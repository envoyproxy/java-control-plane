package io.envoyproxy.controlplane.cache.v2;

import static io.envoyproxy.controlplane.cache.Resources.V2.CLUSTER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V2.ENDPOINT_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V2.LISTENER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V2.ROUTE_TYPE_URL;
import static io.envoyproxy.envoy.api.v2.core.ApiVersion.V2;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.SnapshotConsistencyException;
import io.envoyproxy.controlplane.cache.SnapshotResource;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;

public class SnapshotTest {

  private static final boolean ADS = ThreadLocalRandom.current().nextBoolean();
  private static final String CLUSTER_NAME = "cluster0";
  private static final String LISTENER_NAME = "listener0";
  private static final String ROUTE_NAME = "route0";
  private static final String SECRET_NAME = "secret0";

  private static final int ENDPOINT_PORT = ThreadLocalRandom.current().nextInt(10000, 20000);
  private static final int LISTENER_PORT = ThreadLocalRandom.current().nextInt(20000, 30000);

  private static final SnapshotResource<Cluster> CLUSTER =
      SnapshotResource.create(TestResources.createCluster(CLUSTER_NAME), UUID.randomUUID().toString());
  private static final SnapshotResource<ClusterLoadAssignment> ENDPOINT =
      SnapshotResource.create(TestResources.createEndpoint(CLUSTER_NAME, ENDPOINT_PORT), UUID.randomUUID().toString());
  private static final SnapshotResource<Listener> LISTENER =
      SnapshotResource.create(TestResources.createListener(ADS, V2, V2,
          LISTENER_NAME, LISTENER_PORT, ROUTE_NAME), UUID.randomUUID().toString());
  private static final SnapshotResource<RouteConfiguration> ROUTE =
      SnapshotResource.create(TestResources.createRoute(ROUTE_NAME, CLUSTER_NAME), UUID.randomUUID().toString());
  private static final SnapshotResource<Secret> SECRET =
      SnapshotResource.create(TestResources.createSecret(SECRET_NAME), UUID.randomUUID().toString());

  @Test
  public void createSingleVersionSetsResourcesCorrectly() {
    final String version = UUID.randomUUID().toString();

    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        ImmutableList.of(SECRET),
        version);

    assertThat(snapshot.clusters().resources())
        .containsEntry(CLUSTER_NAME, CLUSTER)
        .hasSize(1);

    assertThat(snapshot.endpoints().resources())
        .containsEntry(CLUSTER_NAME, ENDPOINT)
        .hasSize(1);

    assertThat(snapshot.listeners().resources())
        .containsEntry(LISTENER_NAME, LISTENER)
        .hasSize(1);

    assertThat(snapshot.routes().resources())
        .containsEntry(ROUTE_NAME, ROUTE)
        .hasSize(1);

