package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager.CodecType;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;

/**
 * {@code TestResources} provides helper methods for generating resource messages for testing. It is
 * not intended to be used in production code.
 */
@VisibleForTesting
public class TestResources {

  private static final String ANY_ADDRESS = "0.0.0.0";
  private static final String LOCALHOST = "127.0.0.1";
  private static final String XDS_CLUSTER = "xds_cluster";

  /**
   * Returns a new test v3 cluster using EDS.
   *
   * @param clusterName name of the new cluster
   */
  public static Cluster createCluster(String clusterName) {
    ConfigSource edsSource =
        ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.getDefaultInstance())
            .setResourceApiVersion(ApiVersion.V3)
            .build();

    return Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(5))
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder()
                .setEdsConfig(edsSource)
                .setServiceName(clusterName))
        .setType(Cluster.DiscoveryType.EDS)
        .build();
  }

  /**
   * Returns a new test v3 cluster not using EDS.
   *
   * @param clusterName name of the new cluster
   * @param address address to use for the cluster endpoint
   * @param port port to use for the cluster endpoint
   * @param discoveryType service discovery type
   */
  public static Cluster createCluster(
      String clusterName, String address, int port, Cluster.DiscoveryType discoveryType) {
    return Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(5))
        .setType(discoveryType)
        .setLoadAssignment(
            ClusterLoadAssignment.newBuilder()
                .setClusterName(clusterName)
                .addEndpoints(
                    LocalityLbEndpoints.newBuilder()
                        .addLbEndpoints(
                            LbEndpoint.newBuilder()
                                .setEndpoint(
                                    Endpoint.newBuilder()
                                        .setAddress(
                                            Address.newBuilder()
                                                .setSocketAddress(
                                                    SocketAddress.newBuilder()
                                                        .setAddress(address)
                                                        .setPortValue(port)
                                                        .setProtocolValue(Protocol.TCP_VALUE)))))))
        .build();
  }

  /**
   * Returns a new test v3 endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param port port to use for the endpoint
   */
  public static ClusterLoadAssignment createEndpoint(String clusterName, int port) {
    return createEndpoint(clusterName, LOCALHOST, port);
  }

  /**
   * Returns a new test v3 endpoint for the given cluster.
   *
   * @param clusterName name of the test cluster that is associated with this endpoint
   * @param address ip address to use for the endpoint
   * @param port port to use for the endpoint
   */
  public static ClusterLoadAssignment createEndpoint(String clusterName, String address, int port) {
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(clusterName)
        .addEndpoints(
            io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
                .addLbEndpoints(
                    io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                        .setEndpoint(
                            io.envoyproxy.envoy.config.endpoint.v3.Endpoint.newBuilder()
                                .setAddress(
                                    io.envoyproxy.envoy.config.core.v3.Address.newBuilder()
                                        .setSocketAddress(
                                            io.envoyproxy.envoy.config.core.v3.SocketAddress
                                                .newBuilder()
                                                .setAddress(address)
                                                .setPortValue(port)
                                                .setProtocol(Protocol.TCP))))))
        .build();
  }

  /**
   * Returns a new test v3 listener.
   *
   * @param ads should RDS for the listener be configured to use XDS?
   * @param rdsTransportVersion the transport_api_version that should be set for RDS config on the
   *     listener
   * @param rdsResourceVersion the resource_api_version that should be set for RDS config on the
   *     listener
   * @param listenerName name of the new listener
   * @param port port to use for the listener
   * @param routeName name of the test route that is associated with this listener
   */
  public static Listener createListener(
      boolean ads,
      boolean delta,
      ApiVersion rdsTransportVersion,
      ApiVersion rdsResourceVersion,
      String listenerName,
      int port,
      String routeName) {
    ConfigSource.Builder configSourceBuilder =
        ConfigSource.newBuilder().setResourceApiVersion(rdsResourceVersion);
    ConfigSource rdsSource =
        ads
            ? configSourceBuilder
                .setAds(AggregatedConfigSource.getDefaultInstance())
                .setResourceApiVersion(rdsResourceVersion)
                .build()
            : configSourceBuilder
                .setApiConfigSource(
                    ApiConfigSource.newBuilder()
                        .setTransportApiVersion(rdsTransportVersion)
                        .setApiType(delta ? ApiConfigSource.ApiType.DELTA_GRPC : ApiConfigSource.ApiType.GRPC)
                        .addGrpcServices(
                            GrpcService.newBuilder()
                                .setEnvoyGrpc(
                                    GrpcService.EnvoyGrpc.newBuilder()
                                        .setClusterName(XDS_CLUSTER))))
                .build();

    HttpConnectionManager manager =
        HttpConnectionManager.newBuilder()
            .setCodecType(CodecType.AUTO)
            .setStatPrefix("http")
            .setRds(
                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds
                    .newBuilder()
                    .setConfigSource(rdsSource)
                    .setRouteConfigName(routeName))
            .addHttpFilters(
                io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
                    .newBuilder()
                    .setName(Resources.FILTER_ENVOY_ROUTER)
                    .setTypedConfig(Any.pack(Router.newBuilder().build())))
            .build();

    return Listener.newBuilder()
        .setName(listenerName)
        .setAddress(
            Address.newBuilder()
                .setSocketAddress(
                    SocketAddress.newBuilder()
                        .setAddress(ANY_ADDRESS)
                        .setPortValue(port)
                        .setProtocol(Protocol.TCP)))
        .addFilterChains(
            FilterChain.newBuilder()
                .addFilters(
                    Filter.newBuilder()
                        .setName(Resources.FILTER_HTTP_CONNECTION_MANAGER)
                        .setTypedConfig(Any.pack(manager))))
        .build();
  }

  /**
   * Returns a new test v3 route.
   *
   * @param routeName name of the new route
   * @param clusterName name of the test cluster that is associated with this route
   */
  public static RouteConfiguration createRoute(String routeName, String clusterName) {
    return RouteConfiguration.newBuilder()
        .setName(routeName)
        .addVirtualHosts(
            VirtualHost.newBuilder()
                .setName("all")
                .addDomains("*")
                .addRoutes(
                    Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                        .setRoute(RouteAction.newBuilder().setCluster(clusterName))))
        .build();
  }

  /**
   * Returns a new test v3 secret.
   *
   * @param secretName name of the new secret
   */
  public static Secret createSecret(String secretName) {
    return Secret.newBuilder()
        .setName(secretName)
        .setTlsCertificate(
            TlsCertificate.newBuilder()
                .setPrivateKey(DataSource.newBuilder().setInlineString("secret!")))
        .build();
  }

  private TestResources() {}
}
