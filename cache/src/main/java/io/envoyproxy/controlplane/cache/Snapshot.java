package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.Message;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import java.util.Collection;

/**
 * {@code Snapshot} is a data class that contains an internally consistent snapshot of xDS resources. Snapshots should
 * have distinct versions per node group.
 */
@AutoValue
public abstract class Snapshot {

  /**
   * Returns a new snapshot with the given resources.
   *
   * @param resources multi-map of resources in this snapshot, keyed on their type
   * @param version cache-tracked version of this snapshot
   */
  public static Snapshot create(Multimap<ResourceType, Message> resources, String version) {
    return new AutoValue_Snapshot(resources, version);
  }

  /**
   * Returns a new snapshot with the given resources.
   *
   * @param clusters cluster resources in this snapshot
   * @param endpoints endpoint resources in this snapshot
   * @param listeners listener resources in this snapshot
   * @param routes route resources in this snapshot
   * @param version cache-tracked version of this snapshot
   */
  public static Snapshot create(
      Collection<? extends Cluster> clusters,
      Collection<? extends ClusterLoadAssignment> endpoints,
      Collection<? extends Listener> listeners,
      Collection<? extends RouteConfiguration> routes,
      String version) {

    return create(
        ImmutableMultimap.<ResourceType, Message>builder()
            .putAll(ResourceType.CLUSTER, clusters)
            .putAll(ResourceType.ENDPOINT, endpoints)
            .putAll(ResourceType.LISTENER, listeners)
            .putAll(ResourceType.ROUTE, routes)
            .build(),
        version);
  }

  /**
   * Returns the {@link Multimap} of resources in the snapshot, keyed on the resource type.
   */
  public abstract Multimap<ResourceType, Message> resources();

  /**
   * Returns the snapshot's version.
   */
  public abstract String version();
}
