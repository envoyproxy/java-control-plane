package io.envoyproxy.controlplane.server;

import com.google.common.base.Preconditions;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer;
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.secret.v3.SecretDiscoveryServiceGrpc;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryServer {
  static final String ANY_TYPE_URL = "";
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);
  final List<DiscoveryServerCallbacks> callbacks;
  final ConfigWatcher configWatcher;
  final ProtoResourcesSerializer protoResourcesSerializer;
  private final ExecutorGroup executorGroup;
  private final AtomicLong streamCount = new AtomicLong();

  public DiscoveryServer(ConfigWatcher configWatcher) {
    this(Collections.emptyList(), configWatcher);
  }

  public DiscoveryServer(DiscoveryServerCallbacks callbacks, ConfigWatcher configWatcher) {
    this(Collections.singletonList(callbacks), configWatcher);
  }

  /**
   * Creates the server.
   *
   * @param callbacks     server callbacks
   * @param configWatcher source of configuration updates
   */
  public DiscoveryServer(List<DiscoveryServerCallbacks> callbacks, ConfigWatcher configWatcher) {
    this(callbacks, configWatcher, new DefaultExecutorGroup(), new DefaultProtoResourcesSerializer());
  }

  /**
   * Creates the server.
   *
   * @param callbacks                server callbacks
   * @param configWatcher            source of configuration updates
   * @param executorGroup            executor group to use for responding stream requests
   * @param protoResourcesSerializer serializer of proto buffer messages
   */
  public DiscoveryServer(List<DiscoveryServerCallbacks> callbacks,
                         ConfigWatcher configWatcher,
                         ExecutorGroup executorGroup,
                         ProtoResourcesSerializer protoResourcesSerializer) {
    Preconditions.checkNotNull(callbacks, "callbacks cannot be null");
    Preconditions.checkNotNull(configWatcher, "configWatcher cannot be null");
    Preconditions.checkNotNull(executorGroup, "executorGroup cannot be null");
    Preconditions.checkNotNull(protoResourcesSerializer, "protoResourcesSerializer cannot be null");

    this.callbacks = callbacks;
    this.configWatcher = configWatcher;
    this.executorGroup = executorGroup;
    this.protoResourcesSerializer = protoResourcesSerializer;
  }

  /**
   * Returns an ADS implementation that uses this server's {@link ConfigWatcher}.
   */
  public AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase getAggregatedDiscoveryServiceImpl() {
    return new AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase() {
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
  public ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceImplBase getClusterDiscoveryServiceImpl() {
    return new ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceImplBase() {
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
  public EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceImplBase getEndpointDiscoveryServiceImpl() {
    return new EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceImplBase() {
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
  public ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceImplBase getListenerDiscoveryServiceImpl() {
    return new ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceImplBase() {
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
  public RouteDiscoveryServiceGrpc.RouteDiscoveryServiceImplBase getRouteDiscoveryServiceImpl() {
    return new RouteDiscoveryServiceGrpc.RouteDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamRoutes(
          StreamObserver<DiscoveryResponse> responseObserver) {

        return createRequestHandler(responseObserver, false, Resources.ROUTE_TYPE_URL);
      }
    };
  }

  /**
   * Returns a SDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase getSecretDiscoveryServiceImpl() {
    return new SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamSecrets(
          StreamObserver<DiscoveryResponse> responseObserver) {
        return createRequestHandler(responseObserver, false, Resources.SECRET_TYPE_URL);
      }
    };
  }

  private StreamObserver<DiscoveryRequest> createRequestHandler(
      StreamObserver<DiscoveryResponse> responseObserver,
      boolean ads,
      String defaultTypeUrl) {

    long streamId = streamCount.getAndIncrement();
    Executor executor = executorGroup.next();

    LOGGER.debug("[{}] open stream from {}", streamId, defaultTypeUrl);

    callbacks.forEach(cb -> cb.onStreamOpen(streamId, defaultTypeUrl));

    final DiscoveryRequestStreamObserver requestStreamObserver;
    if (ads) {
      requestStreamObserver = new AdsDiscoveryRequestStreamObserver(
          responseObserver,
          streamId,
          executor,
          this
      );
    } else {
      requestStreamObserver = new XdsDiscoveryRequestStreamObserver(
          defaultTypeUrl,
          responseObserver,
          streamId,
          executor,
          this
      );
    }

    if (responseObserver instanceof ServerCallStreamObserver) {
      ((ServerCallStreamObserver) responseObserver).setOnCancelHandler(requestStreamObserver::onCancelled);
    }

    return requestStreamObserver;
  }
}
