package io.envoyproxy.controlplane.server;

import static io.envoyproxy.controlplane.server.DiscoveryServer.ANY_TYPE_URL;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code AdsDiscoveryRequestStreamObserver} is an implementation of {@link DiscoveryRequestStreamObserver} tailored for
 * ADS streams, which handle multiple watches for all TYPE_URLS.
 */
public class AdsDiscoveryRequestStreamObserver extends DiscoveryRequestStreamObserver {
  private final ConcurrentMap<String, Watch> watches;
  private final ConcurrentMap<String, LatestDiscoveryResponse> latestResponse;
  private final ConcurrentMap<String, Set<String>> ackedResources;

  AdsDiscoveryRequestStreamObserver(StreamObserver<DiscoveryResponse> responseObserver,
                                    long streamId,
                                    Executor executor,
                                    DiscoveryServer discoveryServer) {
    super(ANY_TYPE_URL, responseObserver, streamId, executor, discoveryServer);
    this.watches = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
    this.latestResponse = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
    this.ackedResources = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
  }

  @Override
  public void onNext(DiscoveryRequest request) {
    if (request.getTypeUrl().isEmpty()) {
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
    watches.values().forEach(Watch::cancel);
  }

  @Override
  boolean ads() {
    return true;
  }

  @Override
  LatestDiscoveryResponse latestResponse(String typeUrl) {
    return latestResponse.get(typeUrl);
  }

  @Override
  void setLatestResponse(String typeUrl, LatestDiscoveryResponse response) {
    latestResponse.put(typeUrl, response);
    if (typeUrl.equals(Resources.CLUSTER_TYPE_URL)) {
      hasClusterChanged.set(true);
    } else if (typeUrl.equals(Resources.ENDPOINT_TYPE_URL)) {
      hasClusterChanged.set(false);
    }
  }

  @Override
  Set<String> ackedResources(String typeUrl) {
    return ackedResources.getOrDefault(typeUrl, Collections.emptySet());
  }

  @Override
  void setAckedResources(String typeUrl, Set<String> resources) {
    ackedResources.put(typeUrl, resources);
  }

  @Override
  void computeWatch(String typeUrl, Supplier<Watch> watchCreator) {
    watches.compute(typeUrl, (s, watch) -> {
      if (watch != null) {
        watch.cancel();
      }

      return watchCreator.get();
    });
  }
}
