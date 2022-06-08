package io.envoyproxy.controlplane.server.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

public class SnapshotCollectingCallbackTest {

  private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneId.systemDefault());
  private static final NodeGroup<String> NODE_GROUP = node -> "group";
  private final ArrayList<String> collectedGroups = new ArrayList<>();
  private SnapshotCollectingCallback<String, Snapshot> callback;
  private SimpleCache<String> cache;

  @Before
  public void setUp() {
    collectedGroups.clear();
    cache = new SimpleCache<>(NODE_GROUP);
    cache.setSnapshot("group", Snapshot.createEmpty(""));
    callback = new SnapshotCollectingCallback<>(cache, NODE_GROUP, CLOCK,
            Collections.singleton(collectedGroups::add), 3, 100);
  }

  @Test
  public void testSingleSnapshot() {
    callback.onV3StreamRequest(0, DiscoveryRequest.getDefaultInstance());
    callback.onV3StreamRequest(1, DiscoveryRequest.getDefaultInstance());

    // We have 2 references to the snapshot, this should do nothing.
    callback.deleteUnreferenced(Clock.offset(CLOCK, Duration.ofMillis(5)));
    assertThat(collectedGroups).isEmpty();

    callback.onStreamClose(0, "");

    // We have 1 reference to the snapshot, this should do nothing.
    callback.deleteUnreferenced(Clock.offset(CLOCK, Duration.ofMillis(5)));
    assertThat(collectedGroups).isEmpty();

    callback.onStreamCloseWithError(1, "", new RuntimeException());

    // We have 0 references to the snapshot, but 1 < 3 so it's too early to collect the snapshot.
    callback.deleteUnreferenced(Clock.offset(CLOCK, Duration.ofMillis(1)));
    assertThat(collectedGroups).isEmpty();

    // We have 0 references to the snapshot, and 5 > 3 so we clear out the snapshot.
    callback.deleteUnreferenced(Clock.offset(CLOCK, Duration.ofMillis(5)));
    assertThat(collectedGroups).containsExactly("group");
  }

  @Test
  public void testAsyncCollection() throws InterruptedException {
    CountDownLatch snapshotCollectedLatch = new CountDownLatch(1);
    CountDownLatch deleteUnreferencedLatch = new CountDownLatch(1);

    // Create a cache with 0 expiry delay, which means the snapshot should get collected immediately.
    callback = new SnapshotCollectingCallback<String, Snapshot>(cache, NODE_GROUP, CLOCK,
        ImmutableSet.of(collectedGroups::add, group -> snapshotCollectedLatch.countDown()), -3, 1) {
      @Override synchronized void deleteUnreferenced(Clock clock) {
        super.deleteUnreferenced(clock);
        deleteUnreferencedLatch.countDown();;
      }
    };

    callback.onV3StreamRequest(0, DiscoveryRequest.getDefaultInstance());
    assertThat(deleteUnreferencedLatch.await(100, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(collectedGroups).isEmpty();

    callback.onStreamClose(0, "");
    assertThat(snapshotCollectedLatch.await(100,TimeUnit.MILLISECONDS)).isTrue();
    assertThat(collectedGroups).containsExactly("group");
  }

  @Test
  public void testCloseBeforeRequest() {
    callback.onStreamClose(0, "");
    assertThat(collectedGroups).isEmpty();
  }
}
