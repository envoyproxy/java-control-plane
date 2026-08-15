package io.envoyproxy.controlplane.server;

import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V3;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.ScopedRouteConfiguration;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

/**
 * This test verifies when allowDefaultEmptyEdsUpdate is true, we whether envoy control
 * will send EDS response when some clusters are missing in ClusterLoadAssignments in
 * the snapshot.
 * When allowDefaultEmptyEdsUpdate is false, EDS response will not be sent from envoy-control
 */
public class V3DiscoveryServerAdsAllowDefaultEmptyEdsIT {

  private static final String CONFIG = "envoy/ads.v3.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(1);

  static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
  private static final NettyGrpcServerRule ADS =
      new NettyGrpcServerRule() {
        @Override
        protected void configureServerBuilder(NettyServerBuilder builder) {

          final DiscoveryServerCallbacks callbacks =
              new V3OnlyDiscoveryServerCallbacks(
                  onStreamOpenLatch, onStreamRequestLatch, onStreamResponseLatch);

          Cluster upstream = TestResources.createCluster("upstream");
          Cluster no_endpoints = TestResources.createCluster("no_endpoints");
          ClusterLoadAssignment
              endpoint = TestResources.createEndpoint("upstream", UPSTREAM.ipAddress(), EchoContainer.PORT);
          Listener listener = TestResources.createListener(true, false, V3, V3, "listener0",
              LISTENER_PORT, "route0");
          RouteConfiguration route = TestResources.createRoute("route0", "upstream");
          ScopedRouteConfiguration scopedRoute = TestResources.createScopedRoute("scoped_route0",
              "route0");

          // Construct a snapshot with no_endpoints clusters which does not have EDS data
          Snapshot snapshot = Snapshot.create(
              ImmutableList.of(upstream, no_endpoints),
              ImmutableList.of(endpoint),
              ImmutableList.of(listener),
              ImmutableList.of(route),
              ImmutableList.of(scopedRoute),
              ImmutableList.of(),
              "1");

          cache.setSnapshot(GROUP, snapshot);

          V3DiscoveryServer server = new V3DiscoveryServer(Collections.singletonList(callbacks),
              cache, new DefaultExecutorGroup(), new DefaultProtoResourcesSerializer(),
              new StartupConfigs() {
                @Override public boolean allowDefaultEmptyEdsUpdate() {
                  return true;
                }
              });

          builder.addService(server.getAggregatedDiscoveryServiceImpl());
        }
      };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> ADS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM =
      new EchoContainer().withNetwork(NETWORK).withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM).around(ADS).around(ENVOY);

  @Test
  public void allowEmptyEdsUpdate() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS))
        .isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS))
        .isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    assertThat(onStreamResponseLatch.await(15, TimeUnit.SECONDS))
        .isTrue()
        .overridingErrorMessage("failed to send ADS response");

    String baseUri =
        String.format(
            "http://%s:%d", ENVOY.getHost(), ENVOY.getMappedPort(LISTENER_PORT));

    // Envoy will get updates although there are missing clusters in EDS
    await()
        .atMost(5, TimeUnit.SECONDS)
        .ignoreExceptions()
        .untilAsserted(
            () ->
                given()
                    .baseUri(baseUri)
                    .contentType(ContentType.TEXT)
                    .when()
                    .get("/")
                    .then()
                    .statusCode(200)
                    .and()
                    .body(containsString(UPSTREAM.response)));
  }
}
