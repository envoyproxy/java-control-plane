package io.envoyproxy.controlplane.cache;

import static com.google.common.base.Strings.isNullOrEmpty;
import static envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManagerOuterClass.HttpConnectionManager.RouteSpecifierCase.RDS;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Cds.Cluster.DiscoveryType;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import envoy.api.v2.listener.Listener.Filter;
import envoy.api.v2.listener.Listener.FilterChain;
import envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManagerOuterClass.HttpConnectionManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Resources {

  private static final Logger LOGGER = LoggerFactory.getLogger(Resources.class);

  static final String FILTER_ENVOY_ROUTER = "envoy.router";
  static final String FILTER_HTTP_CONNECTION_MANAGER = "envoy.http_connection_manager";

  private static final String TYPE_URL_PREFIX = "type.googleapis.com/envoy.api.v2.";

  public static final String CLUSTER_TYPE_URL = TYPE_URL_PREFIX + "Cluster";
  public static final String ENDPOINT_TYPE_URL = TYPE_URL_PREFIX + "ClusterLoadAssignment";
  public static final String LISTENER_TYPE_URL = TYPE_URL_PREFIX + "Listener";
  public static final String ROUTE_TYPE_URL = TYPE_URL_PREFIX + "RouteConfiguration";

  public static final List<String> TYPE_URLS = ImmutableList.of(
      CLUSTER_TYPE_URL,
      ENDPOINT_TYPE_URL,
      LISTENER_TYPE_URL,
      ROUTE_TYPE_URL);

  public static final Map<String, Class<? extends Message>> RESOURCE_TYPE_BY_URL = ImmutableMap.of(
      CLUSTER_TYPE_URL, Cluster.class,
      ENDPOINT_TYPE_URL, ClusterLoadAssignment.class,
      LISTENER_TYPE_URL, Listener.class,
      ROUTE_TYPE_URL, RouteConfiguration.class
      );

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
  public static Set<String> getResourceReferences(Collection<? extends Message> resources) {
    final ImmutableSet.Builder<String> refs = ImmutableSet.builder();

    for (Message r : resources) {
      if (r instanceof ClusterLoadAssignment || r instanceof RouteConfiguration) {
        // Endpoints have no dependencies.

        // References to clusters in routes (and listeners) are not included in the result, because the clusters are
        // currently retrieved in bulk, and not by name.

        continue;
      }

      if (r instanceof Cluster) {
        Cluster c = (Cluster) r;

        // For EDS clusters, use the cluster name or the service name override.
        if (c.getType() == DiscoveryType.EDS) {
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
              HttpConnectionManager.Builder config = HttpConnectionManager.newBuilder();

              structAsMessage(filter.getConfig(), config);

              if (config.getRouteSpecifierCase() == RDS && !isNullOrEmpty(config.getRds().getRouteConfigName())) {
                refs.add(config.getRds().getRouteConfigName());
              }
            } catch (InvalidProtocolBufferException e) {
              LOGGER.error(
                  "Failed to convert HTTP connection manager config struct into protobuf message for listener {}",
                  getResourceName(l),
                  e);
            }
          }
        }
      }
    }

    return refs.build();
  }

  private static void structAsMessage(Struct struct, Message.Builder messageBuilder)
      throws InvalidProtocolBufferException {

    String json = JsonFormat.printer()
        .preservingProtoFieldNames()
        .print(struct);

    JsonFormat.parser().merge(json, messageBuilder);
  }

  private Resources() { }
}
