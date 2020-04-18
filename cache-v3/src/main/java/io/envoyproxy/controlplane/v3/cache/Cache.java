package io.envoyproxy.controlplane.v3.cache;

import io.envoyproxy.envoy.config.core.v3.Node;
import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code Cache} is a generic config cache with support for watchers.
 */
@ThreadSafe
public interface Cache<T> extends ConfigWatcher {

  /**
   * Returns all known {@link Node} groups.
   *
   */
  Collection<T> groups();

  /**
   * Returns the current {@link StatusInfo} for the given {@link Node} group.
   *
   * @param group the node group whose status is being fetched
   */
  StatusInfo statusInfo(T group);
}
