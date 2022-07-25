package io.envoyproxy.controlplane.cache;

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.envoyproxy.controlplane.cache.Resources.ApiVersion.V3;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.CLUSTER;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.ENDPOINT;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.LISTENER;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.ROUTE;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.SECRET;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager.RouteSpecifierCase;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Resources {

  /**
   * Version-agnostic representation of a resource. This is useful when the version qualifier isn't
   * needed.
   */
  public enum ResourceType {
    CLUSTER,
    ENDPOINT,
    LISTENER,
    ROUTE,
    SECRET
  }

  public enum ApiVersion {
    V3
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(Resources.class);

  static final String FILTER_ENVOY_ROUTER = "envoy.filters.http.router";
  static final String FILTER_HTTP_CONNECTION_MANAGER = "envoy.http_connection_manager";

  public static class V3 {

    public static final String CLUSTER_TYPE_URL =
        "type.googleapis.com/envoy.config.cluster.v3" + ".Cluster";
    public static final String ENDPOINT_TYPE_URL =
        "type.googleapis.com/envoy.config.endpoint.v3" + ".ClusterLoadAssignment";
    public static final String LISTENER_TYPE_URL =
        "type.googleapis.com/envoy.config.listener.v3" + ".Listener";
    public static final String ROUTE_TYPE_URL =
        "type.googleapis.com/envoy.config.route.v3" + ".RouteConfiguration";
    public static final String SECRET_TYPE_URL =
        "type.googleapis.com/envoy.extensions" + ".transport_sockets.tls.v3.Secret";

    public static final List<String> TYPE_URLS =
        ImmutableList.of(
            CLUSTER_TYPE_URL,
            ENDPOINT_TYPE_URL,
            LISTENER_TYPE_URL,
            ROUTE_TYPE_URL,
            SECRET_TYPE_URL);
  }

  public static final List<ResourceType> RESOURCE_TYPES_IN_ORDER =
      ImmutableList.of(CLUSTER, ENDPOINT, LISTENER, ROUTE, SECRET);

  public static final Map<String, ResourceType> TYPE_URLS_TO_RESOURCE_TYPE =
      new ImmutableMap.Builder<String, ResourceType>()
          .put(Resources.V3.CLUSTER_TYPE_URL, CLUSTER)
          .put(Resources.V3.ENDPOINT_TYPE_URL, ENDPOINT)
          .put(Resources.V3.LISTENER_TYPE_URL, LISTENER)
          .put(Resources.V3.ROUTE_TYPE_URL, ROUTE)
          .put(Resources.V3.SECRET_TYPE_URL, SECRET)
          .build();

  public static final Map<String, Class<? extends Message>> RESOURCE_TYPE_BY_URL =
      new ImmutableMap.Builder<String, Class<? extends Message>>()
          .put(Resources.V3.CLUSTER_TYPE_URL, Cluster.class)
          .put(Resources.V3.ENDPOINT_TYPE_URL, ClusterLoadAssignment.class)
          .put(Resources.V3.LISTENER_TYPE_URL, Listener.class)
          .put(Resources.V3.ROUTE_TYPE_URL, RouteConfiguration.class)
          .put(Resources.V3.SECRET_TYPE_URL, Secret.class)
          .build();

  /**
   * Returns the name of the given resource message.
   *
   * @param resource the resource message
   */
  public static String getResourceName(Message resource) {
    if (resource instanceof Cluster) {
      return ((Cluster) resource).getName();
    }

    if (resource instanceof ClusterLoadAssignment) {
      return ((ClusterLoadAssignment) resource).getClusterName();
    }

    if (resource instanceof Listener) {
      return ((Listener) resource).getName();
    }

    if (resource instanceof RouteConfiguration) {
      return ((RouteConfiguration) resource).getName();
    }

    if (resource instanceof Secret) {
      return ((Secret) resource).getName();
    }

    return "";
  }

  /**
   * Returns the name of the given resource message.
   *
   * @param anyResource the resource message
   * @throws RuntimeException if the passed Any doesn't correspond to an xDS resource
   */
  public static String getResourceName(Any anyResource) {
    Class<? extends Message> clazz = RESOURCE_TYPE_BY_URL.get(anyResource.getTypeUrl());
    Preconditions.checkNotNull(clazz, "cannot unpack non-xDS message type");

    try {
      return getResourceName(anyResource.unpack(clazz));
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns all resource names that are referenced by the given collection of resources.
   *
   * @param resources the resource whose dependencies we are calculating
   */
  public static <T extends Message> Set<String> getResourceReferences(Collection<VersionedResource<T>> resources) {
    final ImmutableSet.Builder<String> refs = ImmutableSet.builder();

    for (VersionedResource<T> sr : resources) {
      Message r = sr.resource();
      if (r instanceof ClusterLoadAssignment || r instanceof RouteConfiguration) {
        // Endpoints have no dependencies.

        // References to clusters in routes (and listeners) are not included in the result, because
        // the clusters are
        // currently retrieved in bulk, and not by name.

        continue;
      }
      if (r instanceof Cluster) {
        Cluster c = (Cluster) r;

        // For EDS clusters, use the cluster name or the service name override.
        if (c.getType() == Cluster.DiscoveryType.EDS) {
          if (!isNullOrEmpty(c.getEdsClusterConfig().getServiceName())) {
            refs.add(c.getEdsClusterConfig().getServiceName());
          } else {
            refs.add(c.getName());
          }
        }
      } else if (r instanceof Listener) {

        Listener l = (Listener) r;

        // Extract the route configuration names from the HTTP connection manager.
        for (FilterChain chain : l.getFilterChainsList()) {
          for (Filter filter : chain.getFiltersList()) {
            if (!filter.getName().equals(FILTER_HTTP_CONNECTION_MANAGER)) {
              continue;
            }

            try {
              HttpConnectionManager config =
                  filter
                      .getTypedConfig()
                      .unpack(
                          io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
                              .HttpConnectionManager.class);

              if (config.getRouteSpecifierCase() == RouteSpecifierCase.RDS
                  && !isNullOrEmpty(config.getRds().getRouteConfigName())) {
                refs.add(config.getRds().getRouteConfigName());
              }
            } catch (InvalidProtocolBufferException e) {
              LOGGER.error(
                  "Failed to convert v3 HTTP connection manager config struct into protobuf "
                      + "message for listener {}",
                  getResourceName(l),
                  e);
            }
          }
        }
      }
    }

    return refs.build();
  }

  /** Returns the supported API version for a given type URL. */
  public static ApiVersion getResourceApiVersion(String typeUrl) {
    if (Resources.V3.TYPE_URLS.contains(typeUrl)) {
      return V3;
    }

    throw new RuntimeException(String.format("Unsupported API version for type URL %s", typeUrl));
  }

  private Resources() {}
}
