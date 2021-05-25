package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.V3TestSnapshots.createSnapshotNoEdsV2Transport;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.testcontainers.containers.Network;

/**
 * Tests a V2 discovery server with a V3 cache. This provides a migration path for end users
 * where they can migrate the control plane to generating v3 resources before making data planes
 * consume the v2 xDS APIs.
 */
public class V2DiscoveryServerV3ResourcesXdsIT {

  private static final String CONFIG = "envoy/xds.v2.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(2);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(2);
  private static final CountDownLatch onStreamResponseLatch = new CountDownLatch(2);

  private static final NettyGrpcServerRule XDS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      final SimpleCache<String> cache = new SimpleCache<>(new NodeGroup<String>() {
        @Override public String hash(Node node) {
          return GROUP;
        }

        @Override public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
          throw new IllegalStateException("Unexpected v3 request in v2 test");
        }
      });

      final DiscoveryServerCallbacks callbacks =
          new V2OnlyDiscoveryServerCallbacks(onStreamOpenLatch, onStreamRequestLatch,
              onStreamResponseLatch);

      // Make sure to configure v2 transport on the RDS config source on the listener, we don't
      // expose v3 in this test.
      cache.setSnapshot(
          GROUP,
          createSnapshotNoEdsV2Transport(false,
              false,
              "upstream",
              "upstream",
              EchoContainer.PORT,
              "listener0",
              LISTENER_PORT,
              "route0",
              "1")
      );

      V2DiscoveryServer server = new V2DiscoveryServer(callbacks, cache);

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
