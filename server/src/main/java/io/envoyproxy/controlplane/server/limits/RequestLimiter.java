package io.envoyproxy.controlplane.server.limits;

public interface RequestLimiter {
  void acquire();
}
