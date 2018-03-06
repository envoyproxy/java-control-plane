package io.envoyproxy.controlplane.cache;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;

public class SnapshotTest {

  private static final String CLUSTER_NAME = "cluster0";
  private static final String LISTENER_NAME = "listener0";
  private static final String ROUTE_NAME = "route0";

  private static final int ENDPOINT_PORT = ThreadLocalRandom.current().nextInt(10000, 20000);
  private static final int LISTENER_PORT = ThreadLocalRandom.current().nextInt(20000, 30000);

  private static final Cluster CLUSTER = TestResources.createCluster(CLUSTER_NAME);
  private static final ClusterLoadAssignment ENDPOINT = TestResources.createEndpoint(CLUSTER_NAME, ENDPOINT_PORT);
  private static final Listener LISTENER = TestResources.createListener(LISTENER_NAME, LISTENER_PORT, ROUTE_NAME);
  private static final RouteConfiguration ROUTE = TestResources.createRoute(ROUTE_NAME, CLUSTER_NAME);

  @Test
  public void createSetsResourcesCorrectly() {
    final String version = UUID.randomUUID().toString();

    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
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
  @SuppressWarnings("unchecked")
  public void resourcesReturnsExpectedResources() {
    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        UUID.randomUUID().toString());

    // We have to do some lame casting to appease java's compiler, otherwise it fails to compile due to limitations with
    // generic type constraints.

    assertThat((Map<String, Message>) snapshot.resources(Resources.CLUSTER_TYPE_URL))
        .containsEntry(CLUSTER_NAME, CLUSTER)
        .hasSize(1);

    assertThat((Map<String, Message>) snapshot.resources(Resources.ENDPOINT_TYPE_URL))
        .containsEntry(CLUSTER_NAME, ENDPOINT)
        .hasSize(1);

    assertThat((Map<String, Message>) snapshot.resources(Resources.LISTENER_TYPE_URL))
        .containsEntry(LISTENER_NAME, LISTENER)
        .hasSize(1);

    assertThat((Map<String, Message>) snapshot.resources(Resources.ROUTE_TYPE_URL))
        .containsEntry(ROUTE_NAME, ROUTE)
        .hasSize(1);

    assertThat(snapshot.resources(null)).isEmpty();
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
        version);

    assertThat(snapshot.version(Resources.CLUSTER_TYPE_URL)).isEqualTo(version);
    assertThat(snapshot.version(Resources.ENDPOINT_TYPE_URL)).isEqualTo(version);
    assertThat(snapshot.version(Resources.LISTENER_TYPE_URL)).isEqualTo(version);
    assertThat(snapshot.version(Resources.ROUTE_TYPE_URL)).isEqualTo(version);

    assertThat(snapshot.version(null)).isEmpty();
    assertThat(snapshot.version("")).isEmpty();
    assertThat(snapshot.version(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  public void ensureConsistentReturnsWithoutExceptionForConsistentSnapshot() throws SnapshotConsistencyException {
    Snapshot snapshot = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
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
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot1::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format("Mismatched cluster endpoint reference and resource lengths, [%s] != 0", CLUSTER_NAME));

    Snapshot snapshot2 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot2::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format("Mismatched listener route reference and resource lengths, [%s] != 0", ROUTE_NAME));
  }

  @Test
  public void ensureConsistentThrowsIfEndpointOrRouteNamesMismatch() {
    final String otherClusterName = "someothercluster0";
    final String otherRouteName = "someotherroute0";

    Snapshot snapshot1 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(TestResources.createEndpoint(otherClusterName, ENDPOINT_PORT)),
        ImmutableList.of(LISTENER),
        ImmutableList.of(ROUTE),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot1::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format("Resource named '%s' not listed in [%s]", CLUSTER_NAME, otherClusterName));

    Snapshot snapshot2 = Snapshot.create(
        ImmutableList.of(CLUSTER),
        ImmutableList.of(ENDPOINT),
        ImmutableList.of(LISTENER),
        ImmutableList.of(TestResources.createRoute(otherRouteName, CLUSTER_NAME)),
        UUID.randomUUID().toString());

    assertThatThrownBy(snapshot2::ensureConsistent)
        .isInstanceOf(SnapshotConsistencyException.class)
        .hasMessage(format("Resource named '%s' not listed in [%s]", ROUTE_NAME, otherRouteName));
  }
}
