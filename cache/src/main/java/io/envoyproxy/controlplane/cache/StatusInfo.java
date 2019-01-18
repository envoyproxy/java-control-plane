package io.envoyproxy.controlplane.cache;

import io.envoyproxy.envoy.api.v2.core.Base.Node;

/**
 * {@code StatusInfo} tracks the state for remote envoy nodes.
 */
public interface StatusInfo<T> {

  /**
   * Returns the timestamp of the last discovery watch request.
   */
  long lastWatchRequestTime();

  /**
   * Returns the node grouping represented by this status, generated via {@link NodeGroup#hash(Node)}.
   */
  T nodeGroup();

  /**
   * Returns the number of open watches.
   */
  int numWatches();
}
