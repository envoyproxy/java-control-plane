package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.TestSnapshots.createSnapshot;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import com.google.protobuf.util.Durations;
import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.SimpleCache;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.Http2ProtocolOptions;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;
import org.testcontainers.shaded.org.apache.commons.lang.math.RandomUtils;

public class DiscoveryServerAdsWarmingClusterIT {

  private static final String CONFIG = "envoy/ads.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;
  private static final CustomCache<String> cache = new CustomCache<>(node -> GROUP);

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(1);

  private static final NettyGrpcServerRule ADS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      final DiscoveryServerCallbacks callbacks = new DiscoveryServerCallbacks() {
        @Override
        public void onStreamOpen(long streamId, String typeUrl) {
          onStreamOpenLatch.countDown();
        }

        @Override
        public void onStreamRequest(long streamId, DiscoveryRequest request) {
          onStreamRequestLatch.countDown();
        }

        @Override
        public void onStreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
          // Here we update a Snapshot with working cluster, but we change only CDS version, not EDS version.
          // This change allows to test if EDS will be sent anyway after CDS was sent.
          createSnapshotWithWorkingClusterWithTheSameEdsVersion(request, executorService);
          onStreamResponseLatch.countDown();
        }
      };

      cache.setSnapshot(
          GROUP,
          createSnapshotWithNotWorkingCluster(true,
              "upstream",
              UPSTREAM.ipAddress(),
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0"));

      DiscoveryServer server = new DiscoveryServer(callbacks, cache);

      builder.addService(server.getAggregatedDiscoveryServiceImpl());
    }
  };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> ADS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM)
      .around(ADS)
      .around(ENVOY);

  @Test
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    assertThat(onStreamResponseLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to send ADS response");

    String baseUri = String.format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));
  }

  private static void createSnapshotWithWorkingClusterWithTheSameEdsVersion(DiscoveryRequest request,
                                                                            ExecutorService executorService) {
    if (request.getTypeUrl().equals(Resources.CLUSTER_TYPE_URL)) {
      executorService.submit(() -> cache.setSnapshot(
          GROUP,
          createSnapshot(true,
              "upstream",
              UPSTREAM.ipAddress(),
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0",
              "2"))
      );
    }
  }

  private static Snapshot createSnapshotWithNotWorkingCluster(boolean ads,
                                                              String clusterName,
                                                              String endpointAddress,
                                                              int endpointPort,
                                                              String listenerName,
                                                              int listenerPort,
                                                              String routeName) {

    ConfigSource edsSource = ConfigSource.newBuilder()
        .setAds(AggregatedConfigSource.getDefaultInstance())
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(RandomUtils.nextInt(5)))
        // we are enabling HTTP2 - communication with cluster won't work
        .setHttp2ProtocolOptions(Http2ProtocolOptions.newBuilder().build())
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
            .setEdsConfig(edsSource)
            .setServiceName(clusterName))
        .setType(Cluster.DiscoveryType.EDS)
        .build();
    ClusterLoadAssignment endpoint = TestResources.createEndpoint(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRoute(routeName, clusterName);

    // here we have new version of resources other than CDS.
    return Snapshot.create(
        ImmutableList.of(cluster),
        "1",
        ImmutableList.of(endpoint),
        "2",
        ImmutableList.of(listener),
        "2",
        ImmutableList.of(route),
        "2",
        ImmutableList.of(),
        "2");
  }


  /**
   * Code has been copied from io.envoyproxy.controlplane.cache.SimpleCache to show specific case when
   * Envoy might stuck with warming cluster. Class has changed lines from method respondWithSpecificOrder which are
   * responsible for responding for watches. Because to reproduce this problem we need a lot of connected Envoy's and
   * changes to snapshot it is easier to reproduce this way.
   */
  static class CustomCache<T> extends SimpleCache<T> {

    public CustomCache(NodeGroup<T> groups) {
      super(groups);
    }

    @Override
    protected void respondWithSpecificOrder(T group, Snapshot snapshot,
                                            ConcurrentMap<String, CacheStatusInfo<T>> status) {
      // This code has been removed to show specific case which is hard to reproduce in integration test:
      //      1. Envoy connects to control-plane
      //      2. Snapshot already exists in control-plane <- other instance share same group
      //      3. Control-plane respond with CDS in createWatch method
      //      4. There is snapshot update which change CDS and EDS versions
      //      5. Envoy sends EDS request
      //      6. Control-plane respond with EDS in createWatch method
      //      7. Envoy resume CDS and EDS requests.
      //      8. Envoy sends request CDS
      //      9. Control plane respond with CDS in createWatch method
      //      10. Envoy sends EDS requests
      //      11. Control plane doesn't respond because version hasn't changed
      //      12. Cluster of service stays in warming phase
    }
  }
}
