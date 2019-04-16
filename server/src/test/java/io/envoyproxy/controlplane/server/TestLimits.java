package io.envoyproxy.controlplane.server;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.RateLimiter;
import com.google.protobuf.Duration;
import io.envoyproxy.controlplane.cache.SimpleCache;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.server.limits.GuavaRequestLimiter;
import io.envoyproxy.controlplane.server.limits.ManualFlowControl;
import io.envoyproxy.controlplane.server.limits.StreamLimiter;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import java.io.IOException;

public class TestLimits {

  private static final String GROUP = "key";

  private static final long MAX_OPEN_STREAMS = 10;
  private static final long MAX_RPS = 1;

  private static final Snapshot CLUSTER_0_SNAPSHOT = Snapshot.create(
      ImmutableList.of(
          Cluster.newBuilder()
              .setName("cluster0")
              .setConnectTimeout(Duration.newBuilder().setSeconds(5))
              .setType(DiscoveryType.STATIC)
              .setLoadAssignment(loadAssignment("127.0.0.1", 1234))
              .build()),
      ImmutableList.of(),
      ImmutableList.of(),
      ImmutableList.of(),
      ImmutableList.of(),
      "1");

  private static final Snapshot CLUSTER_1_SNAPSHOT = Snapshot.create(
      ImmutableList.of(
          Cluster.newBuilder()
              .setName("cluster1")
              .setConnectTimeout(Duration.newBuilder().setSeconds(5))
              .setType(DiscoveryType.STATIC)
              .setLoadAssignment(loadAssignment("127.0.0.1", 1235))
              .build()),
      ImmutableList.of(),
      ImmutableList.of(),
      ImmutableList.of(),
      ImmutableList.of(),
      "1");

  /**
   * Example minimal xDS implementation using the java-control-plane lib.
   * Additionally, this example includes the below configuration:
   * <ul>
   *   <li>Total RPS limit,</li>
   *   <li>Concurrently opened streams limit,</li>
   *   <li>HTTP2 based flow control back-pressure.</li>
   * </ul>
   *
   * @param arg command-line args
   */
  public static void main(String[] arg) throws IOException, InterruptedException {
    SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    cache.setSnapshot(GROUP, CLUSTER_0_SNAPSHOT);

    DiscoveryServer discoveryServer = DiscoveryServer.watching(cache)
        .withFlowControlFactory(ManualFlowControl.factory())
        .withRequestLimiter(new GuavaRequestLimiter(RateLimiter.create(MAX_RPS)))
        .build();

    ServerBuilder builder = NettyServerBuilder.forPort(12345)
        .addService(discoveryServer.getAggregatedDiscoveryServiceImpl())
        .addService(discoveryServer.getClusterDiscoveryServiceImpl())
        .addService(discoveryServer.getEndpointDiscoveryServiceImpl())
        .addService(discoveryServer.getListenerDiscoveryServiceImpl())
        .addService(discoveryServer.getRouteDiscoveryServiceImpl());

    builder.intercept(new StreamLimiter(MAX_OPEN_STREAMS).getServerInterceptor());

    Server server = builder.build();
    server.start();

    System.out.println("Server has started on port " + server.getPort());

    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

    Thread.sleep(10000);

    cache.setSnapshot(GROUP, CLUSTER_1_SNAPSHOT);

    server.awaitTermination();
  }

  private static ClusterLoadAssignment loadAssignment(String ip, int port) {
    return ClusterLoadAssignment.newBuilder().addEndpoints(
        LocalityLbEndpoints.newBuilder().addLbEndpoints(LbEndpoint.newBuilder().setEndpoint(
            Endpoint.newBuilder().setAddress(
                Address.newBuilder()
                    .setSocketAddress(
                        SocketAddress.newBuilder().setAddress(ip).setPortValue(port))
            ).build()).build()).build()).build();
  }
}
