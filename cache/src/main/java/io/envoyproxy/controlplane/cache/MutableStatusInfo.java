package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableSet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

public class MutableStatusInfo<T, V extends AbstractWatch<?,?>> implements StatusInfo<T> {
  private final ConcurrentMap<Long, V> watches = new ConcurrentHashMap<>();
  private final T nodeGroup;
  private volatile long lastWatchRequestTime;

  protected MutableStatusInfo(T nodeGroup) {
    this.nodeGroup = nodeGroup;
  }

  /**
   * {@inheritDoc}
   */
  public long lastWatchRequestTime() {
    return lastWatchRequestTime;
  }

  /**
   * {@inheritDoc}
   */
  public T nodeGroup() {
    return nodeGroup;
  }

  /**
   * {@inheritDoc}
   */
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
  public void setWatch(long watchId, V watch) {
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
  public void watchesRemoveIf(BiFunction<Long, V, Boolean> filter) {
    watches.entrySet().removeIf(entry -> filter.apply(entry.getKey(), entry.getValue()));
  }
}