    assertThat(snapshot.clusters().version()).isEqualTo(version);
    assertThat(snapshot.endpoints().version()).isEqualTo(version);
    assertThat(snapshot.listeners().version()).isEqualTo(version);
    assertThat(snapshot.routes().version()).isEqualTo(version);
  }

  @Test
  public void createSeparateVersionsSetsResourcesCorrectly() {
    final String clustersVersion = UUID.randomUUID().toString();
    final String endpointsVersion = UUID.randomUUID().toString();
    final String listenersVersion = UUID.randomUUID().toString();
    final String routesVersion = UUID.randomUUID().toString();
    final String secretsVersion = UUID.randomUUID().toString();

    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER), clustersVersion,
        ImmutableList.of(ENDPOINT), endpointsVersion,
        ImmutableList.of(LISTENER), listenersVersion,
        ImmutableList.of(ROUTE), routesVersion,
        ImmutableList.of(SECRET), secretsVersion
    );

    assertThat(snapshot.clusters().resources())
        .containsEntry(CLUSTER_NAME, CLUSTER)
        .hasSize(1);

    assertThat(snapshot.endpoints().resources())
        .containsEntry(CLUSTER_NAME, ENDPOINT)
        .hasSize(1);

    assertThat(snapshot.listeners().resources())
        .containsEntry(LISTENER_NAME, LISTENER)
        .hasSize(1);

    assertThat(snapshot.routes().resources())
        .containsEntry(ROUTE_NAME, ROUTE)
        .hasSize(1);

    assertThat(snapshot.clusters().version()).isEqualTo(clustersVersion);
    assertThat(snapshot.endpoints().version()).isEqualTo(endpointsVersion);
    assertThat(snapshot.listeners().version()).isEqualTo(listenersVersion);
    assertThat(snapshot.routes().version()).isEqualTo(routesVersion);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void resourcesReturnsExpectedResources() {
    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        ImmutableList.of(SECRET),
        UUID.randomUUID().toString());

    // We have to do some lame casting to appease java's compiler, otherwise it fails to compile due to limitations with
    // generic type constraints.

    assertThat(snapshot.resources(CLUSTER_TYPE_URL))
        .containsEntry(CLUSTER_NAME, CLUSTER)
        .hasSize(1);

    assertThat(snapshot.resources(ENDPOINT_TYPE_URL))
        .containsEntry(CLUSTER_NAME, ENDPOINT)
        .hasSize(1);

    assertThat(snapshot.resources(LISTENER_TYPE_URL))
        .containsEntry(LISTENER_NAME, LISTENER)
        .hasSize(1);

    assertThat(snapshot.resources(ROUTE_TYPE_URL))
        .containsEntry(ROUTE_NAME, ROUTE)
        .hasSize(1);

    String nullString = null;
    assertThat(snapshot.version(nullString)).isEmpty();
    assertThat(snapshot.resources("")).isEmpty();
    assertThat(snapshot.resources(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  public void versionReturnsExpectedVersion() {
    final String version = UUID.randomUUID().toString();

    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        ImmutableList.of(SECRET),
        version);

    assertThat(snapshot.version(CLUSTER_TYPE_URL)).isEqualTo(version);
    assertThat(snapshot.version(ENDPOINT_TYPE_URL)).isEqualTo(version);
    assertThat(snapshot.version(LISTENER_TYPE_URL)).isEqualTo(version);
    assertThat(snapshot.version(ROUTE_TYPE_URL)).isEqualTo(version);

    String nullString = null;
    assertThat(snapshot.version(nullString)).isEmpty();
    assertThat(snapshot.version("")).isEmpty();
    assertThat(snapshot.version(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  public void ensureConsistentReturnsWithoutExceptionForConsistentSnapshot() throws
      SnapshotConsistencyException {
    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        ImmutableList.of(SECRET),
        UUID.randomUUID().toString());

    snapshot.ensureConsistent();
  }

  @Test
  public void ensureConsistentThrowsIfEndpointOrRouteRefCountMismatch() {
    Snapshot snapshot1 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        ImmutableList.of(SECRET),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot1::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format(
            "Mismatched %s -> %s reference and resource lengths, [%s] != 0",
            CLUSTER_TYPE_URL,
            ENDPOINT_TYPE_URL,
            CLUSTER_NAME));

    Snapshot snapshot2 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(),
        ImmutableList.of(SECRET),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot2::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format(
            "Mismatched %s -> %s reference and resource lengths, [%s] != 0",
            LISTENER_TYPE_URL,
            ROUTE_TYPE_URL,
            ROUTE_NAME));
  }

  @Test
  public void ensureConsistentThrowsIfEndpointOrRouteNamesMismatch() {
    final String otherClusterName = "someothercluster0";
    final String otherRouteName = "someotherroute0";

    Snapshot snapshot1 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(
            SnapshotResource.create(TestResources.createEndpoint(otherClusterName, ENDPOINT_PORT),
                UUID.randomUUID().toString())),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        ImmutableList.of(SECRET),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot1::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format(
            "%s named '%s', referenced by a %s, not listed in [%s]",
            ENDPOINT_TYPE_URL,
            CLUSTER_NAME,
            CLUSTER_TYPE_URL,
            otherClusterName));

    Snapshot snapshot2 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(
            SnapshotResource.create(TestResources.createRoute(otherRouteName, CLUSTER_NAME),
                UUID.randomUUID().toString())),
        ImmutableList.of(SECRET),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot2::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format(
            "%s named '%s', referenced by a %s, not listed in [%s]",
            ROUTE_TYPE_URL,
            ROUTE_NAME,
            LISTENER_TYPE_URL,
            otherRouteName));
  }
}
