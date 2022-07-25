package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

public class SnapshotResourcesTest {

  private static final String CLUSTER0_NAME  = "cluster0";
  private static final String CLUSTER1_NAME  = "cluster1";

  private static final VersionedResource<Cluster> CLUSTER0 = VersionedResource.create(
      TestResources.createCluster(CLUSTER0_NAME), UUID.randomUUID().toString());
  private static final VersionedResource<Cluster> CLUSTER1 = VersionedResource.create(
      TestResources.createCluster(CLUSTER1_NAME), UUID.randomUUID().toString());

  @Test
  public void createBuildsResourcesMapWithNameAndPopulatesVersion() {
    final String version = UUID.randomUUID().toString();

    SnapshotResources<Cluster> snapshot = SnapshotResources.create(ImmutableList.of(CLUSTER0, CLUSTER1), version);

    assertThat(snapshot.resources())
        .containsEntry(CLUSTER0_NAME, CLUSTER0.resource())
        .containsEntry(CLUSTER1_NAME, CLUSTER1.resource())
        .hasSize(2);

    assertThat(snapshot.version()).isEqualTo(version);
  }

  @Test
  public void populatesVersionWithSeparateVersionPerCluster() {
    final String aggregateVersion = UUID.randomUUID().toString();

    final Map<String, String> versions = ImmutableMap.of(
        CLUSTER0_NAME, UUID.randomUUID().toString(),
        CLUSTER1_NAME, UUID.randomUUID().toString()
    );

    SnapshotResources<Cluster> snapshot = SnapshotResources.create(
        ImmutableList.of(CLUSTER0, CLUSTER1), resourceNames -> {
          if (resourceNames.size() != 1 || !versions.containsKey(resourceNames.get(0))) {
            return aggregateVersion;
          }
          return versions.get(resourceNames.get(0));
        }
    );

    // when no resource name provided, the aggregated version should be returned
    assertThat(snapshot.version()).isEqualTo(aggregateVersion);

    // when one resource name is provided, the cluster version should be returned
    assertThat(snapshot.version(ImmutableList.of(CLUSTER0_NAME))).isEqualTo(versions.get(CLUSTER0_NAME));
    assertThat(snapshot.version(ImmutableList.of(CLUSTER1_NAME))).isEqualTo(versions.get(CLUSTER1_NAME));

    // when an unknown resource name is provided, the aggregated version should be returned
    assertThat(snapshot.version(ImmutableList.of("unknown_cluster_name"))).isEqualTo(aggregateVersion);

    // when multiple resource names are provided, the aggregated version should be returned
    assertThat(snapshot.version(ImmutableList.of(CLUSTER1_NAME, CLUSTER1_NAME))).isEqualTo(aggregateVersion);
  }
}
