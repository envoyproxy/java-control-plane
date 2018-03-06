package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableSet;
import envoy.api.v2.core.Base.Node;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code CacheStatusInfo} provides a default implementation of {@link StatusInfo} for use in {@link Cache}
 * implementations.
 */
@ThreadSafe
public class CacheStatusInfo implements StatusInfo {

  private final Node node;

  @GuardedBy("lock")
  private final Map<Long, Watch> watches;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Lock readLock = lock.readLock();
  private final Lock writeLock = lock.writeLock();

  @GuardedBy("lock")
  private long lastWatchRequestTime;

  CacheStatusInfo(Node node) {
    this.node = node;

    watches = new HashMap<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long lastWatchRequestTime() {
    readLock.lock();

    try {
      return lastWatchRequestTime;
    } finally {
      readLock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Node node() {
    return node;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int numWatches() {
    readLock.lock();

    try {
      return watches.size();
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Removes the given watch from the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be removed
   */
  public void removeWatch(long watchId) {
    writeLock.lock();

    try {
      watches.remove(watchId);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Sets the timestamp of the last discovery watch request.
   *
   * @param lastWatchRequestTime the latest watch request timestamp
   */
  public void setLastWatchRequestTime(long lastWatchRequestTime) {
    writeLock.lock();

    try {
      this.lastWatchRequestTime = lastWatchRequestTime;
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Adds the given watch to the tracked collection of watches.
   *
   * @param watchId the ID for the watch that should be added
   * @param watch the watch that should be added
   */
  public void setWatch(long watchId, Watch watch) {
    writeLock.lock();

    try {
      watches.put(watchId, watch);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Returns the set of IDs for all watched currently being tracked.
   */
  public Set<Long> watchIds() {
    readLock.lock();

    try {
      return ImmutableSet.copyOf(watches.keySet());
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Iterate over all tracked watches and execute the given function. If it returns {@code true}, then the watch is
   * removed from the tracked collection. If it returns {@code false}, then the watch is not removed.
   *
   * @param filter the function to execute on each watch
   */
  public void watchesRemoveIf(BiFunction<Long, Watch, Boolean> filter) {
    writeLock.lock();

    try {
      watches.entrySet().removeIf(entry -> filter.apply(entry.getKey(), entry.getValue()));
    } finally {
      writeLock.unlock();
    }
  }
}
