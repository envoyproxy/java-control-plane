package io.envoyproxy.controlplane.cache;

import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code Cache} is a generic config cache with support for watchers.
 */
@ThreadSafe
public interface Cache<T> extends ConfigWatcher {

  /**
   * Set the {@link Snapshot} for the given node group. Snapshots should have distinct versions and be internally
   * consistent (i.e. all referenced resources must be included in the snapshot).
   *
   * @param group group identifier
   * @param snapshot a versioned collection of node config data
   */
  void setSnapshot(T group, Snapshot snapshot);
}
