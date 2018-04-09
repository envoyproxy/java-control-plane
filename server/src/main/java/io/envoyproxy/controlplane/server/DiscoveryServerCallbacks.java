package io.envoyproxy.controlplane.server;

import envoy.api.v2.Discovery.DiscoveryRequest;
import envoy.api.v2.Discovery.DiscoveryResponse;

/**
 * {@code DiscoveryServerCallbacks} defines the callbacks that are exposed by the {@link DiscoveryServer}. The callbacks
 * give consumers the opportunity to add their own application-specific logs/metrics based
 */
public interface DiscoveryServerCallbacks {

  /**
   * {@code onStreamClose} is called just before the bi-directional gRPC stream is closed successfully for an envoy
   * instance.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param typeUrl the resource type of the stream, or {@link DiscoveryServer#ANY_TYPE_URL} for ADS
   */
  default void onStreamClose(long streamId, String typeUrl) {

  }

  /**
   * {@code onStreamCloseWithError} is called just before the bi-directional gRPC stream is closed for an envoy instance
   * due to some error that has occurred.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param typeUrl the resource type of the stream, or {@link DiscoveryServer#ANY_TYPE_URL} for ADS
   * @param error the error that caused the stream to close
   */
  default void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {

  }

  /**
   * {@code onStreamOpen} is called when the bi-directional gRPC stream is opened for an envoy instance, before the
   * initial {@link DiscoveryRequest} is processed.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param typeUrl the resource type of the stream, or {@link DiscoveryServer#ANY_TYPE_URL} for ADS
   */
  default void onStreamOpen(long streamId, String typeUrl) {

  }

  /**
   * {@code onStreamRequest} is called for each {@link DiscoveryRequest} that is received on the stream.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param request the discovery request sent by the envoy instance
   */
  default void onStreamRequest(long streamId, DiscoveryRequest request) {

  }

  /**
   * {@code onStreamResponse} is called just before each {@link DiscoveryResponse} that is sent on the stream.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param request the discovery request sent by the envoy instance
   * @param response the discovery response sent by the discovery server
   */
  default void onStreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {

  }
}
