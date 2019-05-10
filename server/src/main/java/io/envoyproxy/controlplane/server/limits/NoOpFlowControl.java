package io.envoyproxy.controlplane.server.limits;

/**
 * {@link FlowControl} implementation that effectively doesn't influence the default flow control implementation
 * provided by grpc-java (automatic).
 * @param <V> The response type of the controlled stream.
 */
public class NoOpFlowControl<V> implements FlowControl<V> {

  @Override
  public void streamOpened() {
  }

  @Override
  public void streamClosed() {
  }

  @Override
  public void afterRequest() {
  }
}
