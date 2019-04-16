package io.envoyproxy.controlplane.server.limits;

import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManualFlowControl implements FlowControl<DiscoveryResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ManualFlowControl.class);

  private final long streamId;

  private final ServerCallStreamObserver<DiscoveryResponse> serverCallStreamObserver;

  private final AtomicBoolean observerWasReady = new AtomicBoolean(false);

  private final RequestLimiter requestLimiter;

  /**
   * Flow Control implementation that considers back-pressure and request limiting.
   * @param streamId ID of the stream
   * @param serverCallStreamObserver the response observer which allows to control the client's readiness
   * @param requestLimiter a synchronous limiter
   */
  public ManualFlowControl(long streamId,
                           ServerCallStreamObserver<DiscoveryResponse> serverCallStreamObserver,
                           RequestLimiter requestLimiter) {
    this.streamId = streamId;
    this.serverCallStreamObserver = serverCallStreamObserver;
    this.requestLimiter = requestLimiter;
  }

  @Override
  public void streamOpened() {
    serverCallStreamObserver.disableAutoInboundFlowControl();
    serverCallStreamObserver.setOnReadyHandler(() -> {
      if (serverCallStreamObserver.isReady() && observerWasReady.compareAndSet(false, true)) {
        LOGGER.debug("[{}] stream ready", streamId);
        serverCallStreamObserver.request(1);
      }
    });
  }

  @Override
  public void streamClosed() {
  }

  @Override
  public void afterRequest() {
    if (serverCallStreamObserver.isReady()) {
      requestLimiter.acquire();
      serverCallStreamObserver.request(1);
    } else {
      LOGGER.debug("[{}] stream not ready", streamId);
      observerWasReady.set(false);
    }
  }

  /**
   * Factory that creates a {@link ManualFlowControl} instance and validates
   * that it's used with a stream observer compatible with manually controlling the flow.
   * @return {@link ManualFlowControl instance}
   */
  public static Factory<DiscoveryResponse> factory() {
    return (streamId, stream, limiter) -> {
      if (!(stream instanceof ServerCallStreamObserver)) {
        throw new IllegalArgumentException(
            "ManualFlowControl can only be used with ServerCallStreamObserver."
        );
      }
      return new ManualFlowControl(
          streamId, (ServerCallStreamObserver<DiscoveryResponse>) stream, limiter
      );
    };
  }
}
