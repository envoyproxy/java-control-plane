package io.envoyproxy.controlplane.server;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.protobuf.Any;
import envoy.api.v2.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceImplBase;
import envoy.api.v2.Discovery.DiscoveryRequest;
import envoy.api.v2.Discovery.DiscoveryResponse;
import envoy.api.v2.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceImplBase;
import envoy.api.v2.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceImplBase;
import envoy.api.v2.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceImplBase;
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Watch;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class DiscoveryServer {

  private static final DiscoveryServerCallbacks DEFAULT_CALLBACKS = new DiscoveryServerCallbacks() { };
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

  static final String ANY_TYPE_URL = "";

  private final DiscoveryServerCallbacks callbacks;
  private final ConfigWatcher configWatcher;
  private final AtomicLong streamCount = new AtomicLong();

  public DiscoveryServer(DiscoveryServerCallbacks callbacks, ConfigWatcher configWatcher) {
    this.callbacks = callbacks;
    this.configWatcher = configWatcher;
  }

  public DiscoveryServer(ConfigWatcher configWatcher) {
    this(DEFAULT_CALLBACKS, configWatcher);
  }

  /**
   * Returns an ADS implementation that uses this server's {@link ConfigWatcher}.
   */
  public AggregatedDiscoveryServiceImplBase getAggregatedDiscoveryServiceImpl() {
    return new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, true, ANY_TYPE_URL);
      }
    };
  }

  /**
   * Returns a CDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public ClusterDiscoveryServiceImplBase getClusterDiscoveryServiceImpl() {
    return new ClusterDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamClusters(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, false, Resources.CLUSTER_TYPE_URL);
      }
    };
  }

  /**
   * Returns an EDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public EndpointDiscoveryServiceImplBase getEndpointDiscoveryServiceImpl() {
    return new EndpointDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamEndpoints(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, false, Resources.ENDPOINT_TYPE_URL);
      }
    };
  }

  /**
   * Returns a LDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public ListenerDiscoveryServiceImplBase getListenerDiscoveryServiceImpl() {
    return new ListenerDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamListeners(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, false, Resources.LISTENER_TYPE_URL);
      }
    };
  }

  /**
   * Returns a RDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public RouteDiscoveryServiceImplBase getRouteDiscoveryServiceImpl() {
    return new RouteDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamRoutes(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, false, Resources.ROUTE_TYPE_URL);
      }
    };
  }

  private StreamObserver<DiscoveryRequest> createRequestHandler(
      StreamObserver<DiscoveryResponse> responseObserver,
      boolean ads,
      String defaultTypeUrl) {

    long streamId = streamCount.getAndIncrement();

    LOGGER.info("[{}] open stream from {}", streamId, defaultTypeUrl);

    callbacks.onStreamOpen(streamId, defaultTypeUrl);

    return new StreamObserver<DiscoveryRequest>() {

      private final Map<String, Watch> watches = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
      private final Map<String, DiscoveryResponse> latestResponse =
          new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
      private final Map<String, Set<String>> ackedResources = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());

      private AtomicLong streamNonce = new AtomicLong();

      @Override
      public void onNext(DiscoveryRequest request) {
        String nonce = request.getResponseNonce();
        String requestTypeUrl = request.getTypeUrl();

        if (defaultTypeUrl.equals(ANY_TYPE_URL)) {
          if (requestTypeUrl.isEmpty()) {
            responseObserver.onError(
                Status.UNKNOWN
                    .withDescription(String.format("[%d] type URL is required for ADS", streamId))
                    .asRuntimeException());

            return;
          }
        } else if (requestTypeUrl.isEmpty()) {
          requestTypeUrl = defaultTypeUrl;
        }

        LOGGER.info("[{}] request {}[{}] with nonce {} from version {}",
            streamId,
            requestTypeUrl,
            String.join(", ", request.getResourceNamesList()),
            nonce,
            request.getVersionInfo());

        callbacks.onStreamRequest(streamId, request);

        for (String typeUrl : Resources.TYPE_URLS) {
          DiscoveryResponse response = latestResponse.get(typeUrl);
          String resourceNonce = response == null ? null : response.getNonce();

          if (requestTypeUrl.equals(typeUrl) && (isNullOrEmpty(resourceNonce)
              || resourceNonce.equals(nonce))) {
            if (!request.hasErrorDetail() && response != null) {
              Set<String> ackedResourcesForType = response.getResourcesList()
                  .stream()
                  .map(Resources::getResourceName)
                  .collect(Collectors.toSet());
              ackedResources.put(typeUrl, ackedResourcesForType);
            }

            watches.compute(typeUrl, (t, oldWatch) -> {
              if (oldWatch != null) {
                oldWatch.cancel();
              }

              Watch newWatch = configWatcher.createWatch(ads, request,
                  ackedResources.getOrDefault(typeUrl, Collections.emptySet()));

              Flux.from(newWatch.value())
                  .doOnError(e -> responseObserver.onError(
                      Status.UNAVAILABLE
                          .withCause(e)
                          .withDescription(String.format("[%d] %s watch failed", streamId, typeUrl))
                          .asException()))
                  .subscribe(r -> latestResponse.put(typeUrl, send(r, typeUrl)));

              return newWatch;
            });

            return;
          }
        }
      }

      @Override
      public void onError(Throwable t) {
        if (!Status.fromThrowable(t).equals(Status.CANCELLED)) {
          LOGGER.error("[{}] stream closed with error", streamId, t);
        }

        callbacks.onStreamCloseWithError(streamId, defaultTypeUrl, t);
        responseObserver.onError(Status.fromThrowable(t).asException());
        cancel();
      }

      @Override
      public void onCompleted() {
        LOGGER.info("[{}] stream closed", streamId);
        callbacks.onStreamClose(streamId, defaultTypeUrl);
        responseObserver.onCompleted();
        cancel();
      }

      private void cancel() {
        watches.values().forEach(Watch::cancel);
      }

      private DiscoveryResponse send(Response response, String typeUrl) {
        String nonce = Long.toString(streamNonce.getAndIncrement());

        DiscoveryResponse discoveryResponse = DiscoveryResponse.newBuilder()
            .setVersionInfo(response.version())
            .addAllResources(response.resources().stream().map(Any::pack).collect(Collectors.toList()))
            .setTypeUrl(typeUrl)
            .setNonce(nonce)
            .build();

        LOGGER.info("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce, response.version());

        callbacks.onStreamResponse(streamId, response.request(), discoveryResponse);

        // The watch value streams are being observed on multiple threads, so we need to synchronize
        // here because StreamObserver instances are not thread-safe.
        synchronized (responseObserver) {
          responseObserver.onNext(discoveryResponse);
        }

        return discoveryResponse;
      }
    };
  }
}
