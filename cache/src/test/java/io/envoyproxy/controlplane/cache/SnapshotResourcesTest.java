package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import envoy.api.v2.Cds.Cluster;
import java.util.UUID;
import org.junit.Test;

public class SnapshotResourcesTest {

  private static final String CLUSTER0_NAME  = "cluster0";
  private static final String CLUSTER1_NAME  = "cluster1";

  private static final Cluster CLUSTER0 = TestResources.createCluster(CLUSTER0_NAME);
  private static final Cluster CLUSTER1 = TestResources.createCluster(CLUSTER1_NAME);

  @Test
  public void createBuildsResourcesMapWithNameAndPopulatesVersion() {
    final String version = UUID.randomUUID().toString();

    SnapshotResources<Cluster> snapshot = SnapshotResources.create(ImmutableList.of(CLUSTER0, CLUSTER1), version);

    assertThat(snapshot.resources())
        .containsEntry(CLUSTER0_NAME, CLUSTER0)
        .containsEntry(CLUSTER1_NAME, CLUSTER1)
        .hasSize(2);

    assertThat(snapshot.version()).isEqualTo(version);
  }
}
