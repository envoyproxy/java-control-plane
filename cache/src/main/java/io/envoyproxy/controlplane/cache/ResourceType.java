package io.envoyproxy.controlplane.cache;

public enum ResourceType {

  CLUSTER(Resources.TYPE_URL_PREFIX + "Cluster"),

  ENDPOINT(Resources.TYPE_URL_PREFIX + "ClusterLoadAssignment"),

  LISTENER(Resources.TYPE_URL_PREFIX + "Listener"),

  ROUTE(Resources.TYPE_URL_PREFIX + "RouteConfiguration");

  private final String typeUrl;

  ResourceType(String typeUrl) {
    this.typeUrl = typeUrl;
  }

  /**
   * Returns the full type URL for this resource type.
   */
  public String typeUrl() {
    return typeUrl;
  }
}
