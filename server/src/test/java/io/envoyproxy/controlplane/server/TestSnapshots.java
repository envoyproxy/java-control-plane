package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

class TestSnapshots {

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
    Listener listener = TestResources.createListener(ads, listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRoute(routeName, clusterName);

    return Snapshot.create(
        ImmutableList.of(cluster),
        ImmutableList.of(endpoint),
        ImmutableList.of(listener),
        ImmutableList.of(route),
        ImmutableList.of(),
        version);
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

    Cluster cluster = TestResources.createCluster(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, listenerName, listenerPort, routeName);
    RouteConfiguration route = TestResources.createRoute(routeName, clusterName);

    return Snapshot.create(
        ImmutableList.of(cluster),
        ImmutableList.of(),
        ImmutableList.of(listener),
        ImmutableList.of(route),
        ImmutableList.of(),
        version);
  }

  private TestSnapshots() { }
}
