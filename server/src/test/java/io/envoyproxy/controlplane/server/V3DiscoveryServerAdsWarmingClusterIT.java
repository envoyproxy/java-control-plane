package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshot;
import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V3;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import com.google.protobuf.util.Durations;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Http2ProtocolOptions;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.RandomUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

public class V3DiscoveryServerAdsWarmingClusterIT {

  private static final String CONFIG = "envoy/ads.v3.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;
  private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(1);

  private static final NettyGrpcServerRule ADS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      final DiscoveryServerCallbacks callbacks = new DiscoveryServerCallbacks() {
        @Override
        public void onStreamOpen(long streamId, String typeUrl) {
          onStreamOpenLatch.countDown();
        }

        @Override
        public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
          onStreamRequestLatch.countDown();
        }

        @Override
        public void onV3StreamDeltaRequest(long streamId,
                                           DeltaDiscoveryRequest request) {
          throw new IllegalStateException("Unexpected delta request");
        }

        @Override
        public void onV3StreamResponse(long streamId, DiscoveryRequest request,
            DiscoveryResponse response) {
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

      V3DiscoveryServer server = new V3DiscoveryServer(callbacks, cache);

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
            .then().statusCode(502));

    // Here we update a Snapshot with working cluster, but we change only CDS version, not EDS version.
    // This change allows to test if EDS will be sent anyway after CDS was sent.
    createSnapshotWithWorkingClusterWithTheSameEdsVersion();

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));
  }

  private static void createSnapshotWithWorkingClusterWithTheSameEdsVersion() {
    cache.setSnapshot(
        GROUP,
        createSnapshot(true,
            false,
            "upstream",
            UPSTREAM.ipAddress(),
            EchoContainer.PORT,
            "listener0",
            LISTENER_PORT,
            "route0",
            "2"));
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
        .setResourceApiVersion(V3)
        .build();

    Cluster cluster = Cluster.newBuilder()
        .setName(clusterName)
        .setConnectTimeout(Durations.fromSeconds(RandomUtils.nextInt(1, 5)))
        // we are enabling HTTP2 - communication with cluster won't work
        .putAllTypedExtensionProtocolOptions(Collections.singletonMap(
            "envoy.extensions.upstreams.http.v3.HttpProtocolOptions",
            com.google.protobuf.Any.pack(
                HttpProtocolOptions.newBuilder()
                    .setExplicitHttpConfig(
                        HttpProtocolOptions.ExplicitHttpConfig.newBuilder()
                            .setHttp2ProtocolOptions(Http2ProtocolOptions.getDefaultInstance())
                            .build())
                    .build())))
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
            .setEdsConfig(edsSource)
            .setServiceName(clusterName))
        .setType(Cluster.DiscoveryType.EDS)
        .build();
    ClusterLoadAssignment
        endpoint = TestResources.createEndpoint(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, false, V3, V3, listenerName,
        listenerPort, routeName);
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

  /*
   * In the previous versions of this tests we had a copied SimpleCache with respondWithSpecificOrder removed.
   * With new versions of Envoy hitting this edge-case became highly improbable.
   * Now this test checks only if a CDS change will also send EDS.
   * 1. Envoy connects to control-plane
   * 2. Snapshot already exists in control-plane <- other instance share same group
   * 3. Control-plane respond with CDS in createWatch method
   * 4. There is snapshot update which change CDS and EDS versions
   * 5. Envoy sends EDS request
   * 6. Control-plane respond with EDS in createWatch method
   * 7. Envoy resume CDS and EDS requests.
   * 8. Envoy sends request CDS
   * 9. Control plane respond with CDS in createWatch method
   * 10. Envoy sends EDS requests
   * 11. Control plane doesn't respond because version hasn't changed
   * 12. Cluster of service stays in warming phase
   */
}
