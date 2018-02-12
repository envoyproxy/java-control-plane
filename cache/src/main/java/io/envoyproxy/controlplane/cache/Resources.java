package io.envoyproxy.controlplane.cache;

import com.google.protobuf.Message;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;

public class Resources {

  static final String TYPE_URL_PREFIX = "type.googleapis.com/envoy.api.v2.";

  /**
   * Returns the name of the given resource message.
   *
   * @param xds the xDS resource message
   */
  public static String getResourceName(Message xds) {
    if (xds instanceof Cluster) {
      return ((Cluster) xds).getName();
    }

    if (xds instanceof ClusterLoadAssignment) {
      return ((ClusterLoadAssignment) xds).getClusterName();
    }

    if (xds instanceof Listener) {
      return ((Listener) xds).getName();
    }

    if (xds instanceof RouteConfiguration) {
      return ((RouteConfiguration) xds).getName();
    }

    return "";
  }

  private Resources() { }
}
