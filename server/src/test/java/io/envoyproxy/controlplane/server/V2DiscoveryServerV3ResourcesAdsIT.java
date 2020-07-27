package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshot;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.V3SimpleCache;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;

/**
 * Tests a v2 DiscoveryServer with a v3 SimpleCache. This tests the migration path for end users
 * where the control plane migrates to v3 before the data planes migrate to the v3 ADS transport
 * protocol.
 */
public class V2DiscoveryServerV3ResourcesAdsIT {

  private static final String CONFIG = "envoy/ads.v2.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(1);

  private static final NettyGrpcServerRule ADS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      final V3SimpleCache<String> cache = new V3SimpleCache<>(new NodeGroup<String>() {
        @Override public String hashV2(Node node) {
          return GROUP;
        }

        @Override public String hashV3(io.envoyproxy.envoy.config.core.v3.Node node) {
          throw new IllegalStateException("Unexpected v3 request in v2 test");
        }
      });

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
        public void onV3StreamRequest(long streamId,
            io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest request) {
          throw new IllegalStateException("Unexpected v3 request in v2 test");
        }

        @Override
        public void onStreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
          onStreamResponseLatch.countDown();
        }

        @Override
        public void onV3StreamResponse(long streamId,
            io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest request,
            io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse response) {
          throw new IllegalStateException("Unexpected v3 response in v2 test");
        }
      };

      cache.setSnapshot(
          GROUP,
          createSnapshot(true,
              "upstream",
              UPSTREAM.ipAddress(),
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0",
              "1")
      );

      V2DiscoveryServer server = new V2DiscoveryServer(Collections.singletonList(callbacks), cache);

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
}
