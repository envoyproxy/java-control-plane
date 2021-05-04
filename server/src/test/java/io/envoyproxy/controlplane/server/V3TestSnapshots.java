package io.envoyproxy.controlplane.server;

import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V2;
import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V3;

import io.envoyproxy.controlplane.cache.SnapshotResource;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

class V3TestSnapshots {

  static Snapshot createSnapshot(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createClusterV3(clusterName);
    ClusterLoadAssignment
        endpoint = TestResources.createEndpointV3(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListenerV3(ads, V3, V3, listenerName,
        listenerPort, routeName);
    RouteConfiguration route = TestResources.createRouteV3(routeName, clusterName);

    return Snapshot.create(
        ImmutableList.of(SnapshotResource.create(cluster, version)),
        ImmutableList.of(SnapshotResource.create(endpoint, version)),
        ImmutableList.of(SnapshotResource.create(listener, version)),
        ImmutableList.of(SnapshotResource.create(route, version)),
        ImmutableList.of(),
        version);
  }

  static Snapshot createSnapshotNoEdsV2Transport(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {
    return createSnapshotNoEds(ads, V2, V2, clusterName, endpointAddress,
        endpointPort, listenerName, listenerPort, routeName, version);
  }

  static Snapshot createSnapshotNoEds(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {
    return createSnapshotNoEds(ads, V3, V3, clusterName, endpointAddress,
        endpointPort, listenerName, listenerPort, routeName, version);
  }

  private static Snapshot createSnapshotNoEds(
      boolean ads,
      ApiVersion rdsTransportVersion,
      ApiVersion rdsResourceVersion,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createClusterV3(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListenerV3(ads, rdsTransportVersion, rdsResourceVersion,
        listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRouteV3(routeName, clusterName);

    return Snapshot.create(
        ImmutableList.of(SnapshotResource.create(cluster, version)),
        ImmutableList.of(),
        ImmutableList.of(SnapshotResource.create(listener, version)),
        ImmutableList.of(SnapshotResource.create(route, version)),
        ImmutableList.of(),
        version);
  }

  private V3TestSnapshots() { }
}
