package io.envoyproxy.controlplane.cache;

import java.util.function.Consumer;

/**
 * {@code Watch} is a dedicated stream of configuration resources produced by the configuration cache and consumed by
 * the xDS server.
 */
public class Watch extends AbstractWatch<XdsRequest, Response> {
  private final boolean ads;

  /**
   * Construct a watch.
   *
   * @param ads              is this watch for an ADS request?
   * @param request          the original request for the watch
   * @param responseConsumer handler for outgoing response messages
   */
  public Watch(boolean ads, XdsRequest request, Consumer<Response> responseConsumer) {
    super(request, responseConsumer);
    this.ads = ads;
  }

  /**
   * Returns boolean indicating whether or not the watch is for an ADS request.
   */
  public boolean ads() {
    return ads;
  }

}
