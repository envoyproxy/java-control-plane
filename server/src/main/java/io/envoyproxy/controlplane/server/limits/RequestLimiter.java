package io.envoyproxy.controlplane.server.limits;

/**
 * Blocking interface for limiting requests flow in a bidirectional GRPC stream.
 */
public interface RequestLimiter {
  void acquire();
}
