package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.SimpleCache;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshot;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

public class V2V3DiscoveryServerAdsIT {

  private static final String CONFIG_V2 = "envoy/ads.v2.config.yaml";
  private static final String CONFIG_V3 = "envoy/ads.v3.config.yaml";
  private static final String GROUP_V2 = "key_v2";
  private static final String GROUP_V3 = "key_v3";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onV2StreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onV2StreamResponseLatch = new CountDownLatch(1);
  private static final CountDownLatch onV3StreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onV3StreamResponseLatch = new CountDownLatch(1);

  private static final NettyGrpcServerRule ADS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      final SimpleCache<String, Snapshot> cache = new SimpleCache<>(
          new NodeGroup<String>() {
            @Override public String hash(Node node) {
              return GROUP_V2;
            }

            @Override public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
              return GROUP_V3;
            }
          }
      );

      final DiscoveryServerCallbacks callbacks =
          new V2AndV3DiscoveryServerCallbacks(
                  onStreamOpenLatch,
                  onV2StreamRequestLatch,
                  onV2StreamResponseLatch,
                  onV3StreamRequestLatch,
                  onV3StreamResponseLatch
          );

      cache.setSnapshot(
              GROUP_V2,
              createSnapshot(true,
                      "upstream",
                      UPSTREAM.ipAddress(),
                      EchoContainer.PORT,
                      "listener0",
                      LISTENER_PORT,
                      "route0",
                      "1")
      );

      cache.setSnapshot(
              GROUP_V2,
              V2TestSnapshots.createSnapshot(true,
                      "upstream",
                      UPSTREAM.ipAddress(),
                      EchoContainer.PORT,
                      "listener0",
                      LISTENER_PORT,
                      "route0",
                      "1")
      );

      cache.setSnapshot(
              GROUP_V3,
          createSnapshot(true,
              "upstream",
              UPSTREAM.ipAddress(),
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0",
              "1")
      );

      V2DiscoveryServer serverV2 = new V2DiscoveryServer(callbacks, cache);
      V3DiscoveryServer serverV3 = new V3DiscoveryServer(callbacks, cache);

      builder.addService(serverV2.getAggregatedDiscoveryServiceImpl());
      builder.addService(serverV3.getAggregatedDiscoveryServiceImpl());
    }
  };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY_V3 = new EnvoyContainer(CONFIG_V3, () -> ADS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EnvoyContainer ENVOY_V2 = new EnvoyContainer(CONFIG_V2, () -> ADS.getServer().getPort())
          .withExposedPorts(LISTENER_PORT)
          .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM)
      .around(ADS)
      .around(ENVOY_V2)
      .around(ENVOY_V3);

  @Test
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open ADS stream");

    assertThat(onV2StreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
            .overridingErrorMessage("failed to receive ADS request");

    assertThat(onV2StreamResponseLatch.await(15, TimeUnit.SECONDS)).isTrue()
            .overridingErrorMessage("failed to send ADS response");

    assertThat(onV3StreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive ADS request");

    assertThat(onV3StreamResponseLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to send ADS response");

    String baseV2Uri = String.format("http://%s:%d", ENVOY_V2.getContainerIpAddress(), ENVOY_V2.getMappedPort(LISTENER_PORT));
    String baseV3Uri = String.format("http://%s:%d", ENVOY_V3.getContainerIpAddress(), ENVOY_V3.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseV2Uri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
            () -> given().baseUri(baseV3Uri).contentType(ContentType.TEXT)
                    .when().get("/")
                    .then().statusCode(200)
                    .and().body(containsString(UPSTREAM.response)));
  }
}
