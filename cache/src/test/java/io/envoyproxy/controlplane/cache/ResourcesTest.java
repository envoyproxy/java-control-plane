package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.type.Color;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Test;

public class ResourcesTest {

  private static final boolean ADS = ThreadLocalRandom.current().nextBoolean();
  private static final String CLUSTER_NAME = "v2cluster";
  private static final String LISTENER_NAME = "v2listener";
  private static final String ROUTE_NAME = "v2route";
  private static final String SECRET_NAME = "v2secret";
  private static final String V3_CLUSTER_NAME = "v3cluster";
  private static final String V3_LISTENER_NAME = "v3listener";
  private static final String V3_ROUTE_NAME = "v3route";
  private static final String V3_SECRET_NAME = "v3secret";

  private static final int ENDPOINT_PORT = ThreadLocalRandom.current().nextInt(10000, 20000);
  private static final int LISTENER_PORT = ThreadLocalRandom.current().nextInt(20000, 30000);

  private static final Cluster CLUSTER = TestResources.createClusterV3(CLUSTER_NAME);
  private static final ClusterLoadAssignment ENDPOINT =
      TestResources.createEndpointV3(CLUSTER_NAME, ENDPOINT_PORT);
  private static final Listener LISTENER =
      TestResources.createListenerV3(ADS, V3, V3, LISTENER_NAME, LISTENER_PORT, ROUTE_NAME);
  private static final RouteConfiguration ROUTE =
      TestResources.createRouteV3(ROUTE_NAME, CLUSTER_NAME);
  private static final Secret SECRET = TestResources.createSecretV3(SECRET_NAME);

  private static final io.envoyproxy.envoy.config.cluster.v3.Cluster V3_CLUSTER =
      TestResources.createClusterV3(V3_CLUSTER_NAME);
  private static final io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment V3_ENDPOINT =
      TestResources.createEndpointV3(V3_CLUSTER_NAME, ENDPOINT_PORT);
  private static final io.envoyproxy.envoy.config.listener.v3.Listener V3_LISTENER =
      TestResources.createListenerV3(ADS, V3, V3, V3_LISTENER_NAME, LISTENER_PORT, V3_ROUTE_NAME);
  private static final io.envoyproxy.envoy.config.route.v3.RouteConfiguration V3_ROUTE =
      TestResources.createRouteV3(V3_ROUTE_NAME, V3_CLUSTER_NAME);
  private static final io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret V3_SECRET =
      TestResources.createSecretV3(V3_SECRET_NAME);

  @Test
  public void getResourceNameReturnsExpectedNameForValidResourceMessage() {
    Map<Message, String> cases =
        ImmutableMap.of(
            CLUSTER, CLUSTER_NAME,
            ENDPOINT, CLUSTER_NAME,
            LISTENER, LISTENER_NAME,
            ROUTE, ROUTE_NAME,
            SECRET, SECRET_NAME);

    cases.forEach(
        (resource, expectedName) ->
            assertThat(Resources.getResourceName(resource)).isEqualTo(expectedName));
  }

  @Test
  public void getResourceNameReturnsExpectedNameForValidResourceMessageV3() {
    Map<Message, String> cases =
        ImmutableMap.of(
            V3_CLUSTER, V3_CLUSTER_NAME,
            V3_ENDPOINT, V3_CLUSTER_NAME,
            V3_LISTENER, V3_LISTENER_NAME,
            V3_ROUTE, V3_ROUTE_NAME,
            V3_SECRET, V3_SECRET_NAME);

    cases.forEach(
        (resource, expectedName) ->
            assertThat(Resources.getResourceName(resource)).isEqualTo(expectedName));
  }

  @Test
  public void getResourceNameReturnsEmptyStringForNonResourceMessage() {
    Message message = Color.newBuilder().build();

    assertThat(Resources.getResourceName(message)).isEmpty();
  }

  @Test
  public void getResourceNameAnyThrowsOnBadClass() {
    assertThatThrownBy(() -> Resources.getResourceName(Any.newBuilder().setTypeUrl("garbage").build()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("cannot unpack");
  }

  @Test
  public void getResourceReferencesReturnsExpectedReferencesForValidResourceMessages() {
    String clusterServiceName = "clusterWithServiceName0";
    Cluster clusterWithServiceName =
        Cluster.newBuilder()
            .setName(CLUSTER_NAME)
            .setEdsClusterConfig(
                Cluster.EdsClusterConfig.newBuilder().setServiceName(clusterServiceName))
            .setType(Cluster.DiscoveryType.EDS)
            .build();

    Map<Collection<Message>, Set<String>> cases =
        ImmutableMap.<Collection<Message>, Set<String>>builder()
            .put(ImmutableList.of(CLUSTER), ImmutableSet.of(CLUSTER_NAME))
            .put(ImmutableList.of(clusterWithServiceName), ImmutableSet.of(clusterServiceName))
            .put(ImmutableList.of(ENDPOINT), ImmutableSet.of())
            .put(ImmutableList.of(LISTENER), ImmutableSet.of(ROUTE_NAME))
            .put(ImmutableList.of(ROUTE), ImmutableSet.of())
            .put(
                ImmutableList.of(CLUSTER, ENDPOINT, LISTENER, ROUTE),
                ImmutableSet.of(CLUSTER_NAME, ROUTE_NAME))
            .build();

    cases.forEach(
        (resources, refs) ->
            assertThat(Resources.getResourceReferences(resources)).containsExactlyElementsOf(refs));
  }

  @Test
  public void getResourceReferencesReturnsExpectedReferencesForValidV3ResourceMessages() {
    String clusterServiceName = "clusterWithServiceName0";
    io.envoyproxy.envoy.config.cluster.v3.Cluster clusterWithServiceName =
        io.envoyproxy.envoy.config.cluster.v3.Cluster.newBuilder()
            .setName(V3_CLUSTER_NAME)
            .setEdsClusterConfig(
                io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig.newBuilder()
                    .setServiceName(clusterServiceName))
            .setType(io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType.EDS)
            .build();

    Map<Collection<Message>, Set<String>> cases =
        ImmutableMap.<Collection<Message>, Set<String>>builder()
            .put(ImmutableList.of(V3_CLUSTER), ImmutableSet.of(V3_CLUSTER_NAME))
            .put(ImmutableList.of(clusterWithServiceName), ImmutableSet.of(clusterServiceName))
            .put(ImmutableList.of(V3_ENDPOINT), ImmutableSet.of())
            .put(ImmutableList.of(V3_LISTENER), ImmutableSet.of(V3_ROUTE_NAME))
            .put(ImmutableList.of(V3_ROUTE), ImmutableSet.of())
            .put(
                ImmutableList.of(V3_CLUSTER, V3_ENDPOINT, V3_LISTENER, V3_ROUTE),
                ImmutableSet.of(V3_CLUSTER_NAME, V3_ROUTE_NAME))
            .build();

    cases.forEach(
        (resources, refs) ->
            assertThat(Resources.getResourceReferences(resources)).containsExactlyElementsOf(refs));
  }
}
