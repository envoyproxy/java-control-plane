package io.envoyproxy.controlplane.server.limits;

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
