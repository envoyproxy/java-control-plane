package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.DiscoveryServer.ANY_TYPE_URL;

import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.Resources;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code AdsDeltaDiscoveryRequestStreamObserver} is an implementation of {@link DeltaDiscoveryRequestStreamObserver}
 * tailored for ADS streams, which handle multiple watches for all TYPE_URLS.
 */

public class AdsDeltaDiscoveryRequestStreamObserver<V, X, Y> extends DeltaDiscoveryRequestStreamObserver<V, X, Y> {
  private final ConcurrentMap<String, DeltaWatch> watches;
  private final ConcurrentMap<String, String> latestVersion;
  private final ConcurrentMap<String, ConcurrentHashMap<String, LatestDeltaDiscoveryResponse>> responses;
  // tracked and pending resources are always accessed in the observer
  // so they are safe from race, no need for concurrent map
  private final Map<String, Map<String, String>> trackedResourceMap;
  private final Map<String, Set<String>> pendingResourceMap;

  AdsDeltaDiscoveryRequestStreamObserver(StreamObserver<X> responseObserver,
                                         long streamId,
                                         Executor executor,
                                         DiscoveryServer<?, ?, V, X, Y> discoveryServer) {
    super(ANY_TYPE_URL, responseObserver, streamId, executor, discoveryServer);

    this.watches = new ConcurrentHashMap<>(Resources.V3.TYPE_URLS.size());
    this.latestVersion = new ConcurrentHashMap<>(Resources.V3.TYPE_URLS.size());
    this.responses = new ConcurrentHashMap<>(Resources.V3.TYPE_URLS.size());
    this.trackedResourceMap = new HashMap<>(Resources.V3.TYPE_URLS.size());
    this.pendingResourceMap = new HashMap<>(Resources.V3.TYPE_URLS.size());
  }

  @Override
  public void onNext(V request) {
    if (discoveryServer.wrapDeltaXdsRequest(request).getTypeUrl().isEmpty()) {
      closeWithError(
          Status.UNKNOWN
              .withDescription(String.format("[%d] type URL is required for ADS", streamId))
              .asRuntimeException());

      return;
    }

    super.onNext(request);
  }

  @Override
  void cancel() {
    watches.values().forEach(DeltaWatch::cancel);
  }

  @Override
  boolean ads() {
    return true;
  }

  @Override
  void setLatestVersion(String typeUrl, String version) {
    latestVersion.put(typeUrl, version);
    if (typeUrl.equals(Resources.V3.CLUSTER_TYPE_URL)) {
      hasClusterChanged = true;
    } else if (typeUrl.equals(Resources.V3.ENDPOINT_TYPE_URL)) {
      hasClusterChanged = false;
    }
  }

  @Override
  String latestVersion(String typeUrl) {
    return latestVersion.get(typeUrl);
  }

  @Override
  void setResponse(String typeUrl, String nonce, LatestDeltaDiscoveryResponse response) {
    responses.computeIfAbsent(typeUrl, s -> new ConcurrentHashMap<>())
        .put(nonce, response);
  }

  @Override
  LatestDeltaDiscoveryResponse clearResponse(String typeUrl, String nonce) {
    return responses.computeIfAbsent(typeUrl, s -> new ConcurrentHashMap<>())
        .remove(nonce);
  }

  @Override
  int responseCount(String typeUrl) {
    return responses.computeIfAbsent(typeUrl, s -> new ConcurrentHashMap<>())
        .size();
  }

  @Override
  Map<String, String> resourceVersions(String typeUrl) {
    return trackedResourceMap.getOrDefault(typeUrl, Collections.emptyMap());
  }

  @Override
  Set<String> pendingResources(String typeUrl) {
    return pendingResourceMap.getOrDefault(typeUrl, Collections.emptySet());
  }

  @Override
  boolean isWildcard(String typeUrl) {
    Resources.ResourceType resourceType = Resources.TYPE_URLS_TO_RESOURCE_TYPE.get(typeUrl);
    return Resources.ResourceType.CLUSTER.equals(resourceType)
        || Resources.ResourceType.LISTENER.equals(resourceType);
  }

  @Override
  void updateTrackedResources(String typeUrl,
                              Map<String, String> resourcesVersions,
                              List<String> removedResources) {

    Map<String, String> trackedResources = trackedResourceMap.computeIfAbsent(typeUrl, s -> new HashMap<>());
    Set<String> pendingResources = pendingResourceMap.computeIfAbsent(typeUrl, s -> new HashSet<>());
    resourcesVersions.forEach((k, v) -> {
      trackedResources.put(k, v);
      pendingResources.remove(k);
    });
    removedResources.forEach(trackedResources::remove);
  }

  @Override
  void updateSubscriptions(String typeUrl,
                           List<String> resourceNamesSubscribe,
                           List<String> resourceNamesUnsubscribe) {

    Map<String, String> trackedResources = trackedResourceMap.computeIfAbsent(typeUrl, s -> new HashMap<>());
    Set<String> pendingResources = pendingResourceMap.computeIfAbsent(typeUrl, s -> new HashSet<>());
    // unsubscribe first
    resourceNamesUnsubscribe.forEach(s -> {
      trackedResources.remove(s);
      pendingResources.remove(s);
    });
    pendingResources.addAll(resourceNamesSubscribe);
  }

  @Override
  void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator) {
    watches.compute(typeUrl, (s, watch) -> {
      if (watch != null) {
        watch.cancel();
      }

      return watchCreator.get();
    });
  }
}
