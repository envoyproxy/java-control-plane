package io.envoyproxy.controlplane.cache;

import envoy.api.v2.core.Base.Node;
import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code ConfigWatcher} requests watches for configuration resources by type, node, last applied version identifier,
 * and resource names hint. The watch should send the responses when they are ready. The watch can be cancelled by the
 * consumer, in effect terminating the watch for the request. ConfigWatcher implementations must be thread-safe.
 */
@ThreadSafe
public interface ConfigWatcher {

  /**
   * Returns a new configuration resource {@link Watch} for the given type, node, last applied version, and resource
   * names.
   *
   * @param type the response type to watch (e.g. cluster, endpoint, etc.)
   * @param node identifier for the envoy instance that is requesting config
   * @param version the last applied snapshot version
   * @param names requested resource names
   */
  Watch watch(ResourceType type, Node node, String version, Collection<String> names);
}
