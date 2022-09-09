package io.envoyproxy.controlplane.cache;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code ConfigWatcher} requests watches for configuration resources by type, node, last applied version identifier,
 * and resource names hint. The watch should send the responses when they are ready. The watch can be cancelled by the
 * consumer, in effect terminating the watch for the request. ConfigWatcher implementations must be thread-safe.
 */
@ThreadSafe
public interface ConfigWatcher {

  /**
   * Returns a new configuration resource {@link Watch} for the given discovery request.
   *
   * @param ads                is the watch for an ADS request?
   * @param request            the discovery request (node, names, etc.) to use to generate the watch
   * @param knownResourceNames resources that are already known to the caller
   * @param responseConsumer   the response handler, used to process outgoing response messages
   * @param hasClusterChanged  Indicates if EDS should be sent immediately, even if version has not been changed.
   *                           Supported in ADS mode.
   */
  Watch createWatch(
      boolean ads,
      XdsRequest request,
      Set<String> knownResourceNames,
      Consumer<Response> responseConsumer,
      boolean hasClusterChanged);

  /**
   * Returns a new configuration resource {@link Watch} for the given discovery request.
   *
   * @param request           the discovery request (node, names, etc.) to use to generate the watch
   * @param requesterVersion  the last version applied by the requester
   * @param resourceVersions  resources that are already known to the requester
   * @param pendingResources  resources that the caller is waiting for
   * @param isWildcard        indicates if the stream is in wildcard mode
   * @param responseConsumer  the response handler, used to process outgoing response messages
   * @param hasClusterChanged indicates if EDS should be sent immediately, even if version has not been changed.
   *                          Supported in ADS mode.
   */
  DeltaWatch createDeltaWatch(
      DeltaXdsRequest request,
      String requesterVersion,
      Map<String, String> resourceVersions,
      Set<String> pendingResources,
      boolean isWildcard,
      Consumer<DeltaResponse> responseConsumer,
      boolean hasClusterChanged);
}
