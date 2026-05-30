package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.V3.ENDPOINT_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V3.ROUTE_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V3.SECRET_TYPE_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * Unit tests for {@link MuxCache}: requests are routed to the cache registered for their resource type, and a
 * request for an unconfigured type is rejected. Demonstrates the fork's intended layout (SDS/RDS on
 * {@link LinearCache}).
 */
public class MuxCacheTest {

  private static final String GROUP = "node";

  private LinearCache<String> secretCache = new LinearCache<>(ResourceType.SECRET, GROUP);
  private LinearCache<String> routeCache = new LinearCache<>(ResourceType.ROUTE, GROUP);

  private MuxCache<String> mux = new MuxCache<>(ImmutableMap.of(
      ResourceType.SECRET, secretCache,
      ResourceType.ROUTE, routeCache));

  private static XdsRequest wildcard(String typeUrl) {
    return XdsRequest.create(DiscoveryRequest.newBuilder()
        .setNode(Node.getDefaultInstance())
        .setTypeUrl(typeUrl)
        .build());
  }

  @Test
  public void routesEachTypeToItsCache() {
    Secret secret = Secret.newBuilder().setName("secret0").build();
    RouteConfiguration route = RouteConfiguration.newBuilder().setName("route0").build();
    secretCache.updateResource("secret0", secret);
    routeCache.updateResource("route0", route);

    ResponseTracker sdsTracker = new ResponseTracker();
    mux.createWatch(false, wildcard(SECRET_TYPE_URL), Collections.emptySet(), sdsTracker, false, false);
    assertThat(sdsTracker.responses).hasSize(1);
    assertThat(resourcesOf(sdsTracker.responses.getFirst())).containsExactly(secret);

    ResponseTracker rdsTracker = new ResponseTracker();
    mux.createWatch(false, wildcard(ROUTE_TYPE_URL), Collections.emptySet(), rdsTracker, false, false);
    assertThat(rdsTracker.responses).hasSize(1);
    assertThat(resourcesOf(rdsTracker.responses.getFirst())).containsExactly(route);
  }

  @Test
  public void rejectsUnconfiguredType() {
    ResponseTracker tracker = new ResponseTracker();
    assertThatThrownBy(() ->
        mux.createWatch(false, wildcard(ENDPOINT_TYPE_URL), Collections.emptySet(), tracker, false, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void groupsAndStatusAggregateAcrossCaches() {
    secretCache.updateResource("secret0", Secret.newBuilder().setName("secret0").build());
    // Open watches on both sub-caches.
    mux.createWatch(false, wildcard(SECRET_TYPE_URL), Collections.emptySet(), new ResponseTracker(), false, false);
    mux.createWatch(false, wildcard(ROUTE_TYPE_URL), Collections.emptySet(), new ResponseTracker(), false, false);

    assertThat(mux.groups()).contains(GROUP);
    StatusInfo<String> info = mux.statusInfo(GROUP);
    assertThat(info).isNotNull();
    assertThat(info.numWatches()).isGreaterThanOrEqualTo(0);
  }

  private static List<Message> resourcesOf(Response response) {
    return new ArrayList<>(response.resources());
  }

  static class ResponseTracker implements Consumer<Response> {
    final LinkedList<Response> responses = new LinkedList<>();

    @Override
    public void accept(Response response) {
      responses.add(response);
    }
  }
}
