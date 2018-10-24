package io.envoyproxy.controlplane.server;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Cds.Cluster.DiscoveryType;
import envoy.api.v2.core.AddressOuterClass.Address;
import envoy.api.v2.core.AddressOuterClass.SocketAddress;
import io.envoyproxy.controlplane.cache.SimpleCache;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import java.io.IOException;

public class TestMain {

  private static final String GROUP = "key";

  /**
   * Example minimal xDS implementation using the java-control-plane lib.
   *
   * @param arg command-line args
   */
  public static void main(String[] arg) throws IOException, InterruptedException {
    SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    cache.setSnapshot(
        GROUP,
        Snapshot.create(
            ImmutableList.of(
                Cluster.newBuilder()
                    .setName("cluster0")
                    .setConnectTimeout(Duration.newBuilder().setSeconds(5))
                    .setType(DiscoveryType.STATIC)
                    .addHosts(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder().setAddress("127.0.0.1").setPortValue(1234)))
                    .build()),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            "1"));

    DiscoveryServer discoveryServer = new DiscoveryServer(cache);

    ServerBuilder builder = NettyServerBuilder.forPort(12345)
        .addService(discoveryServer.getAggregatedDiscoveryServiceImpl())
        .addService(discoveryServer.getClusterDiscoveryServiceImpl())
        .addService(discoveryServer.getEndpointDiscoveryServiceImpl())
        .addService(discoveryServer.getListenerDiscoveryServiceImpl())
        .addService(discoveryServer.getRouteDiscoveryServiceImpl());

    Server server = builder.build();

    server.start();

    System.out.println("Server has started on port " + server.getPort());

    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

    Thread.sleep(10000);

    cache.setSnapshot(
        GROUP,
        Snapshot.create(
            ImmutableList.of(
                Cluster.newBuilder()
                    .setName("cluster1")
                    .setConnectTimeout(Duration.newBuilder().setSeconds(5))
                    .setType(DiscoveryType.STATIC)
                    .addHosts(Address.newBuilder()
                        .setSocketAddress(SocketAddress.newBuilder().setAddress("127.0.0.1").setPortValue(1235)))
                    .build()),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            "1"));

    server.awaitTermination();
  }
}
