package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import envoy.api.v2.core.Base.Node;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class SimpleCacheTest {

  private static final String CLUSTER_NAME = "cluster0";
  private static final String LISTENER_NAME = "listener0";
  private static final String ROUTE_NAME = "route0";

  private static final String VERSION = UUID.randomUUID().toString();
  private static final Snapshot SNAPSHOT = Snapshot.create(
      ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
      ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
      ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
      ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
      VERSION);
  private static final Map<ResourceType, Collection<String>> NAMES = ImmutableMap.of(
      ResourceType.CLUSTER, ImmutableList.of(CLUSTER_NAME),
      ResourceType.ENDPOINT, ImmutableList.of(),
      ResourceType.LISTENER, ImmutableList.of(LISTENER_NAME),
      ResourceType.ROUTE, ImmutableList.of(ROUTE_NAME));


  @Test
  public void invalidNamesListShouldReturnWatcherWithNoResponse() {
    SimpleCache cache = new SimpleCache(null, new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT);

    Watch watch = cache.watch(
        ResourceType.ENDPOINT,
        Node.getDefaultInstance(),
        "",
        ImmutableList.of("none"));

    assertThat(watch.valueEmitter().getPending()).isZero();
  }

  @Test
  public void watchNullNodeReturnWatcherWithNoResponse() {
    SimpleCache cache = new SimpleCache(null, new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT);

    Watch watch = cache.watch(ResourceType.LISTENER, null, "", ImmutableList.of());

    assertThat(watch.valueEmitter().getPending()).isZero();
  }

  @Test
  public void successfullyWatchAllResourceTypesWithSetBeforeWatch() {
    SimpleCache cache = new SimpleCache(null, new SingleNodeGroup());

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT);

    for (ResourceType type : ResourceType.values()) {
      Watch watch = cache.watch(type, Node.getDefaultInstance(), "", NAMES.get(type));

      assertThat(watch.type()).isEqualByComparingTo(type);
      assertThat(watch.names()).containsExactlyElementsOf(NAMES.get(type));

      Response response = Mono.from(watch.value()).block(Duration.ofMillis(250));

      assertThat(response).isNotNull();
      assertThat(response.version()).isEqualTo(VERSION);
      assertThat(response.resources()).containsExactlyElementsOf(SNAPSHOT.resources().get(type));
    }
  }

  @Test
  public void successfullyWatchAllResourceTypesWithSetAfterWatch() {
    SimpleCache cache = new SimpleCache(null, new SingleNodeGroup());

    Map<ResourceType, Watch> watches = Arrays.stream(ResourceType.values())
        .collect(Collectors.toMap(
            type -> type,
            type -> cache.watch(type, Node.getDefaultInstance(), "", NAMES.get(type))));

    cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT);

    for (ResourceType type : ResourceType.values()) {
      Response response = Mono.from(watches.get(type).value()).block(Duration.ofMillis(250));

      assertThat(response).isNotNull();
      assertThat(response.version()).isEqualTo(VERSION);
      assertThat(response.resources()).containsExactlyElementsOf(SNAPSHOT.resources().get(type));
    }
  }

  @Test
  public void watchesAreReleasedAfterCancel() {
    SimpleCache cache = new SimpleCache(null, new SingleNodeGroup());

    Map<ResourceType, Watch> watches = Arrays.stream(ResourceType.values())
        .collect(Collectors.toMap(
            type -> type,
            type -> cache.watch(type, Node.getDefaultInstance(), "", NAMES.get(type))));

    assertThat(cache.watches().get(SingleNodeGroup.GROUP)).hasSize(watches.size());

    watches.values().forEach(Watch::cancel);

    assertThat(cache.watches().get(SingleNodeGroup.GROUP)).isEmpty();

    watches.values().forEach(watch -> assertThat(watch.valueEmitter().isTerminated()).isTrue());
  }

  @Test
  public void cacheCallbackIsExecuted() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> receivedKey = new AtomicReference<>();

    SimpleCache cache = new SimpleCache(
        key -> {
          receivedKey.set(key);
          latch.countDown();
        },
        new SingleNodeGroup());

    cache.watch(ResourceType.LISTENER, Node.getDefaultInstance(), "", ImmutableList.of());

    if (!latch.await(250, TimeUnit.MILLISECONDS)) {
      fail("timed out waiting for callback");
    }

    assertThat(receivedKey).hasValue(SingleNodeGroup.GROUP);
  }

  private static class SingleNodeGroup implements NodeGroup {

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
