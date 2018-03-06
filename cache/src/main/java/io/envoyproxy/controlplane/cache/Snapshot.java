package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.Rds.RouteConfiguration;
import java.util.Map;
import java.util.Set;

/**
 * {@code Snapshot} is a data class that contains an internally consistent snapshot of xDS resources. Snapshots should
 * have distinct versions per node group.
 */
@AutoValue
public abstract class Snapshot {

  /**
   * Returns a new {@link Snapshot} instance.
   *
   * @param clusters the cluster resources in this snapshot
   * @param endpoints the endpoint resources in this snapshot
   * @param listeners the listener resources in this snapshot
   * @param routes the route resources in this snapshot
   * @param version the version associated with all resources in this snapshot
   */
  public static Snapshot create(
      Iterable<Cluster> clusters,
      Iterable<ClusterLoadAssignment> endpoints,
      Iterable<Listener> listeners,
      Iterable<RouteConfiguration> routes,
      String version) {

    return new AutoValue_Snapshot(
        SnapshotResources.create(clusters, version),
        SnapshotResources.create(endpoints, version),
        SnapshotResources.create(listeners, version),
        SnapshotResources.create(routes, version));
  }

  /**
   * Returns all cluster items in the CDS payload.
   */
  public abstract SnapshotResources<Cluster> clusters();

  /**
   * Returns all endpoint items in the EDS payload.
   */
  public abstract SnapshotResources<ClusterLoadAssignment> endpoints();

  /**
   * Returns all listener items in the LDS payload.
   */
  public abstract SnapshotResources<Listener> listeners();

  /**
   * Returns all route items in the RDS payload.
   */
  public abstract SnapshotResources<RouteConfiguration> routes();

  /**
   * Asserts that all dependent resources are included in the snapshot. All EDS resources are listed by name in CDS
   * resources, and all RDS resources are listed by name in LDS resources.
   *
   * <p>Note that clusters and listeners are requested without name references, so Envoy will accept the snapshot list
   * of clusters as-is, even if it does not match all references found in xDS.
   *
   * @throws SnapshotConsistencyException if the snapshot is not consistent
   */
  public void ensureConsistent() throws SnapshotConsistencyException {
    Set<String> clusterEndpointRefs = Resources.getResourceReferences(clusters().resources().values());

    if (clusterEndpointRefs.size() != endpoints().resources().size()) {
      throw new SnapshotConsistencyException(
          String.format(
              "Mismatched cluster endpoint reference and resource lengths, [%s] != %d",
              String.join(", ", clusterEndpointRefs),
              endpoints().resources().size()));
    }

    ensureAllResourceNamesExist(clusterEndpointRefs, endpoints().resources());

    Set<String> listenerRouteRefs = Resources.getResourceReferences(listeners().resources().values());

    if (listenerRouteRefs.size() != routes().resources().size()) {
      throw new SnapshotConsistencyException(
          String.format(
              "Mismatched listener route reference and resource lengths, [%s] != %d",
              String.join(", ", listenerRouteRefs),
              routes().resources().size()));
    }

    ensureAllResourceNamesExist(listenerRouteRefs, routes().resources());
  }

  /**
   * Returns the resources with the given type.
   *
   * @param typeUrl the URL for the requested resource type
   */
  public Map<String, ? extends Message> resources(String typeUrl) {
    if (Strings.isNullOrEmpty(typeUrl)) {
      return ImmutableMap.of();
    }

    switch (typeUrl) {
      case Resources.CLUSTER_TYPE_URL:
        return clusters().resources();
      case Resources.ENDPOINT_TYPE_URL:
        return endpoints().resources();
      case Resources.LISTENER_TYPE_URL:
        return listeners().resources();
      case Resources.ROUTE_TYPE_URL:
        return routes().resources();
      default:
        return ImmutableMap.of();
    }
  }

  /**
   * Returns the version in this snapshot for the given resource type.
   *
   * @param typeUrl the URL for the requested resource type
   */
  public String version(String typeUrl) {
    if (Strings.isNullOrEmpty(typeUrl)) {
      return "";
    }

    switch (typeUrl) {
      case Resources.CLUSTER_TYPE_URL:
        return clusters().version();
      case Resources.ENDPOINT_TYPE_URL:
        return endpoints().version();
      case Resources.LISTENER_TYPE_URL:
        return listeners().version();
      case Resources.ROUTE_TYPE_URL:
        return routes().version();
      default:
        return "";
    }
  }

  /**
   * Asserts that all of the given resource names have corresponding values in the given resources collection.
   *
   * @param resourceNames the set of resource names that must exist
   * @param resources the collection of resources whose names are being checked
   * @throws SnapshotConsistencyException if a name is given that does not exist in the resources collection
   */
  private void ensureAllResourceNamesExist(Set<String> resourceNames, Map<String, ? extends Message> resources)
      throws SnapshotConsistencyException {

    for (String name : resourceNames) {
      if (!resources.containsKey(name)) {
        throw new SnapshotConsistencyException(
            String.format(
                "Resource named '%s' not listed in [%s]",
                name,
                String.join(", ", resources.keySet())));
      }
    }
  }
}
