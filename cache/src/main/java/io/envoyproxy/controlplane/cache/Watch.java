package io.envoyproxy.controlplane.cache;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import reactor.core.publisher.EmitterProcessor;

/**
 * {@code Watch} is a dedicated stream of configuration resources produced by the configuration cache and consumed by
 * the xDS server.
 */
public class Watch {

  private final AtomicBoolean isCancelled = new AtomicBoolean();
  private final Collection<String> names;
  private final ResourceType type;
  private final EmitterProcessor<Response> value = EmitterProcessor.create();

  private Runnable stop;

  public Watch(Collection<String> names, ResourceType type) {
    this.names = names;
    this.type = type;
  }

  /**
   * Cancel the watch. A watch must be cancelled in order to complete its resource stream and free resources. Cancel
   * may be called multiple times, with each subsequent call being a no-op.
   */
  public void cancel() {
    if (isCancelled.compareAndSet(false, true) && stop != null) {
      valueEmitter().onComplete();
      stop.run();
    }
  }

  /**
   * Returns the names of the requested resources, or empty for all resources. Resources not explicitly mentioned are
   * ignored.
   */
  public Collection<String> names() {
    return names;
  }

  /**
   * Returns the resource type that is being watched.
   */
  public ResourceType type() {
    return type;
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
