package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshotNoEds;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.V3SimpleCache;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;

public class V3DiscoveryServerXdsIT {

  private static final String CONFIG = "envoy/xds.v3.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(2);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(2);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(2);

  private static final NettyGrpcServerRule XDS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      final V3SimpleCache<String> cache = new V3SimpleCache<>(new NodeGroup<String>() {
        @Override public String hash(Node node) {
          throw new IllegalStateException("Unexpected v2 request in a v3 test");
        }

        @Override public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
          return GROUP;
        }
      });

      final DiscoveryServerCallbacks callbacks = new DiscoveryServerCallbacks() {
        @Override
        public void onStreamOpen(long streamId, String typeUrl) {
          onStreamOpenLatch.countDown();
        }

        @Override
        public void onStreamRequest(long streamId, io.envoyproxy.envoy.api.v2.DiscoveryRequest request) {
          throw new IllegalStateException("Unexpected v2 request in v3 test");
        }

        @Override
        public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
          onStreamRequestLatch.countDown();
        }

        @Override
        public void onStreamResponse(long streamId, io.envoyproxy.envoy.api.v2.DiscoveryRequest request,
            io.envoyproxy.envoy.api.v2.DiscoveryResponse response) {
          throw new IllegalStateException("Unexpected v2 response in v3 test");
        }

        @Override
        public void onV3StreamResponse(long streamId, DiscoveryRequest request,
            DiscoveryResponse response) {
          onStreamResponseLatch.countDown();
        }
      };

      cache.setSnapshot(
          GROUP,
          createSnapshotNoEds(false,
              "upstream",
              "upstream",
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0",
              "1")
      );

      V3DiscoveryServer server = new V3DiscoveryServer(callbacks, cache);

      builder.addService(server.getClusterDiscoveryServiceImpl());
      builder.addService(server.getEndpointDiscoveryServiceImpl());
      builder.addService(server.getListenerDiscoveryServiceImpl());
      builder.addService(server.getRouteDiscoveryServiceImpl());
    }
  };

  private static final Network NETWORK = Network.newNetwork();

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> XDS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(NETWORK);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(NETWORK)
      .withNetworkAliases("upstream");

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM)
      .around(XDS)
      .around(ENVOY);

  @Test
  public void validateTestRequestToEchoServerViaEnvoy() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open XDS streams");

    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive XDS requests");

    assertThat(onStreamResponseLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to send XDS responses");

    String baseUri = String.format("http://%s:%d", ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    await().atMost(5, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));
  }
}
