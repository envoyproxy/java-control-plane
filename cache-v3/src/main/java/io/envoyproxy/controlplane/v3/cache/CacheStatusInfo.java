package io.envoyproxy.controlplane.v3.cache;

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
  private volatile long lastWatchRequestTime;

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

  /**
   * Removes the given watch from the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be removed
   */
  public void removeWatch(long watchId) {
    watches.remove(watchId);
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
   * Adds the given watch to the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be added
   * @param watch   the watch that should be added
   */
  public void setWatch(long watchId, Watch watch) {
    watches.put(watchId, watch);
  }

  /**
   * Returns the set of IDs for all watched currently being tracked.
   */
  public Set<Long> watchIds() {
    return ImmutableSet.copyOf(watches.keySet());
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
}
