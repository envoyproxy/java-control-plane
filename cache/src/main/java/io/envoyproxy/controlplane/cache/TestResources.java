package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
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
   * @param ads should RDS for the listener be configured to use XDS?
   * @param listenerName name of the new listener
   * @param port port to use for the listener
   * @param routeName name of the test route that is associated with this listener
   */
  public static Listener createListener(boolean ads, String listenerName, int port, String routeName) {
    ConfigSource rdsSource = ads
        ? ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.getDefaultInstance())
            .build()
        : ConfigSource.newBuilder()
            .setApiConfigSource(ApiConfigSource.newBuilder()
                .setApiType(ApiType.GRPC)
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
