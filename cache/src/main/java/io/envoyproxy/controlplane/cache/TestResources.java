package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.Struct;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.core.SocketAddress.Protocol;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.api.v2.listener.Filter;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.RouteAction;
import io.envoyproxy.envoy.api.v2.route.RouteMatch;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager.CodecType;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds;

/**
 * {@code TestResources} provides helper methods for generating resource messages for testing. It is not intended to be
 * used in production code.
 */
@VisibleForTesting
public class TestResources {

  private static final String ANY_ADDRESS = "0.0.0.0";
  private static final String LOCALHOST = "127.0.0.1";
  private static final String XDS_CLUSTER = "xds_cluster";

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
        .setLoadAssignment(ClusterLoadAssignment.newBuilder()
            .setClusterName(clusterName)
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .addLbEndpoints(LbEndpoint.newBuilder()
                    .setEndpoint(Endpoint.newBuilder()
                        .setAddress(Address.newBuilder()
                            .setSocketAddress(SocketAddress.newBuilder()
                                .setAddress(address)
                                .setPortValue(port)
                                .setProtocolValue(Protocol.TCP_VALUE)))))))
        .build();
  }

  /**
   * Returns a new test v3 cluster using EDS.
   *
   * @param clusterName name of the new cluster
   */
  public static io.envoyproxy.envoy.config.cluster.v3.Cluster createClusterV3(String clusterName) {
    io.envoyproxy.envoy.config.core.v3.ConfigSource edsSource =
        io.envoyproxy.envoy.config.core.v3.ConfigSource.newBuilder()
            .setAds(io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource.getDefaultInstance())
            .setResourceApiVersion(ApiVersion.V3)
            .build();

    return io.envoyproxy.envoy.config.cluster.v3.Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(5))
        .setEdsClusterConfig(io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig.newBuilder()
            .setEdsConfig(edsSource)
            .setServiceName(clusterName))
        .setType(io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType.EDS)
        .build();
  }

  /**
   * Returns a new test v3 cluster not using EDS.
   *
   * @param clusterName name of the new cluster
   * @param address address to use for the cluster endpoint
   * @param port port to use for the cluster endpoint
   */
  public static io.envoyproxy.envoy.config.cluster.v3.Cluster createClusterV3(
      String clusterName, String address, int port) {
    return io.envoyproxy.envoy.config.cluster.v3.Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(5))
        .setType(io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType.STRICT_DNS)
        .setLoadAssignment(io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.newBuilder()
            .setClusterName(clusterName)
            .addEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
                .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                    .setEndpoint(io.envoyproxy.envoy.config.endpoint.v3.Endpoint.newBuilder()
                        .setAddress(io.envoyproxy.envoy.config.core.v3.Address.newBuilder()
                            .setSocketAddress(io.envoyproxy.envoy.config.core.v3.SocketAddress.newBuilder()
                                .setAddress(address)
                                .setPortValue(port)
                                .setProtocolValue(Protocol.TCP_VALUE)))))
            )
        )
        .build();
  }

  /**
   * Returns a new test endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param port port to use for the endpoint
   */
  public static ClusterLoadAssignment createEndpoint(String clusterName, int port) {
    return createEndpoint(clusterName, LOCALHOST, port);
  }

  /**
   * Returns a new test endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param address ip address to use for the endpoint
   * @param port port to use for the endpoint
   */
  public static ClusterLoadAssignment createEndpoint(String clusterName, String address, int port) {
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(clusterName)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .addLbEndpoints(LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder()
                            .setAddress(address)
                            .setPortValue(port)
                            .setProtocol(Protocol.TCP))))))
        .build();
  }

  /**
   * Returns a new test v3 endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param port port to use for the endpoint
   */
  public static io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment createEndpointV3(
      String clusterName, int port) {
    return createEndpointV3(clusterName, LOCALHOST, port);
  }

  /**
   * Returns a new test v3 endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param address ip address to use for the endpoint
   * @param port port to use for the endpoint
   */
  public static io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment createEndpointV3(
      String clusterName, String address, int port) {
    return io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.newBuilder()
        .setClusterName(clusterName)
        .addEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(io.envoyproxy.envoy.config.endpoint.v3.Endpoint.newBuilder()
                    .setAddress(io.envoyproxy.envoy.config.core.v3.Address.newBuilder()
                        .setSocketAddress(io.envoyproxy.envoy.config.core.v3.SocketAddress.newBuilder()
                            .setAddress(address)
                            .setPortValue(port)
                            .setProtocol(io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol.TCP))))))
        .build();
  }

  /**
   * Returns a new test listener.
   *  @param ads should RDS for the listener be configured to use XDS?
   * @param rdsTransportVersion the transport_api_version that should be set for RDS
   * @param rdsResourceVersion the resource_api_version that should be set for RDS
   * @param listenerName name of the new listener
   * @param port port to use for the listener
   * @param routeName name of the test route that is associated with this listener
   */
  public static Listener createListener(boolean ads,
      io.envoyproxy.envoy.api.v2.core.ApiVersion rdsTransportVersion,
      io.envoyproxy.envoy.api.v2.core.ApiVersion rdsResourceVersion, String listenerName,
      int port, String routeName) {
    ConfigSource.Builder configSourceBuilder = ConfigSource.newBuilder()
        .setResourceApiVersion(rdsResourceVersion);

    ConfigSource rdsSource = ads
        ? configSourceBuilder
        .setAds(AggregatedConfigSource.getDefaultInstance())
        .setResourceApiVersion(rdsResourceVersion)
        .build()
        : configSourceBuilder
            .setApiConfigSource(ApiConfigSource.newBuilder()
                .setApiType(ApiType.GRPC)
                .setTransportApiVersion(rdsTransportVersion)
                .addGrpcServices(GrpcService.newBuilder()
                    .setEnvoyGrpc(EnvoyGrpc.newBuilder()
                        .setClusterName(XDS_CLUSTER))))
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
                .setTypedConfig(Any.pack(manager))))
        .build();
  }

  /**
   * Returns a new test v3 listener.
   * @param ads should RDS for the listener be configured to use XDS?
   * @param rdsTransportVersion the transport_api_version that should be set for RDS config on the
   *     listener
   * @param rdsResourceVersion the resource_api_version that should be set for RDS config on the
   *     listener
   * @param listenerName name of the new listener
   * @param port port to use for the listener
   * @param routeName name of the test route that is associated with this listener
   */
  public static io.envoyproxy.envoy.config.listener.v3.Listener createListenerV3(boolean ads,
      ApiVersion rdsTransportVersion,
      ApiVersion rdsResourceVersion, String listenerName,
      int port, String routeName) {
    io.envoyproxy.envoy.config.core.v3.ConfigSource.Builder configSourceBuilder =
        io.envoyproxy.envoy.config.core.v3.ConfigSource.newBuilder()
        .setResourceApiVersion(rdsResourceVersion);
    io.envoyproxy.envoy.config.core.v3.ConfigSource rdsSource = ads
        ? configSourceBuilder
        .setAds(io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource.getDefaultInstance())
        .setResourceApiVersion(rdsResourceVersion)
        .build()
        : configSourceBuilder
            .setApiConfigSource(io.envoyproxy.envoy.config.core.v3.ApiConfigSource.newBuilder()
                .setTransportApiVersion(rdsTransportVersion)
                .setApiType(io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
                    .setEnvoyGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc.newBuilder()
                        .setClusterName(XDS_CLUSTER))))
            .build();

    io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
        manager = io.envoyproxy.envoy.extensions.filters.network
        .http_connection_manager.v3.HttpConnectionManager.newBuilder()
        .setCodecType(
            io.envoyproxy.envoy.extensions.filters.network
                .http_connection_manager.v3.HttpConnectionManager.CodecType.AUTO)
        .setStatPrefix("http")
        .setRds(io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds.newBuilder()
            .setConfigSource(rdsSource)
            .setRouteConfigName(routeName))
        .addHttpFilters(
            io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.newBuilder()
                .setName(Resources.FILTER_ENVOY_ROUTER))
        .build();

    return io.envoyproxy.envoy.config.listener.v3.Listener.newBuilder()
        .setName(listenerName)
        .setAddress(io.envoyproxy.envoy.config.core.v3.Address.newBuilder()
            .setSocketAddress(io.envoyproxy.envoy.config.core.v3.SocketAddress.newBuilder()
                .setAddress(ANY_ADDRESS)
                .setPortValue(port)
                .setProtocol(io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol.TCP)))
        .addFilterChains(io.envoyproxy.envoy.config.listener.v3.FilterChain.newBuilder()
            .addFilters(io.envoyproxy.envoy.config.listener.v3.Filter.newBuilder()
                .setName(Resources.FILTER_HTTP_CONNECTION_MANAGER)
                .setTypedConfig(Any.pack(manager))))
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

  /**
   * Returns a new test v3 route.
   *
   * @param routeName name of the new route
   * @param clusterName name of the test cluster that is associated with this route
   */
  public static io.envoyproxy.envoy.config.route.v3.RouteConfiguration createRouteV3(
      String routeName, String clusterName) {
    return io.envoyproxy.envoy.config.route.v3.RouteConfiguration.newBuilder()
        .setName(routeName)
        .addVirtualHosts(io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
            .setName("all")
            .addDomains("*")
            .addRoutes(io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
                .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPrefix("/"))
                .setRoute(io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster(clusterName))))
        .build();
  }

  /**
   * Returns a new test secret.
   *
   * @param secretName name of the new secret
   */
  public static Secret createSecret(String secretName) {
    return Secret.newBuilder()
        .setName(secretName)
        .setTlsCertificate(TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder()
                .setInlineString("secret!")))
        .build();
  }

  /**
   * Returns a new test v3 secret.
   *
   * @param secretName name of the new secret
   */
  public static io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret createSecretV3(String secretName) {
    return io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret.newBuilder()
        .setName(secretName)
        .setTlsCertificate(io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate.newBuilder()
            .setPrivateKey(io.envoyproxy.envoy.config.core.v3.DataSource.newBuilder()
                .setInlineString("secret!")))
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
