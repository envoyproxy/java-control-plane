package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
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
