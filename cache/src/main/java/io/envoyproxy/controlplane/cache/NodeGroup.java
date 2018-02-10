package io.envoyproxy.controlplane.cache;

import envoy.api.v2.core.Base.Node;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code NodeGroup} aggregates config resources by a hash of the {@link Node}.
 */
@ThreadSafe
public interface NodeGroup {

  /**
   * Returns a consistent hash of the given {@link Node}.
   *
   * @param node identifier for the envoy instance that is requesting config
   */
  String hash(Node node);
}
