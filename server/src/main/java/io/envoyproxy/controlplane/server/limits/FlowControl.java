package io.envoyproxy.controlplane.server.limits;

import io.grpc.stub.StreamObserver;

/**
 * Strategy for controlling the flow control of requests in the GRPC bidirectional request-response flow.
 * <p>The interface assumes a blocking GRPC API is being used as implementations are free to block the thread until
 * the flow is allowed to continue processing next request.</p>
 * @param <V> The response type of the controlled stream.
 */
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
