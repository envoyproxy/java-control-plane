package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import envoy.api.v2.Discovery.DiscoveryRequest;
import envoy.api.v2.core.Base.Node;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;

public class CacheStatusInfoTest {

  @Test
  public void nodeReturnsExpectedInstance() {
    Node node = Node.newBuilder().setId(UUID.randomUUID().toString()).build();

    CacheStatusInfo info = new CacheStatusInfo(node);

    assertThat(info.node()).isSameAs(node);
  }

  @Test
  public void lastWatchRequestTimeReturns0IfNotSet() {
    CacheStatusInfo info = new CacheStatusInfo(Node.getDefaultInstance());

    assertThat(info.lastWatchRequestTime()).isZero();
  }

  @Test
  public void lastWatchRequestTimeReturnsExpectedValueIfSet() {
    final long lastWatchRequestTime = ThreadLocalRandom.current().nextLong(10000, 50000);

    CacheStatusInfo info = new CacheStatusInfo(Node.getDefaultInstance());

    info.setLastWatchRequestTime(lastWatchRequestTime);

    assertThat(info.lastWatchRequestTime()).isEqualTo(lastWatchRequestTime);
  }

  @Test
  public void numWatchesReturnsExpectedSize() {
    final long watchId1 = ThreadLocalRandom.current().nextLong(10000, 50000);
    final long watchId2 = ThreadLocalRandom.current().nextLong(50000, 100000);

    CacheStatusInfo info = new CacheStatusInfo(Node.getDefaultInstance());

    assertThat(info.numWatches()).isZero();

    info.setWatch(watchId1, new Watch(DiscoveryRequest.getDefaultInstance()));

    assertThat(info.numWatches()).isEqualTo(1);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId1);

    info.setWatch(watchId2, new Watch(DiscoveryRequest.getDefaultInstance()));

    assertThat(info.numWatches()).isEqualTo(2);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId1, watchId2);

    info.removeWatch(watchId1);

    assertThat(info.numWatches()).isEqualTo(1);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId2);
  }

  @Test
  public void watchesRemoveIfRemovesExpectedWatches() {
    final long watchId1 = ThreadLocalRandom.current().nextLong(10000, 50000);
    final long watchId2 = ThreadLocalRandom.current().nextLong(50000, 100000);

    CacheStatusInfo info = new CacheStatusInfo(Node.getDefaultInstance());

    info.setWatch(watchId1, new Watch(DiscoveryRequest.getDefaultInstance()));
    info.setWatch(watchId2, new Watch(DiscoveryRequest.getDefaultInstance()));

    assertThat(info.numWatches()).isEqualTo(2);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId1, watchId2);

    info.watchesRemoveIf((watchId, watch) -> watchId.equals(watchId1));

    assertThat(info.numWatches()).isEqualTo(1);
    assertThat(info.watchIds()).containsExactlyInAnyOrder(watchId2);
  }
}
