package io.envoyproxy.controlplane.cache.v2;

import io.envoyproxy.controlplane.cache.NodeGroup;

@Deprecated
public class SimpleCache<T> extends io.envoyproxy.controlplane.cache.SimpleCache<T, Snapshot> {
  public SimpleCache(NodeGroup<T> nodeGroup) {
    super(nodeGroup);
  }
}
