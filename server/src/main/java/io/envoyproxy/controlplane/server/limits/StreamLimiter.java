package io.envoyproxy.controlplane.server.limits;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A provider of {@link ServerInterceptor} for limiting concurrently handled GRPC streams.
 */
public class StreamLimiter {

  private static final Logger LOGGER = LoggerFactory.getLogger(StreamLimiter.class);

  private final long maxStreams;
  private final ServerInterceptor serverInterceptor;
  private final AtomicLong openStreams = new AtomicLong();

  /**
   * StreamLimiter is responsible for configuring a {@link ServerInterceptor} instance,
   * which will take care of limiting concurrently open streams.
   * @param maxStreams the upper limit for concurrently open streams
   */
  public StreamLimiter(long maxStreams) {
    this.maxStreams = maxStreams;
    this.serverInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call,
          Metadata headers,
          ServerCallHandler<ReqT, RespT> next
      ) {
        if (openStreams.get() >= maxStreams) {
          call.close(Status.UNAVAILABLE, new Metadata());
          return new ServerCall.Listener<ReqT>() {};
        }

        final Listener<ReqT> delegate = next.startCall(call, headers);

        openStreams.incrementAndGet();

        LOGGER.debug("stream opened [open/max: {} / {}]", openStreams.get(), maxStreams);

        return new Listener<ReqT>() {
          @Override
          public void onMessage(ReqT message) {
            delegate.onMessage(message);
          }

          @Override
          public void onHalfClose() {
            delegate.onHalfClose();
          }

          @Override
          public void onCancel() {
            openStreams.decrementAndGet();
            LOGGER.debug("stream canceled [open/max: {} / {}]", openStreams.get(), maxStreams);
            delegate.onCancel();
          }

          @Override
          public void onComplete() {
            openStreams.decrementAndGet();
            LOGGER.debug("stream complete [open/max: {} / {}]", openStreams.get(), maxStreams);
            delegate.onComplete();
          }

          @Override
          public void onReady() {
            delegate.onReady();
          }
        };
      }
    };
  }

  public ServerInterceptor getServerInterceptor() {
    return this.serverInterceptor;
  }
}
