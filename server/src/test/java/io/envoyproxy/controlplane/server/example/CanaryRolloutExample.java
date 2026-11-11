package io.envoyproxy.controlplane.server.example;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Example: roll a config change out to a canary group of Envoys first, wait until every canary
 * Envoy has applied it (ACK) or rejected it (NACK), and only then promote it to the rest.
 *
 * <p>Envoys are grouped by {@code node.cluster}, which Envoy sets from {@code --service-cluster}.
 * Start one Envoy with {@code --service-cluster canary} and one with {@code --service-cluster
 * stable}, both with an ADS bootstrap pointing at port 12345.
 *
 * <p>A real control plane would also check the canary's health (synthetic requests, or its error
 * rate from metrics) after the ACK and before promoting. A NACK, a timeout or no connected canary
 * all mean: do not promote, and put the canary back on the last good snapshot.
 */
public class CanaryRolloutExample {

  private static final String CANARY = "canary";
  private static final String STABLE = "stable";

  /** Runs the example; the server stays up until the process is stopped. */
  public static void main(String[] args) throws IOException, InterruptedException {
    // One snapshot per group, chosen by the Envoy's node.cluster.
    SimpleCache<String> cache = new SimpleCache<>(node -> node.getCluster());
    AckTracker acks = new AckTracker();
    V3DiscoveryServer discoveryServer = new V3DiscoveryServer(acks, cache);

    Server server =
        NettyServerBuilder.forPort(12345)
            .addService(discoveryServer.getAggregatedDiscoveryServiceImpl())
            .build()
            .start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    System.out.println("xDS server listening on port " + server.getPort());

    Snapshot good = snapshot("cluster0", 1234, "1");
    cache.setSnapshot(CANARY, good);
    cache.setSnapshot(STABLE, good);

    // Give the Envoys time to connect, then roll out version 2.
    Thread.sleep(10_000);
    Snapshot candidate = snapshot("cluster1", 1235, "2");
    cache.setSnapshot(CANARY, candidate);

    AckTracker.Status status =
        acks.await(CANARY, Resources.V3.CLUSTER_TYPE_URL, "2", 10, TimeUnit.SECONDS);
    System.out.println("Canary answered version 2 with " + status);

    if (status == AckTracker.Status.ACKED) {
      // Here a real control plane would probe the canary before promoting.
      cache.setSnapshot(STABLE, candidate);
      System.out.println("Promoted version 2 to " + STABLE);
    } else {
      // A new version string, so the canary is sent the last good config again.
      cache.setSnapshot(CANARY, snapshot("cluster0", 1234, "3"));
      System.out.println("Rolled the canary back; " + STABLE + " never saw version 2");
    }

    server.awaitTermination();
  }

  private static Snapshot snapshot(String cluster, int port, String version) {
    return Snapshot.create(
        ImmutableList.of(
            TestResources.createCluster(cluster, "127.0.0.1", port, Cluster.DiscoveryType.STATIC)),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        version);
  }
}
