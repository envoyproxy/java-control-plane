package io.envoyproxy.controlplane.server;

import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.concurrent.CountDownLatch;

public class V3OnlyDiscoveryServerCallbacks implements DiscoveryServerCallbacks  {
  private final CountDownLatch onStreamOpenLatch;
  private final CountDownLatch onStreamRequestLatch;
  private final CountDownLatch onStreamResponseLatch;

  /**
   * Returns an implementation of DiscoveryServerCallbacks that throws if it sees a v2 request,
   * and counts down on provided latches in response to certain events.
   *
   * @param onStreamOpenLatch latch to call countDown() on when a v3 stream is opened.
   * @param onStreamRequestLatch latch to call countDown() on when a v3 request is seen.
   * @param onStreamResponseLatch latch to call countDown() on when a v3 response is seen.
   */
  public V3OnlyDiscoveryServerCallbacks(CountDownLatch onStreamOpenLatch,
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
  public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    onStreamRequestLatch.countDown();
  }

  @Override
  public void onV3StreamDeltaRequest(long streamId,
                                     DeltaDiscoveryRequest request) {
    throw new IllegalStateException("Unexpected delta request");
  }

  @Override
  public void onV3StreamResponse(long streamId, DiscoveryRequest request,
      DiscoveryResponse response) {
    onStreamResponseLatch.countDown();
  }
}
