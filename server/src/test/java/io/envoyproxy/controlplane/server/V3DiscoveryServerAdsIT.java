package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshot;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;

public class V3DiscoveryServerAdsIT {

  private static final String CONFIG = "envoy/ads.v3.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(1);

  private static final NettyGrpcServerRule ADS =
      new NettyGrpcServerRule() {
        @Override
        protected void configureServerBuilder(NettyServerBuilder builder) {
          final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

          final DiscoveryServerCallbacks callbacks =
              new V3OnlyDiscoveryServerCallbacks(
                  onStreamOpenLatch, onStreamRequestLatch, onStreamResponseLatch);

          cache.setSnapshot(
              GROUP,
              createSnapshot(
                  true,
                  false,
                  "upstream",
                  UPSTREAM.ipAddress(),
                  EchoContainer.PORT,
                  "listener0",
                  LISTENER_PORT,
                  "route0",
                  "1"));

          V3DiscoveryServer server = new V3DiscoveryServer(callbacks, cache);

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
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
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
            "http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

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
