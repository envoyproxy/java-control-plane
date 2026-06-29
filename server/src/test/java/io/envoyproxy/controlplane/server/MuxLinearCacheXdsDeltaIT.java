package io.envoyproxy.controlplane.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import io.envoyproxy.controlplane.cache.Cache;
import io.envoyproxy.controlplane.cache.LinearCache;
import io.envoyproxy.controlplane.cache.MuxCache;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.Resources.V3;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.netty.NettyServerBuilder;
import io.restassured.http.ContentType;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

/**
 * Real-Envoy integration test for the fork's new caches. The control plane is backed by a {@link MuxCache}
 * that routes <b>RDS to a {@link LinearCache}</b> (the high-churn, O(1) side) and CDS/LDS/EDS/SDS to a
 * {@link SimpleCache} — exactly the layout proposed in the fork design doc. A real Envoy (v1.37.2) connects
 * over delta xDS, and we assert:
 *
 * <ol>
 *   <li>Envoy boots, fetches the muxed config, and proxies an HTTP request to the upstream echo — proving
 *       {@code MuxCache + LinearCache} can serve a real Envoy end-to-end.</li>
 *   <li>A runtime {@code linearCache.updateResource("route0", ...)} (no snapshot rebuild) is applied by Envoy
 *       on the live stream without reconnecting: Envoy ACKs a new RDS nonce, raises no NACK, and the new
 *       route takes effect (a freshly added response header appears) — the end-to-end proof of O(1) dynamic
 *       updates / certificate-rotation-style hot replacement.</li>
 * </ol>
 */
public class MuxLinearCacheXdsDeltaIT {

  private static final Logger LOGGER = LoggerFactory.getLogger(MuxLinearCacheXdsDeltaIT.class);

  private static final String CONFIG = "envoy/xds.v3.delta.config.yaml";
  private static final String GROUP = "key";
  private static final Integer LISTENER_PORT = 10000;
  private static final String CLUSTER_NAME = "upstream";
  private static final String ROUTE_NAME = "route0";
  private static final String LISTENER_NAME = "listener0";

  private static final CountDownLatch onStreamOpenLatch = new CountDownLatch(1);
  private static final CountDownLatch onStreamRequestLatch = new CountDownLatch(1);

  private static final ConcurrentHashMap<String, StringBuffer> resourceToNonceMap = new ConcurrentHashMap<>();
  private static final StringBuffer nonce = new StringBuffer();
  private static final StringBuffer errorDetails = new StringBuffer();

  // CDS/LDS/EDS/SDS on SimpleCache (static side), RDS on LinearCache (high-churn side).
  private static final SimpleCache<String> simpleCache = new SimpleCache<>(node -> GROUP);
  private static final LinearCache<String> routeCache = new LinearCache<>(ResourceType.ROUTE, GROUP);

  private static final EchoContainer UPSTREAM = new EchoContainer()
      .withNetwork(Network.SHARED)
      .withNetworkAliases("upstream");

  private static final NettyGrpcServerRule XDS = new NettyGrpcServerRule() {
    @Override
    protected void configureServerBuilder(NettyServerBuilder builder) {
      final DiscoveryServerCallbacks callbacks =
          new V3DeltaDiscoveryServerCallbacks(onStreamOpenLatch, onStreamRequestLatch, nonce,
              errorDetails, resourceToNonceMap);

      // Full snapshot drives CDS/LDS (and carries a copy of the route to keep SimpleCache self-consistent),
      // but Envoy fetches RDS from the LinearCache via the mux, so route changes go through updateResource().
      Snapshot snapshot = V3TestSnapshots.createSnapshotNoEds(false, true, CLUSTER_NAME,
          UPSTREAM.ipAddress(), EchoContainer.PORT, LISTENER_NAME, LISTENER_PORT, ROUTE_NAME, "1");
      simpleCache.setSnapshot(GROUP, snapshot);
      routeCache.updateResource(ROUTE_NAME, TestResources.createRoute(ROUTE_NAME, CLUSTER_NAME));

      Map<ResourceType, Cache<String>> caches = new EnumMap<>(ResourceType.class);
      caches.put(ResourceType.CLUSTER, simpleCache);
      caches.put(ResourceType.LISTENER, simpleCache);
      caches.put(ResourceType.ENDPOINT, simpleCache);
      caches.put(ResourceType.SECRET, simpleCache);
      caches.put(ResourceType.ROUTE, routeCache);
      MuxCache<String> mux = new MuxCache<>(caches);

      V3DiscoveryServer server = new V3DiscoveryServer(callbacks, mux);
      builder.addService(server.getRouteDiscoveryServiceImpl());
      builder.addService(server.getListenerDiscoveryServiceImpl());
      builder.addService(server.getEndpointDiscoveryServiceImpl());
      builder.addService(server.getClusterDiscoveryServiceImpl());
      builder.addService(server.getSecretDiscoveryServiceImpl());
    }
  };

