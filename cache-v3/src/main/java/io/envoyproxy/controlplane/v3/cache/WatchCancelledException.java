package io.envoyproxy.controlplane.v3.cache;

/**
 * {@code WatchCancelledException} indicates that an operation cannot be performed because the watch has already been
 * cancelled.
 */
public class WatchCancelledException extends Exception {

  public WatchCancelledException() {
    super();
  }
}
