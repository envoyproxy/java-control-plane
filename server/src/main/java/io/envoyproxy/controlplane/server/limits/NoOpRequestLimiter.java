package io.envoyproxy.controlplane.server.limits;

/**
 * {@link RequestLimiter} that always permits next request to be processed straight away and does not block the thread.
 */
public class NoOpRequestLimiter implements RequestLimiter {

  @Override
  public void acquire() {
  }

}
