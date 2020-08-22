package io.envoyproxy.controlplane.server;

import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import java.util.concurrent.CountDownLatch;

public class V2OnlyDiscoveryServerCallbacks implements DiscoveryServerCallbacks  {
  private final CountDownLatch onStreamOpenLatch;
  private final CountDownLatch onStreamRequestLatch;
  private final CountDownLatch onStreamResponseLatch;

  /**
   * Returns an implementation of DiscoveryServerCallbacks that throws if it sees a v3 request,
   * and counts down on provided latches in response to certain events.
   *
   * @param onStreamOpenLatch latch to call countDown() on when a v2 stream is opened.
   * @param onStreamRequestLatch latch to call countDown() on when a v2 request is seen.
   * @param onStreamResponseLatch latch to call countDown() on when a v2 response is seen.
   */
  public V2OnlyDiscoveryServerCallbacks(CountDownLatch onStreamOpenLatch,
      CountDownLatch onStreamRequestLatch, CountDownLatch onStreamResponseLatch) {
    this.onStreamOpenLatch = onStreamOpenLatch;
    this.onStreamRequestLatch = onStreamRequestLatch;
    this.onStreamResponseLatch = onStreamResponseLatch;
  }

  @Override
  public void onStreamOpen(long streamId, String typeUrl) {
    onStreamOpenLatch.countDown();
  }

  @Override
  public void onV2StreamRequest(long streamId, DiscoveryRequest request) {
    onStreamRequestLatch.countDown();
  }

  @Override
  public void onV3StreamRequest(long streamId,
      io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest request) {
    throw new IllegalStateException("unexpected v3 request in v2 test");
  }

  @Override
  public void onStreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
    onStreamResponseLatch.countDown();
  }

  @Override
  public void onV3StreamResponse(long streamId, io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest request,
      io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse response) {
    throw new IllegalStateException("unexpected v3 response in v2 test");
  }
}
