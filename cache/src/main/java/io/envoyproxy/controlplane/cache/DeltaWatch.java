package io.envoyproxy.controlplane.cache;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code Watch} is a dedicated stream of configuration resources produced by the configuration cache and consumed by
 * the xDS server.
 */
public class DeltaWatch extends AbstractWatch<DeltaXdsRequest, DeltaResponse> {
  private final Map<String, String> resourceVersions;
  private final Set<String> pendingResources;
  private final boolean isWildcard;
  private final String version;

  /**
   * Construct a watch.
   *
   * @param request          the original request for the watch
   * @param version          indicates the stream current version
   * @param isWildcard       indicates if the stream is in wildcard mode
   * @param responseConsumer handler for outgoing response messages
   */
  public DeltaWatch(DeltaXdsRequest request,
                    Map<String, String> resourceVersions,
                    Set<String> pendingResources,
                    String version,
                    boolean isWildcard,
                    Consumer<DeltaResponse> responseConsumer) {
    super(request, responseConsumer);
    this.resourceVersions = resourceVersions;
    this.pendingResources = pendingResources;
    this.version = version;
    this.isWildcard = isWildcard;
  }

  /**
   * Returns the tracked resources for the watch.
   */
  public Map<String, String> trackedResources() {
    return resourceVersions;
  }

  /**
   * Returns the pending resources for the watch.
   */
  public Set<String> pendingResources() {
    return pendingResources;
  }

  /**
   * Returns the stream current version.
   */
  public String version() {
    return version;
  }

  /**
   * Indicates if the stream is in wildcard mode.
   */
  public boolean isWildcard() {
    return isWildcard;
  }

}
