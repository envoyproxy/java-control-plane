package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Discovery.DiscoveryRequest;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import envoy.api.v2.core.Base.Node;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.junit.Test;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Mono;

public class SimpleCacheTest {

  private static final boolean ADS = ThreadLocalRandom.current().nextBoolean();
  private static final String CLUSTER_NAME = "cluster0";
  private static final String LISTENER_NAME = "listener0";
  private static final String ROUTE_NAME = "route0";

  private static final String VERSION1 = UUID.randomUUID().toString();
  private static final String VERSION2 = UUID.randomUUID().toString();

  private static final Snapshot SNAPSHOT1 = Snapshot.create(
      ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
      ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
      ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
      ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
      VERSION1);

  private static final Snapshot SNAPSHOT2 = Snapshot.create(
      ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
      ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
      ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
      ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
      VERSION2);

  private static final Map<String, Collection<String>> NAMES = ImmutableMap.of(
      Resources.CLUSTER_TYPE_URL, ImmutableList.of(CLUSTER_NAME),
      Resources.ENDPOINT_TYPE_URL, ImmutableList.of(),
      Resources.LISTENER_TYPE_URL, ImmutableList.of(LISTENER_NAME),
      Resources.ROUTE_TYPE_URL, ImmutableList.of(ROUTE_NAME));

  @Test
  public void invalidNamesListShouldReturnWatcherWithNoResponseInAdsMode() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    Watch watch = cache.createWatch(
        true,
        DiscoveryRequest.newBuilder()
            .setNode(Node.getDefaultInstance())
            .setTypeUrl(Resources.ENDPOINT_TYPE_URL)
            .addResourceNames("none")
            .build());

