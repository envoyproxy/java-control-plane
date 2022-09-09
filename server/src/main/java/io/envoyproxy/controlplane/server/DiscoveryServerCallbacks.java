package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

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
   * {@code onV3StreamRequest} is called for each {@link DiscoveryRequest}
   * that is received on the stream.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param request the discovery request sent by the envoy instance
   *
   * @throws RequestException optionally can throw {@link RequestException} with custom status. That status
   *     will be returned to the client and the stream will be closed with error.
   */
  void onV3StreamRequest(long streamId, DiscoveryRequest request);

  /**
   * {@code onV3StreamRequest} is called for each {@link io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest}
   * that is received on the stream.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param request the discovery request sent by the envoy instance
   *
   * @throws RequestException optionally can throw {@link RequestException} with custom status. That status
   *     will be returned to the client and the stream will be closed with error.
   */
  void onV3StreamDeltaRequest(long streamId,
                              DeltaDiscoveryRequest request);

  /**
   * {@code onV3StreamResponse} is called just before each
   * {@link DiscoveryResponse} that is sent on the stream.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param request the discovery request sent by the envoy instance
   * @param response the discovery response sent by the discovery server
   */
  default void onV3StreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
  }

  /**
   * {@code onV3StreamResponse} is called just before each
   * {@link io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse} that is sent on the stream.
   *
   * @param streamId an ID for this stream that is only unique to this discovery server instance
   * @param request the discovery request sent by the envoy instance
   * @param response the discovery response sent by the discovery server
   */
  default void onV3StreamDeltaResponse(long streamId,
                                       DeltaDiscoveryRequest request,
                                       DeltaDiscoveryResponse response) {
  }
}
