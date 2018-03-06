package io.envoyproxy.controlplane.cache;

import envoy.api.v2.Discovery.DiscoveryRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import reactor.core.publisher.EmitterProcessor;

/**
 * {@code Watch} is a dedicated stream of configuration resources produced by the configuration cache and consumed by
 * the xDS server.
 */
public class Watch {

  private final AtomicBoolean isCancelled = new AtomicBoolean();
  private final DiscoveryRequest request;
  private final EmitterProcessor<Response> value = EmitterProcessor.create();

  private Runnable stop;

  public Watch(DiscoveryRequest request) {
    this.request = request;
  }

  /**
   * Cancel the watch. A watch must be cancelled in order to complete its resource stream and free resources. Cancel
   * may be called multiple times, with each subsequent call being a no-op.
   */
  public void cancel() {
    if (isCancelled.compareAndSet(false, true)) {
      try {
        valueEmitter().onComplete();
      } catch (Exception e) {
        // If the underlying exception was an IllegalStateException then we assume that means the stream was already
        // closed elsewhere and ignore it, otherwise we re-throw.
        if (!(e.getCause() instanceof IllegalStateException)) {
          throw e;
        }
      }

      if (stop != null) {
        stop.run();
      }
    }
  }

  /**
   * Returns the original request for the watch.
   */
  public DiscoveryRequest request() {
    return request;
  }

  /**
   * Returns the stream of response values.
   */
  public Publisher<Response> value() {
    return value;
  }

  void setStop(Runnable stop) {
    this.stop = stop;
  }

  EmitterProcessor<Response> valueEmitter() {
    return value;
  }
}
