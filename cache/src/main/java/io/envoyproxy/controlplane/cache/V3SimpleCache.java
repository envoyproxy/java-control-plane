package io.envoyproxy.controlplane.cache;

public class V3SimpleCache<T> extends SimpleCache<T, V3Snapshot> {
  public V3SimpleCache(NodeGroup<T> nodeGroup) {
    super(nodeGroup);
  }
}
