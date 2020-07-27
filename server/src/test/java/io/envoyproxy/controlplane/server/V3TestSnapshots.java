package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.V3Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

class V3TestSnapshots {

  static V3Snapshot createSnapshot(
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
    Listener listener = TestResources.createListenerV3(ads, ApiVersion.V3, listenerName,
        listenerPort, routeName);
    RouteConfiguration route = TestResources.createRouteV3(routeName, clusterName);

    return V3Snapshot.create(
        ImmutableList.of(cluster),
        ImmutableList.of(endpoint),
        ImmutableList.of(listener),
        ImmutableList.of(route),
        ImmutableList.of(),
        version);
  }

  static V3Snapshot createSnapshotNoEdsV2Transport(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {
    return createSnapshotNoEds(ads, ApiVersion.V2, clusterName, endpointAddress, endpointPort,
        listenerName, listenerPort, routeName, version);
  }

  static V3Snapshot createSnapshotNoEds(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {
    return createSnapshotNoEds(ads, ApiVersion.V3, clusterName, endpointAddress, endpointPort,
        listenerName, listenerPort, routeName, version);
  }

  private static V3Snapshot createSnapshotNoEds(
      boolean ads,
      ApiVersion rdsTransportVersion,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createClusterV3(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListenerV3(ads, rdsTransportVersion, listenerName,
        listenerPort, routeName);
    RouteConfiguration route = TestResources.createRouteV3(routeName, clusterName);

    return V3Snapshot.create(
        ImmutableList.of(cluster),
        ImmutableList.of(),
        ImmutableList.of(listener),
        ImmutableList.of(route),
        ImmutableList.of(),
        version);
  }

  private V3TestSnapshots() { }
}
