package io.envoyproxy.controlplane.cache;

public interface SnapshotCache<T> extends Cache<T> {

  /**
   * Returns the most recently set {@link Snapshot} for the given node group.
   *
   * @param group group identifier
   * @return latest snapshot
   */
  Snapshot getSnapshot(T group);

  /**
   * Set the {@link Snapshot} for the given node group. Snapshots should have distinct versions and be internally
   * consistent (i.e. all referenced resources must be included in the snapshot).
   *
   * @param group group identifier
   * @param snapshot a versioned collection of node config data
   */
  void setSnapshot(T group, Snapshot snapshot);
}
