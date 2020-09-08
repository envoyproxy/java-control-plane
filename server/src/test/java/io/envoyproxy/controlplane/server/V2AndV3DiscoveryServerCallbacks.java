package io.envoyproxy.controlplane.server;

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.concurrent.CountDownLatch;

public class V2AndV3DiscoveryServerCallbacks implements DiscoveryServerCallbacks  {
  private final CountDownLatch onStreamOpenLatch;
  private final CountDownLatch onV2StreamRequestLatch;
  private final CountDownLatch onV2StreamResponseLatch;
  private final CountDownLatch onV3StreamRequestLatch;
  private final CountDownLatch onV3StreamResponseLatch;

  /**
   * Returns an implementation of DiscoveryServerCallbacks that throws if it sees a v2 request,
   * and counts down on provided latches in response to certain events.
   *
   * @param onStreamOpenLatch latch to call countDown() on when a v3 stream is opened.
   * @param onV2StreamRequestLatch latch to call countDown() on when a v3 request is seen.
   * @param onV2StreamResponseLatch latch to call countDown() on when a v3 response is seen.
   * @param onV3StreamRequestLatch latch to call countDown() on when a v3 request is seen.
   * @param onV3StreamResponseLatch latch to call countDown() on when a v3 response is seen.
   */
  public V2AndV3DiscoveryServerCallbacks(
          CountDownLatch onStreamOpenLatch,
          CountDownLatch onV2StreamRequestLatch,
          CountDownLatch onV2StreamResponseLatch,
          CountDownLatch onV3StreamRequestLatch,
          CountDownLatch onV3StreamResponseLatch
  ) {
    this.onStreamOpenLatch = onStreamOpenLatch;
    this.onV2StreamRequestLatch = onV2StreamRequestLatch;
    this.onV2StreamResponseLatch = onV2StreamResponseLatch;
    this.onV3StreamRequestLatch = onV3StreamRequestLatch;
    this.onV3StreamResponseLatch = onV3StreamResponseLatch;
  }

  @Override
  public void onStreamOpen(long streamId, String typeUrl) {
    onStreamOpenLatch.countDown();
  }

  @Override
  public void onV2StreamRequest(long streamId,
      io.envoyproxy.envoy.api.v2.DiscoveryRequest request) {
    onV2StreamRequestLatch.countDown();
  }

  @Override
  public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    onV3StreamRequestLatch.countDown();
  }

  @Override
  public void onStreamResponse(long streamId,
      io.envoyproxy.envoy.api.v2.DiscoveryRequest request,
      io.envoyproxy.envoy.api.v2.DiscoveryResponse response) {
    onV2StreamResponseLatch.countDown();
  }

  @Override
  public void onV3StreamResponse(long streamId, DiscoveryRequest request,
      DiscoveryResponse response) {
    onV3StreamResponseLatch.countDown();
  }
}
