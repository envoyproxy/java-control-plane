package io.envoyproxy.controlplane.cache;

public class V2SimpleCache<T> extends SimpleCache<T, V2Snapshot> {
  public V2SimpleCache(NodeGroup<T> nodeGroup) {
    super(nodeGroup);
  }
}
