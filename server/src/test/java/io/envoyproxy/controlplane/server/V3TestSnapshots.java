package io.envoyproxy.controlplane.server;

import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V3;

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
      boolean delta,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createCluster(clusterName);
    ClusterLoadAssignment
        endpoint = TestResources.createEndpoint(clusterName, endpointAddress, endpointPort);
    Listener listener = TestResources.createListener(ads, delta, V3, V3, listenerName,
        listenerPort, routeName);
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
      boolean delta,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {
    return createSnapshotNoEds(ads, delta, V3, V3, clusterName, endpointAddress,
        endpointPort, listenerName, listenerPort, routeName, version);
  }

  private static Snapshot createSnapshotNoEds(
      boolean ads,
      boolean delta,
      ApiVersion rdsTransportVersion,
      ApiVersion rdsResourceVersion,
      String clusterName,
      String endpointAddress,
      int endpointPort,
      String listenerName,
      int listenerPort,
      String routeName,
      String version) {

    Cluster cluster = TestResources.createCluster(clusterName, endpointAddress,
        endpointPort, Cluster.DiscoveryType.STRICT_DNS);
    Listener listener = TestResources
        .createListener(ads, delta, rdsTransportVersion, rdsResourceVersion,
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

  private V3TestSnapshots() {}
}
