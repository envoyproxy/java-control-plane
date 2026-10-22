package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.V3.SECRET_TYPE_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * Unit tests for {@link LinearCache}, focused on the behaviors the fork relies on: O(1) targeted notification,
 * delta named-subscription filtering, and content-hash versioning for TLS certificate rotation. Several cases
 * are Java ports of go-control-plane's {@code linear_test.go} / {@code delta_test.go}.
 */
public class LinearCacheTest {

  private static final String GROUP = "node";
  private static final String R0 = "secret0";
  private static final String R1 = "secret1";

  private static Secret secret(String name, String value) {
    return Secret.newBuilder()
        .setName(name)
        .setTlsCertificate(TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString(value).build()))
        .build();
  }

  private static String contentHash(Message message) {
    return Hashing.sha256().hashBytes(message.toByteArray()).toString();
  }

  /**
   * Collects a response's resources into a {@code List<Message>} so assertj varargs infer cleanly.
   */
  private static List<Message> resourcesOf(Response response) {
    return new ArrayList<>(response.resources());
  }

  private static XdsRequest sotw(List<String> names, String version) {
    DiscoveryRequest.Builder builder = DiscoveryRequest.newBuilder()
        .setNode(Node.getDefaultInstance())
        .setTypeUrl(SECRET_TYPE_URL)
        .setVersionInfo(version);
    names.forEach(builder::addResourceNames);
    return XdsRequest.create(builder.build());
  }

  private static DeltaXdsRequest delta() {
    return DeltaXdsRequest.create(DeltaDiscoveryRequest.newBuilder()
        .setNode(Node.getDefaultInstance())
        .setTypeUrl(SECRET_TYPE_URL)
        .build());
  }

  private static LinearCache<String> newCache() {
    return new LinearCache<>(ResourceType.SECRET, GROUP);
  }

  /**
   * Opens a sotw watch that stays open (no immediate response) by first probing the current version, then
   * issuing the real request at that version with matching known resource names.
   */
  private WatchAndTracker openSotw(LinearCache<String> cache, List<String> names) {
    ResponseTracker probe = new ResponseTracker();
    cache.createWatch(false, sotw(names, ""), Collections.emptySet(), probe, false, false);
    String version = probe.responses.isEmpty() ? "" : probe.responses.getLast().version();

    ResponseTracker tracker = new ResponseTracker();
    Set<String> known = new HashSet<>(names);
    Watch watch = cache.createWatch(false, sotw(names, version), known, tracker, false, false);
    return new WatchAndTracker(watch, tracker);
  }

  @Test
  public void sotwNamedWatchIsNotifiedOnlyForItsOwnResource() {
    LinearCache<String> cache = newCache();
    cache.updateResource(R0, secret(R0, "v0"));
    cache.updateResource(R1, secret(R1, "v0"));

    WatchAndTracker a = openSotw(cache, Collections.singletonList(R0));
    WatchAndTracker b = openSotw(cache, Collections.singletonList(R1));
    assertThat(a.tracker.responses).isEmpty();
    assertThat(b.tracker.responses).isEmpty();

    // Change only R0: only watch A must be notified.
    cache.updateResource(R0, secret(R0, "v1"));

    assertThat(a.tracker.responses).hasSize(1);
    assertThat(b.tracker.responses).isEmpty();
    assertThat(resourcesOf(a.tracker.responses.getFirst())).containsExactly(secret(R0, "v1"));
  }

  @Test
  public void sotwWildcardWatchIsNotifiedOnAnyChange() {
    LinearCache<String> cache = newCache();
    cache.updateResource(R0, secret(R0, "v0"));

    WatchAndTracker wildcard = openSotw(cache, Collections.emptyList());
    assertThat(wildcard.tracker.responses).isEmpty();

    cache.updateResource(R1, secret(R1, "v0"));

    assertThat(wildcard.tracker.responses).hasSize(1);
    assertThat(resourcesOf(wildcard.tracker.responses.getFirst()))
        .containsExactlyInAnyOrder(secret(R0, "v0"), secret(R1, "v0"));
  }

  @Test
  public void sotwSameContentUpdateDoesNotResend() {
    LinearCache<String> cache = newCache();
    cache.updateResource(R0, secret(R0, "v0"));

    WatchAndTracker a = openSotw(cache, Collections.singletonList(R0));

    // Re-set the same content: the names-scoped version is unchanged, so no duplicate response is sent.
    cache.updateResource(R0, secret(R0, "v0"));

    assertThat(a.tracker.responses).isEmpty();
  }

  @Test
  public void sotwDeleteNotifiesWildcard() {
    LinearCache<String> cache = newCache();
    cache.updateResource(R0, secret(R0, "v0"));

    WatchAndTracker wildcard = openSotw(cache, Collections.emptyList());

    cache.deleteResource(R0);

    assertThat(wildcard.tracker.responses).hasSize(1);
    assertThat(wildcard.tracker.responses.getFirst().resources()).isEmpty();
  }

  @Test
  public void certRotationChangesVersionAndContent() {
    LinearCache<String> cache = newCache();
    Secret before = secret(R0, "old-cert");
    cache.updateResource(R0, before);

    WatchAndTracker a = openSotw(cache, Collections.singletonList(R0));
    final String openVersion = a.watch.request().getVersionInfo();

    Secret after = secret(R0, "new-cert");
    cache.updateResource(R0, after);

    assertThat(a.tracker.responses).hasSize(1);
    Response response = a.tracker.responses.getFirst();
    assertThat(resourcesOf(response)).containsExactly(after);
    // The response version must differ from what the watch was opened at — otherwise Envoy would keep the
    // old certificate. The strong content hash guarantees this.
    assertThat(response.version()).isNotEqualTo(openVersion);
    assertThat(contentHash(after)).isNotEqualTo(contentHash(before));
  }

  // ---- Delta ----

  @Test
  public void deltaNonWildcardReturnsOnlySubscribedResource() {
    LinearCache<String> cache = newCache();
    cache.updateResource(R0, secret(R0, "v0"));
    cache.updateResource(R1, secret(R1, "v0"));

    DeltaResponseTracker tracker = new DeltaResponseTracker();
    // requesterVersion "" (mismatch) drives the full-diff path; pending = {R0}, not wildcard.
    cache.createDeltaWatch(delta(), "", Collections.emptyMap(),
        new HashSet<>(Collections.singletonList(R0)), false, tracker, false);

    assertThat(tracker.responses).hasSize(1);
    DeltaResponse response = tracker.responses.getFirst();
    assertThat(response.resources().keySet()).containsExactly(R0);
    assertThat(response.removedResources()).isEmpty();
  }

  @Test
  public void deltaUpdateNotifiesOnlyTrackingWatchAndCarriesNewVersionOnRotation() {
    LinearCache<String> cache = newCache();
    Secret r0v0 = secret(R0, "v0");
    cache.updateResource(R0, r0v0);
    cache.updateResource(R1, secret(R1, "v0"));

    // Open a delta watch tracking only R0 at the current cache version (so it stays open).
    DeltaResponseTracker tracker = new DeltaResponseTracker();
    Map<String, String> tracked = ImmutableMap.of(R0, contentHash(r0v0));
    cache.createDeltaWatch(delta(), cacheVersion(cache), tracked,
        Collections.emptySet(), false, tracker, false);
    assertThat(tracker.responses).isEmpty();

    // Updating R1 (not tracked, not wildcard) must NOT notify this watch.
    cache.updateResource(R1, secret(R1, "v1"));
    assertThat(tracker.responses).isEmpty();

    // Rotating R0 must notify, with the new content.
    Secret r0v1 = secret(R0, "v1");
    cache.updateResource(R0, r0v1);
    assertThat(tracker.responses).hasSize(1);
    assertThat(tracker.responses.getFirst().resources()).containsKey(R0);
    assertThat(tracker.responses.getFirst().resources().get(R0).resource()).isEqualTo(r0v1);
  }

  @Test
  public void deltaDeleteSendsRemoved() {
    LinearCache<String> cache = newCache();
    Secret r0v0 = secret(R0, "v0");
    cache.updateResource(R0, r0v0);

    DeltaResponseTracker tracker = new DeltaResponseTracker();
    Map<String, String> tracked = ImmutableMap.of(R0, contentHash(r0v0));
    cache.createDeltaWatch(delta(), cacheVersion(cache), tracked,
        Collections.emptySet(), false, tracker, false);
    assertThat(tracker.responses).isEmpty();

    cache.deleteResource(R0);

    assertThat(tracker.responses).hasSize(1);
    assertThat(tracker.responses.getFirst().removedResources()).containsExactly(R0);
  }

  @Test
  public void accessorsReflectCacheContents() {
    LinearCache<String> cache = newCache();
    assertThat(cache.numResources()).isZero();

    Secret s0 = secret(R0, "v0");
    Secret s1 = secret(R1, "v0");
    cache.updateResource(R0, s0);
    cache.updateResource(R1, s1);

    assertThat(cache.numResources()).isEqualTo(2);
    assertThat(cache.getResources()).containsOnlyKeys(R0, R1);
    assertThat(cache.getResources().get(R0)).isEqualTo(s0);
    assertThat(cache.resourceNames()).containsExactlyInAnyOrder(R0, R1);

    cache.deleteResource(R0);
    assertThat(cache.numResources()).isEqualTo(1);
    assertThat(cache.getResources()).containsOnlyKeys(R1);
  }

  /** Helper to learn the current cache version string by probing a wildcard delta watch's open version. */
  private static String cacheVersion(LinearCache<String> cache) {
    // The wildcard sotw version equals the cache-global version; probe it.
    ResponseTracker probe = new ResponseTracker();
    cache.createWatch(false,
        XdsRequest.create(DiscoveryRequest.newBuilder()
            .setNode(Node.getDefaultInstance()).setTypeUrl(SECRET_TYPE_URL).setVersionInfo("").build()),
        Collections.emptySet(), probe, false, false);
    return probe.responses.isEmpty() ? "" : probe.responses.getLast().version();
  }

  static class ResponseTracker implements Consumer<Response> {
    final LinkedList<Response> responses = new LinkedList<>();

    @Override
    public void accept(Response response) {
      responses.add(response);
    }
  }

  static class DeltaResponseTracker implements Consumer<DeltaResponse> {
    final LinkedList<DeltaResponse> responses = new LinkedList<>();

    @Override
    public void accept(DeltaResponse response) {
      responses.add(response);
    }
  }

  static class WatchAndTracker {
    final Watch watch;
    final ResponseTracker tracker;

    WatchAndTracker(Watch watch, ResponseTracker tracker) {
      this.watch = watch;
      this.tracker = tracker;
    }
  }
}
