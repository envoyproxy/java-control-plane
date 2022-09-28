package io.envoyproxy.controlplane.cache;

import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code CacheStatusInfo} provides a default implementation of {@link StatusInfo} for use in {@link Cache}
 * implementations.
 */
@ThreadSafe
public class CacheStatusInfo<T> extends MutableStatusInfo<T, Watch> {
  public CacheStatusInfo(T nodeGroup) {
    super(nodeGroup);
  }
}