  private static final EnvoyContainer ENVOY = new EnvoyContainer(CONFIG, () -> XDS.getServer().getPort())
      .withExposedPorts(LISTENER_PORT)
      .withNetwork(Network.SHARED);

  @ClassRule
  public static final RuleChain RULES = RuleChain.outerRule(UPSTREAM).around(XDS).around(ENVOY);

  @Test
  public void muxLinearCacheServesEnvoyAndAppliesRuntimeRouteUpdate() throws InterruptedException {
    assertThat(onStreamOpenLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to open xDS stream");
    assertThat(onStreamRequestLatch.await(15, TimeUnit.SECONDS)).isTrue()
        .overridingErrorMessage("failed to receive xDS request");

    final String baseUri = String.format("http://%s:%d",
        ENVOY.getContainerIpAddress(), ENVOY.getMappedPort(LISTENER_PORT));

    // 1) MuxCache + LinearCache served a working config: Envoy proxies to the echo upstream.
    await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(
        () -> given().baseUri(baseUri).contentType(ContentType.TEXT)
            .when().get("/")
            .then().statusCode(200)
            .and().body(containsString(UPSTREAM.response)));

    // The route was served by the LinearCache at its initial version (nonce "0"), with no NACK.
    assertThat(errorDetails.toString()).isEqualTo("");
    assertThat(resourceToNonceMap.get(V3.ROUTE_TYPE_URL).toString()).isEqualTo("0");
    assertThat(resourceToNonceMap.get(V3.CLUSTER_TYPE_URL).toString()).isEqualTo("0");
    assertThat(resourceToNonceMap.get(V3.LISTENER_TYPE_URL).toString()).isEqualTo("0");

    // 2) Runtime O(1) update on the LinearCache: same route name, new content (adds a response header).
    //    No snapshot rebuild, no reconnect — Envoy must apply it on the live stream.
    RouteConfiguration updated = withResponseHeader(
        TestResources.createRoute(ROUTE_NAME, CLUSTER_NAME), "x-xds4j", "linear-cache");
    routeCache.updateResource(ROUTE_NAME, updated);

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      // Envoy ACK'd a new RDS version pushed by the LinearCache, and did not NACK.
      assertThat(resourceToNonceMap.get(V3.ROUTE_TYPE_URL).toString()).isEqualTo("01");
      assertThat(errorDetails.toString()).isEqualTo("");
      // The new route is in effect: the added header is present and traffic still flows.
      given().baseUri(baseUri).contentType(ContentType.TEXT)
          .when().get("/")
          .then().statusCode(200)
          .and().header("x-xds4j", "linear-cache")
          .and().body(containsString(UPSTREAM.response));
    });
    LOGGER.info("LinearCache runtime route update applied by Envoy without reconnect");
  }

  private static RouteConfiguration withResponseHeader(RouteConfiguration route, String key, String value) {
    return route.toBuilder()
        .setVirtualHosts(0, route.getVirtualHosts(0).toBuilder()
            .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                .setHeader(HeaderValue.newBuilder().setKey(key).setValue(value).build())
                .build())
            .build())
        .build();
  }

  @AfterClass
  public static void after() throws Exception {
    ENVOY.close();
    UPSTREAM.close();
  }
}
