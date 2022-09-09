package io.envoyproxy.controlplane.server;

import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class V3DeltaDiscoveryServerCallbacks implements DiscoveryServerCallbacks {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(V3DeltaDiscoveryServerCallbacks.class);

  private final CountDownLatch onStreamOpenLatch;
  private final CountDownLatch onStreamRequestLatch;
  private StringBuffer nonce;
  private StringBuffer errorDetail;
  private ConcurrentHashMap<String, StringBuffer> resourceToNonceMap;


  /**
   * Returns an implementation of DiscoveryServerCallbacks that throws if it sees a nod-delta v3 request,
   * and counts down on provided latches in response to certain events.
   *
   * @param onStreamOpenLatch    latch to call countDown() on when a v3 stream is opened.
   * @param onStreamRequestLatch latch to call countDown() on when a v3 request is seen.
   */
  public V3DeltaDiscoveryServerCallbacks(CountDownLatch onStreamOpenLatch,
      CountDownLatch onStreamRequestLatch,
      StringBuffer nonce,
      StringBuffer errorDetail,
      ConcurrentHashMap<String, StringBuffer> resourceToNonceMap
  ) {
    this.onStreamOpenLatch = onStreamOpenLatch;
    this.onStreamRequestLatch = onStreamRequestLatch;
    this.nonce = nonce;
    this.errorDetail = errorDetail;
    this.resourceToNonceMap = resourceToNonceMap;
  }

  @Override
  public void onStreamOpen(long streamId, String typeUrl) {
    LOGGER.info("onStreamOpen called");
    onStreamOpenLatch.countDown();
  }

  @Override
  public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    LOGGER.error("request={}", request);
    throw new IllegalStateException("Unexpected stream request");

  }

  @Override
  public void onV3StreamDeltaRequest(long streamId,
      DeltaDiscoveryRequest request) {
    LOGGER.info("Got a v3StreamDeltaRequest");
    errorDetail.append(request.getErrorDetail().getMessage());
    StringBuffer resourceNonce = resourceToNonceMap
        .getOrDefault(request.getTypeUrl(), new StringBuffer());
    resourceNonce.append(request.getResponseNonce());
    resourceToNonceMap.put(request.getTypeUrl(), resourceNonce);
    nonce.append(request.getResponseNonce());
    onStreamRequestLatch.countDown();
  }

  @Override
  public void onV3StreamResponse(long streamId, DiscoveryRequest request,
      DiscoveryResponse response) {
    LOGGER.info("Got a v3StreamResponse");
  }
}

