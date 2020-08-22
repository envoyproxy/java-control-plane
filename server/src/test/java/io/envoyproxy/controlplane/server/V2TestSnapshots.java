package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v2.Snapshot;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.core.ApiVersion;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

class V2TestSnapshots {

  static Snapshot createSnapshot(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createCluster(clusterName);
    ClusterLoadAssignment endpoint = TestResources.createEndpoint(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, ApiVersion.V2, ApiVersion.V2,
        listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRoute(routeName, clusterName);

    return Snapshot.create(
        ImmutableList.of(cluster),
        ImmutableList.of(endpoint),
        ImmutableList.of(listener),
        ImmutableList.of(route),
        ImmutableList.of(),
        version);
  }

  static Snapshot createSnapshotNoEdsV3Transport(
      boolean ads,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {
    return createSnapshotNoEds(ads, ApiVersion.V3, ApiVersion.V3, clusterName, endpointAddress,
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
    return createSnapshotNoEds(ads, ApiVersion.V2, ApiVersion.V2, clusterName, endpointAddress,
        endpointPort, listenerName, listenerPort, routeName, version);
  }

  private static Snapshot createSnapshotNoEds(
      boolean ads,
      ApiVersion rdsTransportVersion,
      ApiVersion rdsResourceVersion, String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createCluster(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, rdsTransportVersion, rdsResourceVersion,
        listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRoute(routeName, clusterName);

    return Snapshot.create(
        ImmutableList.of(cluster),
        ImmutableList.of(),
        ImmutableList.of(listener),
        ImmutableList.of(route),
        ImmutableList.of(),
        version);
  }

  private V2TestSnapshots() { }
}
