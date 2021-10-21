package io.envoyproxy.controlplane.cache;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;

public abstract class AbstractWatch<V, T> {

  private static final AtomicIntegerFieldUpdater<AbstractWatch> isCancelledUpdater =
      AtomicIntegerFieldUpdater.newUpdater(AbstractWatch.class, "isCancelled");
  private final V request;
  private final Consumer<T> responseConsumer;
  private volatile int isCancelled = 0;
  private Runnable stop;

  /**
   * Construct a watch.
   *
   * @param request          the original request for the watch
   * @param responseConsumer handler for outgoing response messages
   */
  public AbstractWatch(V request, Consumer<T> responseConsumer) {
    this.request = request;
    this.responseConsumer = responseConsumer;
  }

  /**
   * Cancel the watch. A watch must be cancelled in order to complete its resource stream and free resources. Cancel
   * may be called multiple times, with each subsequent call being a no-op.
   */
  public void cancel() {
    if (isCancelledUpdater.compareAndSet(this, 0, 1)) {
      if (stop != null) {
        stop.run();
      }
    }
  }

  /**
   * Returns boolean indicating whether or not the watch has been cancelled.
   */
  public boolean isCancelled() {
    return isCancelledUpdater.get(this) == 1;
  }

  /**
   * Returns the original request for the watch.
   */
  public V request() {
    return request;
  }

  /**
   * Sends the given response to the watch's response handler.
   *
   * @param response the response to be handled
   * @throws WatchCancelledException if the watch has already been cancelled
   */
  public void respond(T response) throws WatchCancelledException {
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
