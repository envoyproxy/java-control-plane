package io.envoyproxy.controlplane.server;

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.envoyproxy.controlplane.cache.ResourceType.CLUSTER;
import static io.envoyproxy.controlplane.cache.ResourceType.ENDPOINT;
import static io.envoyproxy.controlplane.cache.ResourceType.LISTENER;

import com.google.protobuf.Any;
import envoy.api.v2.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceImplBase;
import envoy.api.v2.Discovery.DiscoveryRequest;
import envoy.api.v2.Discovery.DiscoveryResponse;
import envoy.api.v2.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceImplBase;
import envoy.api.v2.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceImplBase;
import envoy.api.v2.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceImplBase;
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.ResourceType;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Watch;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class DiscoveryServer {

  private static final String ANY_TYPE_URL = "";

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

  private final ConfigWatcher configWatcher;
  private final AtomicLong streamCount = new AtomicLong();

  public DiscoveryServer(ConfigWatcher configWatcher) {
    this.configWatcher = configWatcher;
  }

  /**
   * Returns an ADS implementation that uses this server's {@link ConfigWatcher}.
   */
  public AggregatedDiscoveryServiceImplBase getAggregatedDiscoveryServiceImpl() {
    return new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, ANY_TYPE_URL);
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

        return createRequestHandler(responseObserver, CLUSTER.typeUrl());
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

        return createRequestHandler(responseObserver, ENDPOINT.typeUrl());
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

        return createRequestHandler(responseObserver, LISTENER.typeUrl());
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

        return createRequestHandler(responseObserver, ResourceType.ROUTE.typeUrl());
      }
    };
  }

  private StreamObserver<DiscoveryRequest> createRequestHandler(
      StreamObserver<DiscoveryResponse> responseObserver,
      String defaultTypeUrl) {

    long streamId = streamCount.getAndIncrement();

    LOGGER.info("[{}] open stream from {}", streamId, defaultTypeUrl);

    return new StreamObserver<DiscoveryRequest>() {

      private final Map<ResourceType, Watch> watches = new ConcurrentHashMap<>(ResourceType.values().length);
      private final Map<ResourceType, String> nonces = new ConcurrentHashMap<>(ResourceType.values().length);

      private AtomicLong streamNonce = new AtomicLong();

      @Override
      public void onNext(DiscoveryRequest request) {
        String nonce = request.getResponseNonce();
        String typeUrl = request.getTypeUrl();

        if (defaultTypeUrl.equals(ANY_TYPE_URL)) {
          if (typeUrl.isEmpty()) {
            responseObserver.onError(
                Status.UNKNOWN.withDescription("type URL is required for ADS").asRuntimeException());
          }
        } else if (typeUrl.isEmpty()) {
          typeUrl = defaultTypeUrl;
        }

        LOGGER.info("[{}] request {}[{}] with nonce {} from version {}",
            streamId,
            typeUrl,
            String.join(", ", request.getResourceNamesList()),
            nonce,
            request.getVersionInfo());

        for (ResourceType type : ResourceType.values()) {
          String resourceNonce = nonces.get(type);

          if (typeUrl.equals(type.typeUrl()) && (isNullOrEmpty(resourceNonce) || resourceNonce.equals(nonce))) {
            watches.compute(type, (t, oldWatch) -> {
              if (oldWatch != null) {
                oldWatch.cancel();
              }

              Watch newWatch = configWatcher.watch(
                  type,
                  request.getNode(),
                  request.getVersionInfo(),
                  request.getResourceNamesList());

              Flux.from(newWatch.value())
                  .doAfterTerminate(() -> responseObserver.onError(
                      Status.UNAVAILABLE
                          .withDescription(String.format("%s watch failed", type.toString()))
                          .asException()))
                  .subscribe(r -> nonces.put(type, send(r, type.typeUrl())));

              return newWatch;
            });

            return;
          }
        }
      }

      @Override
      public void onError(Throwable t) {
        LOGGER.error("[{}] stream closed with error", streamId, t);
        responseObserver.onError(Status.fromThrowable(t).asException());
        cancel();
      }

      @Override
      public void onCompleted() {
        LOGGER.info("[{}] stream closed", streamId);
        responseObserver.onCompleted();
        cancel();
      }

      private void cancel() {
        watches.values().forEach(Watch::cancel);
      }

      private String send(Response response, String typeUrl) {
        String nonce = Long.toString(streamNonce.getAndIncrement());

        DiscoveryResponse discoveryResponse = DiscoveryResponse.newBuilder()
            .setVersionInfo(response.version())
            .addAllResources(response.resources().stream().map(Any::pack).collect(Collectors.toList()))
            .setCanary(response.canary())
            .setTypeUrl(typeUrl)
            .setNonce(nonce)
            .build();

        LOGGER.info("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce, response.version());

        responseObserver.onNext(discoveryResponse);

        return nonce;
      }
    };
  }
}
