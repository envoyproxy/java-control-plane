package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.CLUSTER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.ENDPOINT_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.LISTENER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.ROUTE_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.SECRET_TYPE_URL;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.api.v2.Cds.Cluster;
import io.envoyproxy.envoy.api.v2.Eds.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Lds.Listener;
import io.envoyproxy.envoy.api.v2.Rds.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.Cert.Secret;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code Snapshot} is a data class that contains an internally consistent snapshot of xDS resources. Snapshots should
 * have distinct versions per node group.
 */
@AutoValue
public abstract class Snapshot {

  /**
   * Returns a new {@link Snapshot} instance that is versioned uniformly across all resources.
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
      Iterable<Secret> secrets,
      String version) {

    return new AutoValue_Snapshot(
        SnapshotResources.create(clusters, version),
        SnapshotResources.create(endpoints, version),
        SnapshotResources.create(listeners, version),
        SnapshotResources.create(routes, version),
        SnapshotResources.create(secrets, version));
  }

  /**
   * Returns a new {@link Snapshot} instance that has separate versions for each resource type.
   *
   * @param clusters the cluster resources in this snapshot
   * @param clustersVersion the version of the cluster resources
   * @param endpoints the endpoint resources in this snapshot
   * @param endpointsVersion the version of the endpoint resources
   * @param listeners the listener resources in this snapshot
   * @param listenersVersion the version of the listener resources
   * @param routes the route resources in this snapshot
   * @param routesVersion the version of the route resources
   */
  public static Snapshot create(
      Iterable<Cluster> clusters,
      String clustersVersion,
      Iterable<ClusterLoadAssignment> endpoints,
      String endpointsVersion,
      Iterable<Listener> listeners,
      String listenersVersion,
      Iterable<RouteConfiguration> routes,
      String routesVersion,
      Iterable<Secret> secrets,
      String secretsVersion) {

    // TODO(snowp): add a builder alternative
    return new AutoValue_Snapshot(
        SnapshotResources.create(clusters, clustersVersion),
        SnapshotResources.create(endpoints, endpointsVersion),
        SnapshotResources.create(listeners, listenersVersion),
        SnapshotResources.create(routes, routesVersion),
        SnapshotResources.create(secrets, secretsVersion));
  }

  /**
   * Returns a new {@link Snapshot} instance that has separate versions for each resource type.
   *
   * @param clusters the cluster resources in this snapshot
   * @param clustersVersion the version of the cluster resources
   * @param endpoints the endpoint resources in this snapshot
   * @param endpointVersions versions for cluster names
   * @param listeners the listener resources in this snapshot
   * @param listenersVersion the version of the listener resources
   * @param routes the route resources in this snapshot
   * @param routesVersion the version of the route resources
   */
  public static Snapshot create(
      Iterable<Cluster> clusters,
      String clustersVersion,
      Iterable<ClusterLoadAssignment> endpoints,
      Map<String, String> endpointVersions,
      Iterable<Listener> listeners,
      String listenersVersion,
      Iterable<RouteConfiguration> routes,
      String routesVersion,
      Iterable<Secret> secrets,
      String secretsVersion) {

    return new AutoValue_Snapshot(
        SnapshotResources.create(clusters, clustersVersion),
        SnapshotResources.create(endpoints, clustersVersion, endpointVersions),
        SnapshotResources.create(listeners, listenersVersion),
        SnapshotResources.create(routes, routesVersion),
        SnapshotResources.create(secrets, secretsVersion));
  }

  /**
   * Creates an empty snapshot with the given version.
   *
   * @param version the version of the snapshot resources
   */
  public static Snapshot createEmpty(String version) {
    return create(Collections.emptySet(), Collections.emptySet(),
        Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), version);
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
   * Returns all secret items in the SDS payload.
   */
  public abstract SnapshotResources<Secret> secrets();

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

    ensureAllResourceNamesExist(CLUSTER_TYPE_URL, ENDPOINT_TYPE_URL, clusterEndpointRefs, endpoints().resources());

    Set<String> listenerRouteRefs = Resources.getResourceReferences(listeners().resources().values());

    ensureAllResourceNamesExist(LISTENER_TYPE_URL, ROUTE_TYPE_URL, listenerRouteRefs, routes().resources());
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
      case CLUSTER_TYPE_URL:
        return clusters().resources();
      case ENDPOINT_TYPE_URL:
        return endpoints().resources();
      case LISTENER_TYPE_URL:
        return listeners().resources();
      case ROUTE_TYPE_URL:
        return routes().resources();
      case SECRET_TYPE_URL:
        return secrets().resources();
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
    return version(typeUrl, Collections.emptyList());
  }

  /**
   * Returns the version in this snapshot for the given resource type.
   *
   * @param typeUrl the URL for the requested resource type
   * @param resourceNames list of resource names
   */
  public String version(String typeUrl, List<String> resourceNames) {
    if (Strings.isNullOrEmpty(typeUrl)) {
      return "";
    }

    switch (typeUrl) {
      case CLUSTER_TYPE_URL:
        return clusters().version(resourceNames);
      case ENDPOINT_TYPE_URL:
        return endpoints().version(resourceNames);
      case LISTENER_TYPE_URL:
        return listeners().version(resourceNames);
      case ROUTE_TYPE_URL:
        return routes().version(resourceNames);
      case SECRET_TYPE_URL:
        return secrets().version(resourceNames);
      default:
        return "";
    }
  }

  /**
   * Asserts that all of the given resource names have corresponding values in the given resources collection.
   *
   * @param parentTypeUrl the type of the parent resources (source of the resource name refs)
   * @param dependencyTypeUrl the type of the given dependent resources
   * @param resourceNames the set of dependent resource names that must exist
   * @param resources the collection of resources whose names are being checked
   * @throws SnapshotConsistencyException if a name is given that does not exist in the resources collection
   */
  private static void ensureAllResourceNamesExist(
      String parentTypeUrl,
      String dependencyTypeUrl,
      Set<String> resourceNames,
      Map<String, ? extends Message> resources) throws SnapshotConsistencyException {

    if (resourceNames.size() != resources.size()) {
      throw new SnapshotConsistencyException(
          String.format(
              "Mismatched %s -> %s reference and resource lengths, [%s] != %d",
              parentTypeUrl,
              dependencyTypeUrl,
              String.join(", ", resourceNames),
              resources.size()));
    }

    for (String name : resourceNames) {
      if (!resources.containsKey(name)) {
        throw new SnapshotConsistencyException(
            String.format(
                "%s named '%s', referenced by a %s, not listed in [%s]",
                dependencyTypeUrl,
                name,
                parentTypeUrl,
                String.join(", ", resources.keySet())));
      }
    }
  }
}
