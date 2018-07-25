package io.envoyproxy.controlplane.cache;

import envoy.api.v2.Discovery.DiscoveryRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@code Watch} is a dedicated stream of configuration resources produced by the configuration cache and consumed by
 * the xDS server.
 */
public class Watch {

  private final boolean ads;
  private final AtomicBoolean isCancelled = new AtomicBoolean();
  private final DiscoveryRequest request;
  private final Consumer<Response> responseConsumer;

  private Runnable stop;

  /**
   * Construct a watch.
   *
   * @param ads is this watch for an ADS request?
   * @param request the original request for the watch
   * @param responseConsumer handler for outgoing response messages
   */
  public Watch(boolean ads, DiscoveryRequest request, Consumer<Response> responseConsumer) {
    this.ads = ads;
    this.request = request;
    this.responseConsumer = responseConsumer;
  }

  /**
   * Returns boolean indicating whether or not the watch is for an ADS request.
   */
  public boolean ads() {
    return ads;
  }

  /**
   * Cancel the watch. A watch must be cancelled in order to complete its resource stream and free resources. Cancel
   * may be called multiple times, with each subsequent call being a no-op.
   */
  public void cancel() {
    if (isCancelled.compareAndSet(false, true)) {
      if (stop != null) {
        stop.run();
      }
    }
  }

  /**
   * Returns boolean indicating whether or not the watch has been cancelled.
   */
  public boolean isCancelled() {
    return isCancelled.get();
  }

  /**
   * Returns the original request for the watch.
   */
  public DiscoveryRequest request() {
    return request;
  }

  /**
   * Sends the given response to the watch's response handler.
   *
   * @param response the response to be handled
   * @throws WatchCancelledException if the watch has already been cancelled
   */
  public void respond(Response response) throws WatchCancelledException {
    if (isCancelled()) {
      throw new WatchCancelledException();
    }

    responseConsumer.accept(response);
  }

  /**
   * Sets the callback method to be executed when the watch is cancelled. Even if cancel is executed multiple times, it
   * ensures that this stop callback is only executed once.
   */
  public void setStop(Runnable stop) {
    this.stop = stop;
  }
}
