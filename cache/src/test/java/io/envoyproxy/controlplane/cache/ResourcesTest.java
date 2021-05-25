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
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.core.ApiVersion;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

  private static final VersionedResource<Cluster> CLUSTER = VersionedResource.create(
      TestResources.createCluster(CLUSTER_NAME), UUID.randomUUID().toString());
  private static final VersionedResource<ClusterLoadAssignment> ENDPOINT = VersionedResource.create(
      TestResources.createEndpoint(CLUSTER_NAME, ENDPOINT_PORT), UUID.randomUUID().toString());
  private static final VersionedResource<Listener> LISTENER = VersionedResource.create(
      TestResources.createListener(ADS, ApiVersion.V2,
          ApiVersion.V2,
          LISTENER_NAME, LISTENER_PORT,
          ROUTE_NAME), UUID.randomUUID().toString());
  private static final VersionedResource<RouteConfiguration> ROUTE = VersionedResource.create(
      TestResources.createRoute(ROUTE_NAME, CLUSTER_NAME), UUID.randomUUID().toString());
  private static final VersionedResource<Secret> SECRET = VersionedResource.create(
      TestResources.createSecret(SECRET_NAME), UUID.randomUUID().toString());

  private static final VersionedResource<io.envoyproxy.envoy.config.cluster.v3.Cluster> V3_CLUSTER =
      VersionedResource.create(TestResources.createClusterV3(V3_CLUSTER_NAME), UUID.randomUUID().toString());
  private static final VersionedResource<io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment> V3_ENDPOINT =
      VersionedResource.create(TestResources.createEndpointV3(V3_CLUSTER_NAME, ENDPOINT_PORT),
          UUID.randomUUID().toString());
  private static final VersionedResource<io.envoyproxy.envoy.config.listener.v3.Listener>
      V3_LISTENER = VersionedResource
      .create(TestResources.createListenerV3(ADS, false, V3, V3, V3_LISTENER_NAME,
          LISTENER_PORT, V3_ROUTE_NAME), UUID.randomUUID().toString());
  private static final VersionedResource<io.envoyproxy.envoy.config.route.v3.RouteConfiguration> V3_ROUTE =
      VersionedResource.create(TestResources.createRouteV3(V3_ROUTE_NAME, V3_CLUSTER_NAME),
          UUID.randomUUID().toString());
  private static final VersionedResource<io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret> V3_SECRET =
      VersionedResource.create(TestResources.createSecretV3(V3_SECRET_NAME), UUID.randomUUID().toString());

  @Test
  public void getResourceNameReturnsExpectedNameForValidResourceMessage() {
    ImmutableMap<VersionedResource<? extends Message>, String> cases = ImmutableMap.of(
        CLUSTER, CLUSTER_NAME,
        ENDPOINT, CLUSTER_NAME,
        LISTENER, LISTENER_NAME,
        ROUTE, ROUTE_NAME,
        SECRET, SECRET_NAME);

    cases.forEach((resource, expectedName) ->
        assertThat(Resources.getResourceName(resource.resource())).isEqualTo(expectedName));
  }

  @Test
  public void getResourceNameReturnsExpectedNameForValidResourceMessageV3() {
    ImmutableMap<VersionedResource<? extends Message>, String> cases = ImmutableMap.of(
        V3_CLUSTER, V3_CLUSTER_NAME,
        V3_ENDPOINT, V3_CLUSTER_NAME,
        V3_LISTENER, V3_LISTENER_NAME,
        V3_ROUTE, V3_ROUTE_NAME,
        V3_SECRET, V3_SECRET_NAME);

    cases.forEach((resource, expectedName) ->
        assertThat(Resources.getResourceName(resource.resource())).isEqualTo(expectedName));
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
    VersionedResource<Cluster> clusterWithServiceName = VersionedResource.create(Cluster.newBuilder()
            .setName(CLUSTER_NAME)
            .setEdsClusterConfig(
                EdsClusterConfig.newBuilder()
                    .setServiceName(clusterServiceName))
            .setType(DiscoveryType.EDS)
            .build(),
        UUID.randomUUID().toString());

    Map<Collection<VersionedResource<Message>>, Set<String>> cases =
        ImmutableMap.<Collection<VersionedResource<Message>>, Set<String>>builder()
            .put((Collection) ImmutableList.of(CLUSTER), ImmutableSet.of(CLUSTER_NAME))
            .put((Collection) ImmutableList.of(clusterWithServiceName), ImmutableSet.of(clusterServiceName))
            .put((Collection) ImmutableList.of(ENDPOINT), ImmutableSet.of())
            .put((Collection) ImmutableList.of(LISTENER), ImmutableSet.of(ROUTE_NAME))
            .put((Collection) ImmutableList.of(ROUTE), ImmutableSet.of())
            .put((Collection) ImmutableList.of(CLUSTER, ENDPOINT, LISTENER, ROUTE),
                ImmutableSet.of(CLUSTER_NAME, ROUTE_NAME))
            .build();

    cases.forEach((resources, refs) ->
        assertThat(Resources.getResourceReferences(resources)).containsExactlyElementsOf(refs));
  }

  @Test
  public void getResourceReferencesReturnsExpectedReferencesForValidV3ResourceMessages() {
    String clusterServiceName = "clusterWithServiceName0";
    VersionedResource<io.envoyproxy.envoy.config.cluster.v3.Cluster> clusterWithServiceName = VersionedResource
        .create(
            io.envoyproxy.envoy.config.cluster.v3.Cluster
                .newBuilder()
                .setName(V3_CLUSTER_NAME)
                .setEdsClusterConfig(
                    io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig.newBuilder()
                        .setServiceName(clusterServiceName))
                .setType(io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType.EDS)
                .build(),
            UUID.randomUUID().toString());

    Map<Collection<VersionedResource<Message>>, Set<String>> cases =
        ImmutableMap.<Collection<VersionedResource<Message>>, Set<String>>builder()
            .put((Collection) ImmutableList.of(V3_CLUSTER), ImmutableSet.of(V3_CLUSTER_NAME))
            .put((Collection) ImmutableList.of(clusterWithServiceName), ImmutableSet.of(clusterServiceName))
            .put((Collection) ImmutableList.of(V3_ENDPOINT), ImmutableSet.of())
            .put((Collection) ImmutableList.of(V3_LISTENER), ImmutableSet.of(V3_ROUTE_NAME))
            .put((Collection) ImmutableList.of(V3_ROUTE), ImmutableSet.of())
            .put((Collection) ImmutableList.of(V3_CLUSTER, V3_ENDPOINT, V3_LISTENER, V3_ROUTE),
                ImmutableSet.of(V3_CLUSTER_NAME, V3_ROUTE_NAME))
            .build();

    cases.forEach((resources, refs) ->
        assertThat(Resources.getResourceReferences(resources)).containsExactlyElementsOf(refs));
  }
}
