package io.envoyproxy.controlplane.server.limits;

import io.grpc.stub.StreamObserver;

public interface FlowControl<V> {

  void streamOpened();

  void streamClosed();

  void afterRequest();

  @FunctionalInterface
  interface Factory<V> {
    FlowControl<V> get(long streamId,
                       StreamObserver<V> serverCallStreamObserver,
                       RequestLimiter requestLimiter);
  }

  static <T> Factory<T> noOpFactory() {
    return (streamId, stream, limiter) -> new NoOpFlowControl<>();
  }
}
