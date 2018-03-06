package io.envoyproxy.controlplane.cache;

import envoy.api.v2.core.Base.Node;

/**
 * {@code StatusInfo} tracks the state for remote envoy nodes.
 */
public interface StatusInfo {

  /**
   * Returns the timestamp of the last discovery watch request.
   */
  long lastWatchRequestTime();

  /**
   * Returns the node metadata.
   */
  Node node();

  /**
   * Returns the number of open watches.
   */
  int numWatches();
}
