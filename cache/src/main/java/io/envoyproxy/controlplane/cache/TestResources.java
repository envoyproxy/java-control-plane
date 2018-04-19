package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.JsonFormat;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Cds.Cluster.DiscoveryType;
import envoy.api.v2.Cds.Cluster.EdsClusterConfig;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import envoy.api.v2.core.AddressOuterClass.Address;
import envoy.api.v2.core.AddressOuterClass.SocketAddress;
import envoy.api.v2.core.AddressOuterClass.SocketAddress.Protocol;
import envoy.api.v2.core.ConfigSourceOuterClass.AggregatedConfigSource;
import envoy.api.v2.core.ConfigSourceOuterClass.ConfigSource;
import envoy.api.v2.endpoint.EndpointOuterClass.Endpoint;
import envoy.api.v2.endpoint.EndpointOuterClass.LbEndpoint;
import envoy.api.v2.endpoint.EndpointOuterClass.LocalityLbEndpoints;
import envoy.api.v2.listener.Listener.Filter;
import envoy.api.v2.listener.Listener.FilterChain;
import envoy.api.v2.route.RouteOuterClass.Route;
import envoy.api.v2.route.RouteOuterClass.RouteAction;
import envoy.api.v2.route.RouteOuterClass.RouteMatch;
import envoy.api.v2.route.RouteOuterClass.VirtualHost;
import envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManagerOuterClass.HttpConnectionManager;
import envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManagerOuterClass.HttpConnectionManager.CodecType;
import envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManagerOuterClass.HttpFilter;
import envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManagerOuterClass.Rds;

/**
 * {@code TestResources} provides helper methods for generating resource messages for testing. It is not intended to be
 * used in production code.
 */
@VisibleForTesting
public class TestResources {

  private static final String ANY_ADDRESS = "0.0.0.0";
  private static final String LOCALHOST = "127.0.0.1";

  /**
   * Returns a new test cluster using EDS.
   *
   * @param clusterName name of the new cluster
   */
  public static Cluster createCluster(String clusterName) {
    ConfigSource edsSource = ConfigSource.newBuilder()
        .setAds(AggregatedConfigSource.getDefaultInstance())
        .build();

    return Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(5))
        .setEdsClusterConfig(EdsClusterConfig.newBuilder()
            .setEdsConfig(edsSource)
            .setServiceName(clusterName))
        .setType(DiscoveryType.EDS)
        .build();
  }

  /**
   * Returns a new test cluster not using EDS.
   *
   * @param clusterName name of the new cluster
   * @param address address to use for the cluster endpoint
   * @param port port to use for the cluster endpoint
   */
  public static Cluster createCluster(String clusterName, String address, int port) {
    return Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(5))
        .setType(DiscoveryType.STRICT_DNS)
        .addHosts(Address.newBuilder()
            .setSocketAddress(SocketAddress.newBuilder()
                .setAddress(address)
                .setPortValue(port)
                .setProtocolValue(Protocol.TCP_VALUE)))
        .build();
  }

  /**
   * Returns a new test endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param port port to use for the endpoint
   */
  public static ClusterLoadAssignment createEndpoint(String clusterName, int port) {
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(clusterName)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .addLbEndpoints(LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder()
                            .setAddress(LOCALHOST)
                            .setPortValue(port)
                            .setProtocol(Protocol.TCP))))))
        .build();
  }

  /**
   * Returns a new test listener.
   *
   * @param listenerName name of the new listener
   * @param port port to use for the listener
   * @param routeName name of the test route that is associated with this listener
   */
  public static Listener createListener(String listenerName, int port, String routeName) {
    ConfigSource rdsSource = ConfigSource.newBuilder()
        .setAds(AggregatedConfigSource.getDefaultInstance())
        .build();

    HttpConnectionManager manager = HttpConnectionManager.newBuilder()
        .setCodecType(CodecType.AUTO)
        .setStatPrefix("http")
        .setRds(Rds.newBuilder()
            .setConfigSource(rdsSource)
            .setRouteConfigName(routeName))
        .addHttpFilters(HttpFilter.newBuilder()
            .setName(Resources.FILTER_ENVOY_ROUTER))
        .build();

    return Listener.newBuilder()
        .setName(listenerName)
        .setAddress(Address.newBuilder()
            .setSocketAddress(SocketAddress.newBuilder()
                .setAddress(ANY_ADDRESS)
                .setPortValue(port)
                .setProtocol(Protocol.TCP)))
        .addFilterChains(FilterChain.newBuilder()
            .addFilters(Filter.newBuilder()
                .setName(Resources.FILTER_HTTP_CONNECTION_MANAGER)
                .setConfig(messageAsStruct(manager))))
        .build();
  }

  /**
   * Returns a new test route.
   *
   * @param routeName name of the new route
   * @param clusterName name of the test cluster that is associated with this route
   */
  public static RouteConfiguration createRoute(String routeName, String clusterName) {
    return RouteConfiguration.newBuilder()
        .setName(routeName)
        .addVirtualHosts(VirtualHost.newBuilder()
            .setName("all")
            .addDomains("*")
            .addRoutes(Route.newBuilder()
                .setMatch(RouteMatch.newBuilder()
                    .setPrefix("/"))
                .setRoute(RouteAction.newBuilder()
                    .setCluster(clusterName))))
        .build();
  }

  private static Struct messageAsStruct(MessageOrBuilder message) {
    try {
      String json = JsonFormat.printer()
          .preservingProtoFieldNames()
          .print(message);

      Struct.Builder structBuilder = Struct.newBuilder();

      JsonFormat.parser().merge(json, structBuilder);

      return structBuilder.build();
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException("Failed to convert protobuf message to struct", e);
    }
  }

  private TestResources() { }
}
