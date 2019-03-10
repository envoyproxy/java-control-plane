package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.grpc.stub.StreamObserver;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class XdsDiscoveryRequestStreamObserver extends DiscoveryRequestStreamObserver {
  private volatile Watch watch;
  private volatile DiscoveryResponse latestResponse;
  private Set<String> ackedResources;

  XdsDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                    StreamObserver<DiscoveryResponse> responseObserver,
                                    long streamId,
                                    Executor executor,
                                    DiscoveryServer discoveryServer) {
    super(defaultTypeUrl, responseObserver, streamId, executor, discoveryServer);
    this.ackedResources = Collections.emptySet();
  }

  @Override
  public void onNext(DiscoveryRequest request) {
    processRequest(defaultTypeUrl, request);
  }

  @Override
  void cancel() {
    if (watch != null) {
      watch.cancel();
    }
  }

  @Override
  boolean ads() {
    return false;
  }

  @Override
  DiscoveryResponse latestResponse(String typeUrl) {
    return latestResponse;
  }

  @Override
  void setLatestResponse(String typeUrl, DiscoveryResponse response) {
    latestResponse = response;
  }

  @Override
  Set<String> ackedResources(String typeUrl) {
    return ackedResources;
  }

  @Override
  void setAckedResources(String typeUrl, Set<String> resources) {
    ackedResources = resources;
  }

  @Override
  void computeWatch(String typeUrl, Supplier<Watch> watchCreator) {
    if (watch != null) {
      watch.cancel();
    }

    watch = watchCreator.get();
  }
}
