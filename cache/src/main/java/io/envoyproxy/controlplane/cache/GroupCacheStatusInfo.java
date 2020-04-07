package io.envoyproxy.controlplane.cache;

import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code CacheStatusInfo} provides a default implementation of {@link StatusInfo} for use in {@link Cache}
 * implementations.
 */
@ThreadSafe
class GroupCacheStatusInfo<T> implements StatusInfo<T> {
  private final Collection<CacheStatusInfo<T>> statuses;

  public GroupCacheStatusInfo(Collection<CacheStatusInfo<T>> statuses) {
    this.statuses = statuses;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long lastWatchRequestTime() {
    return statuses.stream().mapToLong(CacheStatusInfo::lastWatchRequestTime).max().orElse(0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T nodeGroup() {
    return statuses.stream().map(CacheStatusInfo::nodeGroup).findFirst().orElse(null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int numWatches() {
    return statuses.stream().mapToInt(CacheStatusInfo::numWatches).sum();
  }
}
