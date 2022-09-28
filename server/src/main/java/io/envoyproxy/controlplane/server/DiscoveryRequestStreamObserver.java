package io.envoyproxy.controlplane.server;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.protobuf.Any;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code DiscoveryRequestStreamObserver} provides the base implementation for XDS stream handling.
 * The proto message types are abstracted so it can be used with different xDS versions.
 */
public abstract class DiscoveryRequestStreamObserver<T, U> implements StreamObserver<T> {
  private static final AtomicLongFieldUpdater<DiscoveryRequestStreamObserver> streamNonceUpdater =
      AtomicLongFieldUpdater.newUpdater(DiscoveryRequestStreamObserver.class, "streamNonce");
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

  final long streamId;
  final DiscoveryServer<T, U, ?, ?, ?> discoveryServer;
  private final String defaultTypeUrl;
  private final StreamObserver<U> responseObserver;
  private final Executor executor;
  volatile boolean hasClusterChanged;
  private volatile long streamNonce;
  private volatile boolean isClosing;

  DiscoveryRequestStreamObserver(String defaultTypeUrl,
                                 StreamObserver<U> responseObserver,
                                 long streamId,
                                 Executor executor,
                                 DiscoveryServer<T, U, ?, ?, ?> discoveryServer) {
    this.defaultTypeUrl = defaultTypeUrl;
    this.responseObserver = responseObserver;
    this.streamId = streamId;
    this.executor = executor;
    this.streamNonce = 0;
    this.discoveryServer = discoveryServer;
    this.hasClusterChanged = false;
  }

  @Override
  public void onNext(T rawRequest) {
    XdsRequest request = discoveryServer.wrapXdsRequest(rawRequest);
    String requestTypeUrl = request.getTypeUrl().isEmpty() ? defaultTypeUrl : request.getTypeUrl();
    String nonce = request.getResponseNonce();

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("[{}] request {}[{}] with nonce {} from version {}",
          streamId,
          requestTypeUrl,
          String.join(", ", request.getResourceNamesList()),
          nonce,
          request.getVersionInfo());
    }

    try {
      discoveryServer.runStreamRequestCallbacks(streamId, rawRequest);
    } catch (RequestException e) {
      closeWithError(e);
      return;
    }

    LatestDiscoveryResponse latestDiscoveryResponse = latestResponse(requestTypeUrl);
    String resourceNonce = latestDiscoveryResponse == null ? null : latestDiscoveryResponse.nonce();

    if (isNullOrEmpty(resourceNonce) || resourceNonce.equals(nonce)) {
      if (!request.hasErrorDetail() && latestDiscoveryResponse != null) {
        setAckedResources(requestTypeUrl, latestDiscoveryResponse.resourceNames());
      }

      computeWatch(requestTypeUrl, () -> discoveryServer.configWatcher.createWatch(
          ads(),
          request,
          ackedResources(requestTypeUrl),
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

  private void send(Response response, String typeUrl) {
    String nonce = Long.toString(streamNonceUpdater.getAndIncrement(this));

    Collection<Any> resources =
        discoveryServer.protoResourcesSerializer.serialize(response.resources(),
            Resources.getResourceApiVersion(typeUrl));

    LOGGER.debug("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce,
        response.version());

    U discoveryResponse = discoveryServer.makeResponse(response.version(), resources, typeUrl,
        nonce);
    discoveryServer.runStreamResponseCallbacks(streamId, response.request(), discoveryResponse);

    // Store the latest response *before* we send the response. This ensures that by the time the request
    // is processed the map is guaranteed to be updated. Doing it afterwards leads to a race conditions
    // which may see the incoming request arrive before the map is updated, failing the nonce check erroneously.
    setLatestResponse(
        typeUrl,
        LatestDiscoveryResponse.create(
            nonce,
            response.resources().stream().map(Resources::getResourceName).collect(Collectors.toSet())
        )
    );

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

  abstract LatestDiscoveryResponse latestResponse(String typeUrl);

  abstract void setLatestResponse(String typeUrl, LatestDiscoveryResponse response);

  abstract Set<String> ackedResources(String typeUrl);

  abstract void setAckedResources(String typeUrl, Set<String> resources);

  abstract void computeWatch(String typeUrl, Supplier<Watch> watchCreator);
}
