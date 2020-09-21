package io.envoyproxy.controlplane.cache.v2;

import static io.envoyproxy.controlplane.cache.Resources.TYPE_URLS_TO_RESOURCE_TYPE;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.ResourceVersionResolver;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.controlplane.cache.SnapshotConsistencyException;
import io.envoyproxy.controlplane.cache.SnapshotResources;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code Snapshot} is a data class that contains an internally consistent snapshot of v2 xDS
 * resources. Snapshots should have distinct versions per node group.
 */
@AutoValue
public abstract class Snapshot extends io.envoyproxy.controlplane.cache.Snapshot {

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
   * @param clusterVersionResolver version resolver of the clusters in this snapshot
   * @param endpoints the endpoint resources in this snapshot
   * @param endpointVersionResolver version resolver of the endpoints in this snapshot
   * @param listeners the listener resources in this snapshot
   * @param listenerVersionResolver version resolver of listeners in this snapshot
   * @param routes the route resources in this snapshot
   * @param routeVersionResolver version resolver of the routes in this snapshot
   * @param secrets the secret resources in this snapshot
   * @param secretVersionResolver version resolver of the secrets in this snapshot
   */
  public static Snapshot create(
      Iterable<Cluster> clusters,
      ResourceVersionResolver clusterVersionResolver,
      Iterable<ClusterLoadAssignment> endpoints,
      ResourceVersionResolver endpointVersionResolver,
      Iterable<Listener> listeners,
      ResourceVersionResolver listenerVersionResolver,
      Iterable<RouteConfiguration> routes,
      ResourceVersionResolver routeVersionResolver,
      Iterable<Secret> secrets,
      ResourceVersionResolver secretVersionResolver) {

    return new AutoValue_Snapshot(
        SnapshotResources.create(clusters, clusterVersionResolver),
        SnapshotResources.create(endpoints, endpointVersionResolver),
        SnapshotResources.create(listeners, listenerVersionResolver),
        SnapshotResources.create(routes, routeVersionResolver),
        SnapshotResources.create(secrets, secretVersionResolver));
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

    ensureAllResourceNamesExist(Resources.V2.CLUSTER_TYPE_URL, Resources.V2.ENDPOINT_TYPE_URL,
        clusterEndpointRefs, endpoints().resources());

    Set<String> listenerRouteRefs = Resources.getResourceReferences(listeners().resources().values());

    ensureAllResourceNamesExist(Resources.V2.LISTENER_TYPE_URL, Resources.V2.ROUTE_TYPE_URL,
        listenerRouteRefs, routes().resources());
  }

  /**
   * Returns the resources with the given type.
   *
   * @param typeUrl the type URL of the requested resource type
   */
  public Map<String, ? extends Message> resources(String typeUrl) {
    if (Strings.isNullOrEmpty(typeUrl)) {
      return ImmutableMap.of();
    }

    ResourceType resourceType = TYPE_URLS_TO_RESOURCE_TYPE.get(typeUrl);
    if (resourceType == null) {
      return ImmutableMap.of();
    }

    return resources(resourceType);
  }

  /**
   * Returns the resources with the given type.
   *
   * @param resourceType the requested resource type
   */
  public Map<String, ? extends Message> resources(ResourceType resourceType) {
    switch (resourceType) {
      case CLUSTER:
        return clusters().resources();
      case ENDPOINT:
        return endpoints().resources();
      case LISTENER:
        return listeners().resources();
      case ROUTE:
        return routes().resources();
      case SECRET:
        return secrets().resources();
      default:
        return ImmutableMap.of();
    }
  }

  /**
   * Returns the version in this snapshot for the given resource type.
   *
   * @param typeUrl the type URL of the requested resource type
   */
  public String version(String typeUrl) {
    return version(typeUrl, Collections.emptyList());
  }

  /**
   * Returns the version in this snapshot for the given resource type.
   *
   * @param typeUrl the type URL of the requested resource type
   * @param resourceNames list of requested resource names,
   *                      used to calculate a version for the given resources
   */
  public String version(String typeUrl, List<String> resourceNames) {
    if (Strings.isNullOrEmpty(typeUrl)) {
      return "";
    }

    ResourceType resourceType = TYPE_URLS_TO_RESOURCE_TYPE.get(typeUrl);
    if (resourceType == null) {
      return "";
    }

    return version(resourceType, resourceNames);
  }

  public String version(ResourceType resourceType) {
    return version(resourceType, Collections.emptyList());
  }

  /**
   * Returns the version in this snapshot for the given resource type.
   *
   * @param resourceType the the requested resource type
   * @param resourceNames list of requested resource names,
   *                      used to calculate a version for the given resources
   */
  @Override
  public String version(ResourceType resourceType, List<String> resourceNames) {
    switch (resourceType) {
      case CLUSTER:
        return clusters().version(resourceNames);
      case ENDPOINT:
        return endpoints().version(resourceNames);
      case LISTENER:
        return listeners().version(resourceNames);
      case ROUTE:
        return routes().version(resourceNames);
      case SECRET:
        return secrets().version(resourceNames);
      default:
        return "";
    }
  }


}
