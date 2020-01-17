package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableSet;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * {@code CacheStatusInfo} provides a default implementation of {@link StatusInfo} for use in {@link Cache}
 * implementations.
 */
@ThreadSafe
public class CacheStatusInfo<T> implements StatusInfo<T> {

  private final T nodeGroup;

  private final ConcurrentMap<String, Map<Long, Watch>> watches = new ConcurrentHashMap<>();
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
    return watches.values().stream().mapToInt(Map::size).sum();
  }

  /**
   * Removes the given watch from the tracked collection of watches.
   *
   * @param typeUrl type of resource from which watch should be removed
   * @param watchId the ID for the watch that should be removed
   */
  public void removeWatch(String typeUrl, Long watchId) {
    if (watches.containsKey(typeUrl)) {
      watches.get(typeUrl).remove(watchId);
    }
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
   * @param typeUrl type of resource from which watch should be removed
   * @param watchId the ID for the watch that should be added
   * @param watch   the watch that should be added
   */
  public void setWatch(String typeUrl, Long watchId, Watch watch) {
    if (!watches.containsKey(typeUrl)) {
      watches.put(typeUrl, new ConcurrentHashMap<>());
    }
    watches.get(typeUrl).put(watchId, watch);
  }

  /**
   * Returns the set of IDs for all watched currently being tracked.
   */
  public Set<Long> watchIds() {
    return ImmutableSet.copyOf(
        watches.values().stream().flatMap(
            typeResources -> typeResources.keySet().stream()
        ).collect(Collectors.toSet())
    );
  }

  /**
   * Iterate over all tracked watches and execute the given function. If it returns {@code true}, then the watch is
   * removed from the tracked collection. If it returns {@code false}, then the watch is not removed.
   *
   * @param filter the function to execute on each watch
   */
  public void watchesRemoveIf(String typeUrl, BiFunction<Long, Watch, Boolean> filter) {
    if (watches.containsKey(typeUrl)) {
      watches.get(typeUrl).entrySet().removeIf(entry -> filter.apply(entry.getKey(), entry.getValue()));
    }
  }
}
