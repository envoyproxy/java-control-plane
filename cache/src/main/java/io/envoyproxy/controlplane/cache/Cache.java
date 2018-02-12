package io.envoyproxy.controlplane.cache;

import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code Cache} is a generic config cache with support for watchers.
 */
@ThreadSafe
public interface Cache extends ConfigWatcher {

  /**
   * Set the {@link Snapshot} for the given node group. Snapshots should have distinct versions and be internally
   * consistent (i.e. all referenced resources must be included in the snapshot).
   *
   * @param group hash key for the node group
   * @param snapshot a versioned collection of node config data
   */
  void setSnapshot(String group, Snapshot snapshot);
}
