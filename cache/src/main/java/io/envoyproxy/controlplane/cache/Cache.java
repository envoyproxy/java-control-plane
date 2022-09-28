package io.envoyproxy.controlplane.cache;

import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code Cache} is a generic config cache with support for watchers.
 */
@ThreadSafe
public interface Cache<T> extends ConfigWatcher {

  /**
   * Returns all known groups.
   *
   */
  Collection<T> groups();

  /**
   * Returns the current {@link StatusInfo} for the given group.
   *
   * @param group the node group whose status is being fetched
   */
  StatusInfo<T> statusInfo(T group);
}
