package io.envoyproxy.controlplane.server.limits;

public class NoOpRequestLimiter implements RequestLimiter {
  @Override
  public void acquire() {
  }
}
