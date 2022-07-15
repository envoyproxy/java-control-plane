package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.Test;

public class CacheStatusInfoTest {

  @Test
  public void nodeGroupReturnsExpectedGroup() {
    Node node = Node.newBuilder().setId(UUID.randomUUID().toString()).build();

    CacheStatusInfo<Node> info = new CacheStatusInfo<>(node);

    assertThat(info.nodeGroup()).isSameAs(node);
  }

  @Test
  public void lastWatchRequestTimeReturns0IfNotSet() {
    CacheStatusInfo<Node> info = new CacheStatusInfo<>(Node.getDefaultInstance());

    assertThat(info.lastWatchRequestTime()).isZero();
  }

  @Test
  public void lastWatchRequestTimeReturnsExpectedValueIfSet() {
    final long lastWatchRequestTime = ThreadLocalRandom.current().nextLong(10000, 50000);

    CacheStatusInfo<Node> info = new CacheStatusInfo<>(Node.getDefaultInstance());

    info.setLastWatchRequestTime(lastWatchRequestTime);

    assertThat(info.lastWatchRequestTime()).isEqualTo(lastWatchRequestTime);
  }

  @Test
  public void numWatchesReturnsExpectedSize() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();
    final long watchId1 = ThreadLocalRandom.current().nextLong(10000, 50000);
    final long watchId2 = ThreadLocalRandom.current().nextLong(50000, 100000);

    CacheStatusInfo<Node> info = new CacheStatusInfo<>(Node.getDefaultInstance());

    assertThat(info.numWatches()).isZero();

    info.setWatch(watchId1, new Watch(ads,
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()), r -> { }));

    assertThat(info.numWatches()).isEqualTo(1);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId1);

    info.setWatch(watchId2, new Watch(ads,
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()), r -> { }));

    assertThat(info.numWatches()).isEqualTo(2);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId1, watchId2);

    info.removeWatch(watchId1);

    assertThat(info.numWatches()).isEqualTo(1);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId2);
  }

  @Test
  public void watchesRemoveIfRemovesExpectedWatches() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();
    final long watchId1 = ThreadLocalRandom.current().nextLong(10000, 50000);
    final long watchId2 = ThreadLocalRandom.current().nextLong(50000, 100000);

    CacheStatusInfo<Node> info = new CacheStatusInfo<>(Node.getDefaultInstance());

    info.setWatch(watchId1, new Watch(ads,
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()), r -> { }));
    info.setWatch(watchId2, new Watch(ads,
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()), r -> { }));

    assertThat(info.numWatches()).isEqualTo(2);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId1, watchId2);

    info.watchesRemoveIf((watchId, watch) -> watchId.equals(watchId1));

    assertThat(info.numWatches()).isEqualTo(1);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId2);
  }

  @Test
  public void testConcurrentSetWatchAndRemove() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();
    final int watchCount = 50;

    CacheStatusInfo<Node> info = new CacheStatusInfo<>(Node.getDefaultInstance());

    Collection<Long> watchIds = LongStream.range(0, watchCount).boxed().collect(Collectors.toList());

    watchIds.parallelStream().forEach(watchId -> {
      Watch watch = new Watch(ads, XdsRequest.create(DiscoveryRequest.getDefaultInstance()),
          r -> { });

      info.setWatch(watchId, watch);
    });

    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchIds.toArray(new Long[0]));
    assertThat(info.numWatches()).isEqualTo(watchIds.size());

    watchIds.parallelStream().forEach(info::removeWatch);

    assertThat(info.watchIds()).isEmpty();
    assertThat(info.numWatches()).isZero();
  }
}
