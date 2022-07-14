package io.envoyproxy.controlplane.cache;

import io.envoyproxy.envoy.config.core.v3.Node;

import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code NodeGroup} aggregates config resources by a consistent grouping of {@link Node}s.
 */
@ThreadSafe
public interface NodeGroup<T> {

  /**
   * Returns a consistent identifier of the given {@link Node}.
   *
   * @param node identifier for the envoy instance that is requesting config
   */
  T hash(Node node);
}
