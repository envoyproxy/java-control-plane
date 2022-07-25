package io.envoyproxy.controlplane.server.callback;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.SnapshotCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Callback that keeps track of the number of streams associated with each node group and periodically clears
 * out {@link Snapshot}s from the cache that are no longer referenced by any streams.
 *
 * <p>Works by monitoring the stream to determine what group they belong to and keeps a running count as well
 * as when a request is seen that targets a given node group.
 *
 * <p>Every {@code collectionIntervalMillis} milliseconds a cleanup job runs which looks for snapshots with no
 * active streams that haven't been updated within the configured time frame. Checking the time since last update
 * is done to prevent snapshots from being prematurely removed from the cache. It ensures that a group must have
 * no active streams for {@code collectAfterMillis} milliseconds before being collected.
 *
 * <p>To be notified of snapshots that are removed, a set of callbacks may be provided which will be triggered
 * whenever a snapshot is removed from the cache. Any other callback which maintains state about the snapshots
 * that is cleaned up by one of these callbacks should be run *after* this callback. This helps ensure that
 * if state is cleaned up while a request in inbound, the request will be blocked by the lock in this callback
 * until collection finishes and the subsequent callbacks will see the new request come in after collection. If the
 * order is reversed, another callback might have seen the new request but the refcount here hasn't been incremented,
 * causing it to get cleaned up and wipe the state of the other callback even though we now have an active stream
 * for that group.
 */
public class SnapshotCollectingCallback<T, X extends io.envoyproxy.controlplane.cache.Snapshot>
    implements DiscoveryServerCallbacks {
  private final SnapshotCache<T, X> snapshotCache;
  private final NodeGroup<T> nodeGroup;
  private final Clock clock;
  private final Set<Consumer<T>> collectorCallbacks;
  private final long collectAfterMillis;
  private final Map<T, SnapshotState> snapshotStates = new ConcurrentHashMap<>();
  private final Map<Long, T> groupByStream = new ConcurrentHashMap<>();

  /**
   * Creates the callback.
   *
   * @param snapshotCache            the cache to evict snapshots from
   * @param nodeGroup                the node group used to map requests to groups
   * @param clock                    system clock
   * @param collectorCallbacks       the callbacks to invoke when snapshot is collected
   * @param collectAfterMillis       how long a snapshot must be referenced for before being collected
   * @param collectionIntervalMillis how often the collection background action should run
   */
  public SnapshotCollectingCallback(SnapshotCache<T, X> snapshotCache,
                                    NodeGroup<T> nodeGroup, Clock clock, Set<Consumer<T>> collectorCallbacks,
                                    long collectAfterMillis, long collectionIntervalMillis) {
    this.snapshotCache = snapshotCache;
    this.nodeGroup = nodeGroup;
    this.clock = clock;
    this.collectorCallbacks = collectorCallbacks;
    this.collectAfterMillis = collectAfterMillis;
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("snapshot-gc-%d").build());
    executorService.scheduleAtFixedRate(() -> deleteUnreferenced(clock), collectionIntervalMillis,
        collectionIntervalMillis, TimeUnit.MILLISECONDS);
  }

  public synchronized void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    T groupIdentifier = nodeGroup.hash(request.getNode());
    updateState(streamId, groupIdentifier);
  }

  @Override
  public void onV3StreamDeltaRequest(long streamId,
                                     DeltaDiscoveryRequest request) {
    T groupIdentifier = nodeGroup.hash(request.getNode());
    updateState(streamId, groupIdentifier);
  }

  private void updateState(long streamId, T groupIdentifier) {
    SnapshotState snapshotState =
        this.snapshotStates.computeIfAbsent(groupIdentifier, x -> new SnapshotState());
    snapshotState.lastSeen = clock.instant();

    if (groupByStream.put(streamId, groupIdentifier) == null) {
      snapshotState.streamCount++;
    }
  }

  @Override
  public void onStreamClose(long streamId, String typeUrl) {
    onStreamCloseHelper(streamId);
  }

  @Override
  public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
    onStreamCloseHelper(streamId);
  }

  @VisibleForTesting
  synchronized void deleteUnreferenced(Clock clock) {
    // Keep track of snapshots to delete to avoid CME.
    Set<T> toDelete = new LinkedHashSet<>();

    for (Map.Entry<T, SnapshotState> entry : snapshotStates.entrySet()) {
      if (entry.getValue().streamCount == 0 && entry.getValue().lastSeen.isBefore(
          clock.instant().minus(collectAfterMillis, ChronoUnit.MILLIS))) {

        // clearSnapshot will do nothing and return false if there are any pending watches - this
        // ensures that we don't actually remove a snapshot that's in use.
        T groupIdentifier = entry.getKey();
        if (snapshotCache.clearSnapshot(groupIdentifier)) {
          toDelete.add(groupIdentifier);
        }
      }
    }

    toDelete.forEach(group -> {
      snapshotStates.remove(group);
      collectorCallbacks.forEach(cb -> cb.accept(group));
    });
  }

  private synchronized void onStreamCloseHelper(long streamId) {
    T removed = groupByStream.remove(streamId);
    if (removed == null) {
      // This will happen if the stream closed before we received the first request.
      return;
    }

    SnapshotState snapshotState = snapshotStates.get(removed);
    snapshotState.streamCount--;
    snapshotState.lastSeen = clock.instant();
  }

  private static class SnapshotState {
    int streamCount;
    Instant lastSeen;
  }
}
