package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshotNoEds;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
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
      final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

      final DiscoveryServerCallbacks callbacks =
          new V3OnlyDiscoveryServerCallbacks(onStreamOpenLatch, onStreamRequestLatch,
              onStreamResponseLatch);

      cache.setSnapshot(
          GROUP,
          createSnapshotNoEds(false,
              false,
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

  @After
  public void after() throws Exception {
    ENVOY.stop();
    UPSTREAM.stop();
    NETWORK.close();
  }
}
