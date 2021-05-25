package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.DeltaResponse;
import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.DeltaXdsRequest;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code DeltaDiscoveryRequestStreamObserver} provides the base implementation for Delta XDS stream handling.
 * The proto message types are abstracted so it can be used with different xDS versions.
 */
public abstract class DeltaDiscoveryRequestStreamObserver<V, X, Y> implements StreamObserver<V> {
  private static final AtomicLongFieldUpdater<DeltaDiscoveryRequestStreamObserver> streamNonceUpdater =
      AtomicLongFieldUpdater.newUpdater(DeltaDiscoveryRequestStreamObserver.class, "streamNonce");
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

  final long streamId;
  private final String defaultTypeUrl;
  private final StreamObserver<X> responseObserver;
  private final Executor executor;
  volatile boolean hasClusterChanged;
  final DiscoveryServer<?, ?, V, X, Y> discoveryServer;
  private volatile long streamNonce;
  private volatile boolean isClosing;

  DeltaDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                      StreamObserver<X> responseObserver,
                                      long streamId,
                                      Executor executor,
                                      DiscoveryServer<?, ?, V, X, Y> discoveryServer) {
    this.defaultTypeUrl = defaultTypeUrl;
    this.responseObserver = responseObserver;
    this.streamId = streamId;
    this.executor = executor;
    this.streamNonce = 0;
    this.discoveryServer = discoveryServer;
    this.hasClusterChanged = false;
  }

  @Override
  public void onNext(V rawRequest) {
    DeltaXdsRequest request = discoveryServer.wrapDeltaXdsRequest(rawRequest);
    String requestTypeUrl = request.getTypeUrl().isEmpty() ? defaultTypeUrl : request.getTypeUrl();

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[{}] request {}[{}] with nonce {} from versions {}",
          streamId,
          requestTypeUrl,
          String.join(", ", request.getResourceNamesSubscribeList()),
          request.getResponseNonce(),
          request.getInitialResourceVersionsMap());
    }

    try {
      discoveryServer.runStreamDeltaRequestCallbacks(streamId, rawRequest);
    } catch (RequestException e) {
      closeWithError(e);
      return;
    }

    final String version;
    if (latestVersion(requestTypeUrl) == null) {
      version = "";
    } else {
      version = latestVersion(requestTypeUrl);
    }

    // always update subscriptions
    updateSubscriptions(requestTypeUrl,
        request.getResourceNamesSubscribeList(),
        request.getResourceNamesUnsubscribeList());

    if (!request.getResponseNonce().isEmpty()) {
      // envoy is replying to a response we sent, get and clear respective response
      LatestDeltaDiscoveryResponse response = clearResponse(requestTypeUrl, request.getResponseNonce());
      if (!request.hasErrorDetail()) {
        // if envoy has acked, update tracked resources
        // from the corresponding response
        updateTrackedResources(requestTypeUrl,
            response.resourceVersions(),
            response.removedResources());
      }
    }

    // if nonce is empty, envoy is only requesting new resources or this is a new connection,
    // in either case we have already updated the subscriptions,
    // but we should only create watches when there's no pending ack
    // this tries to ensure we don't have two outstanding responses
    if (responseCount(requestTypeUrl) == 0) {
      computeWatch(requestTypeUrl, () -> discoveryServer.configWatcher.createDeltaWatch(
          request,
          version,
          resourceVersions(requestTypeUrl),
          pendingResources(requestTypeUrl),
          isWildcard(requestTypeUrl),
          r -> executor.execute(() -> send(r, requestTypeUrl)),
          hasClusterChanged
      ));
    }
  }

  @Override
  public void onError(Throwable t) {
    if (!Status.fromThrowable(t).getCode().equals(Status.CANCELLED.getCode())) {
      LOGGER.error("[{}] stream closed with error", streamId, t);
    }

    try {
      discoveryServer.callbacks.forEach(cb -> cb.onStreamCloseWithError(streamId, defaultTypeUrl, t));
      closeWithError(Status.fromThrowable(t).asException());
    } finally {
      cancel();
    }
  }

  @Override
  public void onCompleted() {
    LOGGER.debug("[{}] stream closed", streamId);

    try {
      discoveryServer.callbacks.forEach(cb -> cb.onStreamClose(streamId, defaultTypeUrl));
      synchronized (responseObserver) {
        if (!isClosing) {
          isClosing = true;
          responseObserver.onCompleted();
        }
      }
    } finally {
      cancel();
    }
  }

  void onCancelled() {
    LOGGER.info("[{}] stream cancelled", streamId);
    cancel();
  }

  void closeWithError(Throwable exception) {
    synchronized (responseObserver) {
      if (!isClosing) {
        isClosing = true;
        responseObserver.onError(exception);
      }
    }
    cancel();
  }

  private void send(DeltaResponse response, String typeUrl) {
    String nonce = Long.toString(streamNonceUpdater.getAndIncrement(this));
    List<Y> resources = response.resources()
        .entrySet()
        .stream()
        .map(entry -> discoveryServer.makeResource(entry.getKey(),
            entry.getValue().version(),
            discoveryServer.protoResourcesSerializer.serialize(
                entry.getValue().resource(),
                Resources.getResourceApiVersion(typeUrl))))
        .collect(Collectors.toList());

    X discoveryResponse = discoveryServer.makeDeltaResponse(
        typeUrl,
        response.version(),
        nonce,
        resources,
        response.removedResources()
    );

    LOGGER.debug("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce,
        response.version());

    discoveryServer.runStreamDeltaResponseCallbacks(streamId, response.request(), discoveryResponse);

    // Store the latest response *before* we send the response. This ensures that by the time the request
    // is processed the map is guaranteed to be updated. Doing it afterwards leads to a race conditions
    // which may see the incoming request arrive before the map is updated, failing the nonce check erroneously.
    setResponse(
        typeUrl,
        nonce,
        LatestDeltaDiscoveryResponse.create(
            nonce,
            response.version(),
            response.resources()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().version())),
            response.removedResources()
        )
    );
    setLatestVersion(typeUrl, response.version());

    synchronized (responseObserver) {
      if (!isClosing) {
        try {
          responseObserver.onNext(discoveryResponse);
        } catch (StatusRuntimeException e) {
          if (!Status.CANCELLED.getCode().equals(e.getStatus().getCode())) {
            throw e;
          }
        }
      }
    }
  }

  abstract void cancel();

  abstract boolean ads();

  abstract void setLatestVersion(String typeUrl, String version);

  abstract String latestVersion(String typeUrl);

  abstract void setResponse(String typeUrl, String nonce, LatestDeltaDiscoveryResponse response);

  abstract LatestDeltaDiscoveryResponse clearResponse(String typeUrl, String nonce);

  abstract int responseCount(String typeUrl);

  abstract Map<String, String> resourceVersions(String typeUrl);

  abstract Set<String> pendingResources(String typeUrl);

  abstract boolean isWildcard(String typeUrl);

  abstract void updateTrackedResources(String typeUrl,
                                       Map<String, String> resourcesVersions,
                                       List<String> removedResources);

  abstract void updateSubscriptions(String typeUrl,
                                    List<String> resourceNamesSubscribe,
                                    List<String> resourceNamesUnsubscribe);

  abstract void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator);
}