    assertThatWatchIsOpenWithNoPendingResponses(watch);
  }

  @Test
  public void invalidNamesListShouldReturnWatcherWithResponseInXdsMode() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    Watch watch = cache.createWatch(
        false,
        DiscoveryRequest.newBuilder()
            .setNode(Node.getDefaultInstance())
            .setTypeUrl(Resources.ENDPOINT_TYPE_URL)
            .addResourceNames("none")
            .build());

    assertThat(((EmitterProcessor<Response>) watch.value()).getPending()).isNotZero();
  }

  @Test
  public void successfullyWatchAllResourceTypesWithSetBeforeWatch() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    for (String typeUrl : Resources.TYPE_URLS) {
      Watch watch = cache.createWatch(
          ADS,
          DiscoveryRequest.newBuilder()
              .setNode(Node.getDefaultInstance())
              .setTypeUrl(typeUrl)
              .addAllResourceNames(NAMES.get(typeUrl))
              .build());

      assertThat(watch.request().getTypeUrl()).isEqualTo(typeUrl);
      assertThat(watch.request().getResourceNamesList()).containsExactlyElementsOf(NAMES.get(typeUrl));

      assertThatWatchReceivesSnapshot(watch, SNAPSHOT1);
    }
  }

  @Test
  public void successfullyWatchAllResourceTypesWithSetAfterWatch() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    Map<String, Watch> watches = Resources.TYPE_URLS.stream()
        .collect(Collectors.toMap(
            typeUrl -> typeUrl,
            typeUrl -> cache.createWatch(
                ADS,
                DiscoveryRequest.newBuilder()
                    .setNode(Node.getDefaultInstance())
                    .setTypeUrl(typeUrl)
                    .addAllResourceNames(NAMES.get(typeUrl))
                    .build())));

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    for (String typeUrl : Resources.TYPE_URLS) {
      assertThatWatchReceivesSnapshot(watches.get(typeUrl), SNAPSHOT1);
    }
  }

  @Test
  public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithRequestVersion() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    Map<String, Watch> watches = Resources.TYPE_URLS.stream()
        .collect(Collectors.toMap(
            typeUrl -> typeUrl,
            typeUrl -> cache.createWatch(
                ADS,
                DiscoveryRequest.newBuilder()
                    .setNode(Node.getDefaultInstance())
                    .setTypeUrl(typeUrl)
                    .setVersionInfo(SNAPSHOT1.version(typeUrl))
                    .addAllResourceNames(NAMES.get(typeUrl))
                    .build())));

    // The request version matches the current snapshot version, so the watches shouldn't receive any responses.
    for (String typeUrl : Resources.TYPE_URLS) {
      assertThatWatchIsOpenWithNoPendingResponses(watches.get(typeUrl));
    }

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT2);

    for (String typeUrl : Resources.TYPE_URLS) {
      assertThatWatchReceivesSnapshot(watches.get(typeUrl), SNAPSHOT2);
    }
  }

  @Test
  public void setSnapshotWithVersionMatchingRequestShouldLeaveWatchOpenWithoutAdditionalResponse() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    Map<String, Watch> watches = Resources.TYPE_URLS.stream()
        .collect(Collectors.toMap(
            typeUrl -> typeUrl,
            typeUrl -> cache.createWatch(
                ADS,
                DiscoveryRequest.newBuilder()
                    .setNode(Node.getDefaultInstance())
                    .setTypeUrl(typeUrl)
                    .setVersionInfo(SNAPSHOT1.version(typeUrl))
                    .addAllResourceNames(NAMES.get(typeUrl))
                    .build())));

    // The request version matches the current snapshot version, so the watches shouldn't receive any responses.
    for (String typeUrl : Resources.TYPE_URLS) {
      assertThatWatchIsOpenWithNoPendingResponses(watches.get(typeUrl));
    }

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    // The request version still matches the current snapshot version, so the watches shouldn't receive any responses.
    for (String typeUrl : Resources.TYPE_URLS) {
      assertThatWatchIsOpenWithNoPendingResponses(watches.get(typeUrl));
    }
  }

  @Test
  public void watchesAreReleasedAfterCancel() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    Map<String, Watch> watches = Resources.TYPE_URLS.stream()
        .collect(Collectors.toMap(
            typeUrl -> typeUrl,
            typeUrl -> cache.createWatch(
                ADS,
                DiscoveryRequest.newBuilder()
                    .setNode(Node.getDefaultInstance())
                    .setTypeUrl(typeUrl)
                    .addAllResourceNames(NAMES.get(typeUrl))
                    .build())));

    StatusInfo statusInfo = cache.statusInfo(SingleNodeGroup.GROUP);

    assertThat(statusInfo.numWatches()).isEqualTo(watches.size());

    watches.values().forEach(Watch::cancel);

    assertThat(statusInfo.numWatches()).isZero();

    watches.values().forEach(watch -> assertThat(((EmitterProcessor<Response>) watch.value()).isTerminated()).isTrue());
  }

  @Test
  public void getSnapshot() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isEqualTo(SNAPSHOT1);
  }

  @Test
  public void clearSnapshot() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isTrue();

    assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isNull();
  }

  @Test
  public void clearSnapshotWithWatches() {
    SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

    Watch watch = cache.createWatch(ADS, DiscoveryRequest.newBuilder()
        .setNode(Node.getDefaultInstance())
        .setTypeUrl("")
        .build());

    // clearSnapshot should fail and the snapshot should be left untouched
    assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isFalse();
    assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isEqualTo(SNAPSHOT1);
    assertThat(cache.statusInfo(SingleNodeGroup.GROUP)).isNotNull();

    watch.cancel();

    // now that the watch is gone we should be able to clear it
    assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isTrue();
    assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isNull();
    assertThat(cache.statusInfo(SingleNodeGroup.GROUP)).isNull();
  }

  private static void assertThatWatchIsOpenWithNoPendingResponses(Watch watch) {
    assertThat(((EmitterProcessor<Response>) watch.value()).getPending()).isZero();
    assertThat(((EmitterProcessor<Response>) watch.value()).isTerminated()).isFalse();
  }

  private static void assertThatWatchReceivesSnapshot(Watch watch, Snapshot snapshot) {
    Response response = Mono.from(watch.value()).block(Duration.ofMillis(250));

    assertThat(response).isNotNull();
    assertThat(response.version()).isEqualTo(snapshot.version(watch.request().getTypeUrl()));
    assertThat(response.resources().toArray(new Message[0]))
        .containsExactlyElementsOf(snapshot.resources(watch.request().getTypeUrl()).values());
  }

  private static class SingleNodeGroup implements NodeGroup<String> {

    private static final String GROUP = "node";

    @Override
    public String hash(Node node) {
      if (node == null) {
        throw new IllegalArgumentException("node");
      }

      return GROUP;
    }
  }
}
