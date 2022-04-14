package io.envoyproxy.controlplane.server;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;

import java.io.IOException;

public class TestMain {

  private static final String GROUP = "key";

  /**
   * Example minimal xDS implementation using the java-control-plane lib. This example configures a
   * DiscoveryServer with a v3 cache, and handles only v3 requests from data planes.
   *
   * @param arg command-line args
   */
  public static void main(String[] arg) throws IOException, InterruptedException {
    SimpleCache<String> cache =
        new SimpleCache<>(node -> GROUP);

    cache.setSnapshot(
        GROUP,
        Snapshot.create(
            ImmutableList.of(TestResources.createClusterV3("cluster0", "127.0.0.1", 1234)),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            "1"));

    V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);

    ServerBuilder builder =
        NettyServerBuilder.forPort(12345)
            .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl());

    Server server = builder.build();

    server.start();

    System.out.println("Server has started on port " + server.getPort());

    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

    Thread.sleep(10000);

    cache.setSnapshot(
        GROUP,
        Snapshot.create(
            ImmutableList.of(TestResources.createClusterV3("cluster1", "127.0.0.1", 1235)),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            "1"));

    server.awaitTermination();
  }
}
