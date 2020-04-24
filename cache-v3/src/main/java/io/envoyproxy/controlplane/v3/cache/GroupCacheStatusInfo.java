package io.envoyproxy.controlplane.v3.cache;

import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code GroupCacheStatusInfo} provides an implementation of {@link StatusInfo} for a group of {@link CacheStatusInfo}.
 */
@ThreadSafe
public class GroupCacheStatusInfo<T> implements StatusInfo<T> {
  private final Collection<CacheStatusInfo<T>> statuses;

  public GroupCacheStatusInfo(Collection<CacheStatusInfo<T>> statuses) {
    this.statuses = new ArrayList<>(statuses);
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
