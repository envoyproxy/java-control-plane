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

public class AdsDiscoveryRequestStreamObserver extends DiscoveryRequestStreamObserver {
  private final ConcurrentMap<String, Watch> watches;
  private final ConcurrentMap<String, DiscoveryResponse> latestResponse;
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
    String requestTypeUrl = request.getTypeUrl();

    if (requestTypeUrl.isEmpty()) {
      responseObserver.onError(
          Status.UNKNOWN
              .withDescription(String.format("[%d] type URL is required for ADS", streamId))
              .asRuntimeException());

      return;
    }

    processRequest(requestTypeUrl, request);
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
  DiscoveryResponse latestResponse(String typeUrl) {
    return latestResponse.get(typeUrl);
  }

  @Override
  void setLatestResponse(String typeUrl, DiscoveryResponse response) {
    latestResponse.put(typeUrl, response);
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
