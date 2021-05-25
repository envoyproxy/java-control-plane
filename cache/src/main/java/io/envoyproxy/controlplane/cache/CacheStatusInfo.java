package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code CacheStatusInfo} provides a default implementation of {@link StatusInfo} for use in {@link Cache}
 * implementations.
 */
@ThreadSafe
public class CacheStatusInfo<T> implements StatusInfo<T> {

  private final T nodeGroup;

  private final ConcurrentMap<Long, Watch> watches = new ConcurrentHashMap<>();
  private final ConcurrentMap<Long, DeltaWatch> deltaWatches = new ConcurrentHashMap<>();
  private volatile long lastWatchRequestTime;
  private volatile long lastDeltaWatchRequestTime;

  public CacheStatusInfo(T nodeGroup) {
    this.nodeGroup = nodeGroup;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long lastWatchRequestTime() {
    return lastWatchRequestTime;
  }

  @Override
  public long lastDeltaWatchRequestTime() {
    return lastDeltaWatchRequestTime;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T nodeGroup() {
    return nodeGroup;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int numWatches() {
    return watches.size();
  }

  @Override
  public int numDeltaWatches() {
    return deltaWatches.size();
  }

  /**
   * Removes the given watch from the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be removed
   */
  public void removeWatch(long watchId) {
    watches.remove(watchId);
  }

  /**
   * Removes the given delta watch from the tracked collection of watches.
   *
   * @param watchId the ID for the delta watch that should be removed
   */
  public void removeDeltaWatch(long watchId) {
    deltaWatches.remove(watchId);
  }

  /**
   * Sets the timestamp of the last discovery watch request.
   *
   * @param lastWatchRequestTime the latest watch request timestamp
   */
  public void setLastWatchRequestTime(long lastWatchRequestTime) {
    this.lastWatchRequestTime = lastWatchRequestTime;
  }

  /**
   * Sets the timestamp of the last discovery delta watch request.
   *
   * @param lastDeltaWatchRequestTime the latest delta watch request timestamp
   */
  public void setLastDeltaWatchRequestTime(long lastDeltaWatchRequestTime) {
    this.lastDeltaWatchRequestTime = lastDeltaWatchRequestTime;
  }

  /**
   * Adds the given watch to the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be added
   * @param watch   the watch that should be added
   */
  public void setWatch(long watchId, Watch watch) {
    watches.put(watchId, watch);
  }

  /**
   * Adds the given watch to the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be added
   * @param watch   the watch that should be added
   */
  public void setDeltaWatch(long watchId, DeltaWatch watch) {
    deltaWatches.put(watchId, watch);
  }

  /**
   * Returns the set of IDs for all watched currently being tracked.
   */
  public Set<Long> watchIds() {
    return ImmutableSet.copyOf(watches.keySet());
  }

  /**
   * Returns the set of IDs for all watched currently being tracked.
   */
  public Set<Long> deltaWatchIds() {
    return ImmutableSet.copyOf(deltaWatches.keySet());
  }

  /**
   * Iterate over all tracked watches and execute the given function. If it returns {@code true}, then the watch is
   * removed from the tracked collection. If it returns {@code false}, then the watch is not removed.
   *
   * @param filter the function to execute on each watch
   */
  public void watchesRemoveIf(BiFunction<Long, Watch, Boolean> filter) {
    watches.entrySet().removeIf(entry -> filter.apply(entry.getKey(), entry.getValue()));
  }

  /**
   * Iterate over all tracked delta watches and execute the given function. If it returns {@code true},
   * then the watch is removed from the tracked collection. If it returns {@code false}, then
   * the watch is not removed.
   *
   * @param filter the function to execute on each delta watch
   */
  public void deltaWatchesRemoveIf(BiFunction<Long, DeltaWatch, Boolean> filter) {
    deltaWatches.entrySet().removeIf(entry -> filter.apply(entry.getKey(), entry.getValue()));
  }
}
