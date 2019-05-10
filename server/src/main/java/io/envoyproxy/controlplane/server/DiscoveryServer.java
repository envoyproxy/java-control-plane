package io.envoyproxy.controlplane.server;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.controlplane.server.limits.FlowControl;
import io.envoyproxy.controlplane.server.limits.NoOpRequestLimiter;
import io.envoyproxy.controlplane.server.limits.RequestLimiter;
import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer;
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer;
import io.envoyproxy.envoy.api.v2.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceImplBase;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceImplBase;
import io.envoyproxy.envoy.api.v2.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceImplBase;
import io.envoyproxy.envoy.api.v2.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryServer {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);

  static final String ANY_TYPE_URL = "";

  private final List<DiscoveryServerCallbacks> callbacks;
  private final ConfigWatcher configWatcher;
  private final ExecutorGroup executorGroup;
  private final AtomicLong streamCount = new AtomicLong();
  private final ProtoResourcesSerializer protoResourcesSerializer;
  private final RequestLimiter requestLimiter;
  private final FlowControl.Factory<DiscoveryResponse> flowControlFactory;

  public static DiscoveryServer.Builder watching(ConfigWatcher watcher) {
    return new Builder(watcher);
  }

  public DiscoveryServer(ConfigWatcher configWatcher) {
    this(Collections.emptyList(), configWatcher);
  }

  public DiscoveryServer(DiscoveryServerCallbacks callbacks, ConfigWatcher configWatcher) {
    this(Collections.singletonList(callbacks), configWatcher);
  }

  /**
   * Creates the server.
   * @param callbacks server callbacks
   * @param configWatcher source of configuration updates
   */
  public DiscoveryServer(List<DiscoveryServerCallbacks> callbacks, ConfigWatcher configWatcher) {
    this(callbacks, configWatcher, new DefaultExecutorGroup(), new DefaultProtoResourcesSerializer());
  }

  /**
   * Creates the server.
   * @param callbacks server callbacks
   * @param configWatcher source of configuration updates
   * @param executorGroup executor group to use for responding stream requests
   * @param protoResourcesSerializer serializer of proto buffer messages
   */
  public DiscoveryServer(List<DiscoveryServerCallbacks> callbacks,
                         ConfigWatcher configWatcher,
                         ExecutorGroup executorGroup,
                         ProtoResourcesSerializer protoResourcesSerializer) {
    this(callbacks, configWatcher, executorGroup, protoResourcesSerializer,
        FlowControl.noOpFactory(), new NoOpRequestLimiter());
  }

  /**
   * Creates the server.
   * @param callbacks server callbacks
   * @param configWatcher source of configuration updates
   * @param executorGroup executor group to use for responding stream requests
   * @param protoResourcesSerializer serializer of proto buffer messages
   */
  public DiscoveryServer(List<DiscoveryServerCallbacks> callbacks,
                         ConfigWatcher configWatcher,
                         ExecutorGroup executorGroup,
                         ProtoResourcesSerializer protoResourcesSerializer,
                         FlowControl.Factory<DiscoveryResponse> flowControlFactory,
                         RequestLimiter requestLimiter) {
    Preconditions.checkNotNull(callbacks, "callbacks cannot be null");
    Preconditions.checkNotNull(configWatcher, "configWatcher cannot be null");
    Preconditions.checkNotNull(executorGroup, "executorGroup cannot be null");
    Preconditions.checkNotNull(protoResourcesSerializer, "protoResourcesSerializer cannot be null");

    this.callbacks = callbacks;
    this.configWatcher = configWatcher;
    this.executorGroup = executorGroup;
    this.protoResourcesSerializer = protoResourcesSerializer;
    this.flowControlFactory = flowControlFactory;
    this.requestLimiter = requestLimiter;
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

  /**
   * Returns a SDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase getSecretDiscoveryServiceImpl() {
    return new SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase() {
      @Override public StreamObserver<DiscoveryRequest> streamSecrets(
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

    LOGGER.info("[{}] open stream from {}", streamId, defaultTypeUrl);

    callbacks.forEach(cb -> cb.onStreamOpen(streamId, defaultTypeUrl));

    final DiscoveryRequestStreamObserver requestStreamObserver =
        new DiscoveryRequestStreamObserver(defaultTypeUrl, responseObserver, streamId, ads, executor);

    if (responseObserver instanceof ServerCallStreamObserver) {
      ((ServerCallStreamObserver) responseObserver).setOnCancelHandler(requestStreamObserver::onCancelled);
    }

    return requestStreamObserver;
  }

  private class DiscoveryRequestStreamObserver implements StreamObserver<DiscoveryRequest> {

    private final Map<String, Watch> watches;
    private final Map<String, DiscoveryResponse> latestResponse;
    private final Map<String, Set<String>> ackedResources;
    private final String defaultTypeUrl;
    private final StreamObserver<DiscoveryResponse> responseObserver;
    private final long streamId;
    private final boolean ads;
    private final Executor executor;
    private final AtomicBoolean isClosing = new AtomicBoolean();
    private final FlowControl<DiscoveryResponse> flowControl;

    private AtomicLong streamNonce;

    public DiscoveryRequestStreamObserver(
        String defaultTypeUrl,
        StreamObserver<DiscoveryResponse> responseObserver,
        long streamId,
        boolean ads,
        Executor executor) {
      this.defaultTypeUrl = defaultTypeUrl;
      this.responseObserver = responseObserver;
      this.streamId = streamId;
      this.ads = ads;
      watches = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
      latestResponse = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
      ackedResources = new ConcurrentHashMap<>(Resources.TYPE_URLS.size());
      streamNonce = new AtomicLong();
      this.executor = executor;
      this.flowControl = flowControlFactory.get(streamId, responseObserver, requestLimiter);

      flowControl.streamOpened();
    }

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

      try {
        callbacks.forEach(cb -> cb.onStreamRequest(streamId, request));
      } catch (RequestException e) {
        closeWithError(e);
        return;
      }

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

            return configWatcher.createWatch(
                ads,
                request,
                ackedResources.getOrDefault(typeUrl, Collections.emptySet()),
                r -> executor.execute(() -> send(r, typeUrl)));
          });

          break;
        }
      }
      flowControl.afterRequest();
    }

    @Override
    public void onError(Throwable t) {
      if (!Status.fromThrowable(t).getCode().equals(Status.CANCELLED.getCode())) {
        LOGGER.error("[{}] stream closed with error", streamId, t);
      }

      try {
        callbacks.forEach(cb -> cb.onStreamCloseWithError(streamId, defaultTypeUrl, t));
        responseObserver.onError(Status.fromThrowable(t).asException());
      } finally {
        flowControl.streamClosed();
        cancel();
      }
    }

    @Override
    public void onCompleted() {
      LOGGER.info("[{}] stream closed", streamId);

      try {
        callbacks.forEach(cb -> cb.onStreamClose(streamId, defaultTypeUrl));
        responseObserver.onCompleted();
      } finally {
        flowControl.streamClosed();
        cancel();
      }
    }

    void onCancelled() {
      LOGGER.info("[{}] stream cancelled", streamId);
      cancel();
    }

    private void closeWithError(Throwable exception) {
      if (isClosing.compareAndSet(false, true)) {
        responseObserver.onError(exception);
      }
      cancel();
    }

    private void cancel() {
      watches.values().forEach(Watch::cancel);
    }

    private void send(Response response, String typeUrl) {
      String nonce = Long.toString(streamNonce.getAndIncrement());

      Collection<Any> resources = protoResourcesSerializer.serialize(response.resources());
      DiscoveryResponse discoveryResponse = DiscoveryResponse.newBuilder()
          .setVersionInfo(response.version())
          .addAllResources(resources)
          .setTypeUrl(typeUrl)
          .setNonce(nonce)
          .build();

      LOGGER.info("[{}] response {} with nonce {} version {}", streamId, typeUrl, nonce, response.version());

      callbacks.forEach(cb -> cb.onStreamResponse(streamId, response.request(), discoveryResponse));

      // Store the latest response *before* we send the response. This ensures that by the time the request
      // is processed the map is guaranteed to be updated. Doing it afterwards leads to a race conditions
      // which may see the incoming request arrive before the map is updated, failing the nonce check erroneously.
      latestResponse.put(typeUrl, discoveryResponse);
      try {
        responseObserver.onNext(discoveryResponse);
      } catch (StatusRuntimeException e) {
        if (!Status.CANCELLED.getCode().equals(e.getStatus().getCode())) {
          throw e;
        }
      }
    }
  }

  /**
   * Builder for creating a {@link DiscoveryServer} instance.
   */
  public static class Builder {

    private final ConfigWatcher configWatcher;
    private List<DiscoveryServerCallbacks> callbacks = Collections.emptyList();
    private ExecutorGroup executorGroup = new DefaultExecutorGroup();
    private ProtoResourcesSerializer protoResourcesSerializer = new DefaultProtoResourcesSerializer();
    private RequestLimiter requestLimiter = new NoOpRequestLimiter();
    private FlowControl.Factory<DiscoveryResponse> flowControlFactory = FlowControl.noOpFactory();

    private Builder(ConfigWatcher configWatcher) {
      this.configWatcher = configWatcher;
    }

    /**
     * Creates an instance of {@link DiscoveryServer} based on provided configuration merged with defaults.
     * @return instance of {@link DiscoveryServer}
     */
    public DiscoveryServer build() {
      return new DiscoveryServer(
          this.callbacks,
          this.configWatcher,
          this.executorGroup,
          this.protoResourcesSerializer,
          this.flowControlFactory,
          this.requestLimiter
      );
    }

    /**
     * Use provided server callbacks.
     * @param callbacks list of {@link DiscoveryServerCallbacks}
     * @return the builder instance
     */
    public Builder withCallbacks(List<DiscoveryServerCallbacks> callbacks) {
      Preconditions.checkNotNull(callbacks, "callbacks cannot be null");
      this.callbacks = callbacks;
      return this;
    }

    /**
     * Use provided executor group.
     * @param group {@link ExecutorGroup} to be used
     * @return the builder instance
     */
    public Builder withExecutorGroup(ExecutorGroup group) {
      Preconditions.checkNotNull(group, "ExecutorGroup cannot be null");
      this.executorGroup = group;
      return this;
    }

    /**
     * Use provided serializer.
     * @param serializer {@link ProtoResourcesSerializer} to be used
     * @return the builder instance
     */
    public Builder withProtoResourcesSerializer(ProtoResourcesSerializer serializer) {
      Preconditions.checkNotNull(serializer, "ProtoResourcesSerializer cannot be null");
      this.protoResourcesSerializer = serializer;
      return this;
    }

    /**
     * Use provided request limiter.
     * @param limiter {@link RequestLimiter} to be used
     * @return the builder instance
     */
    public Builder withRequestLimiter(RequestLimiter limiter) {
      Preconditions.checkNotNull(limiter, "RequestLimiter cannot be null");
      this.requestLimiter = limiter;
      return this;
    }

    /**
     * Use provided FlowControl factory.
     * @param factory {@link FlowControl.Factory} to be used
     * @return the builder instance
     */
    public Builder withFlowControlFactory(FlowControl.Factory<DiscoveryResponse> factory) {
      Preconditions.checkNotNull(factory, "FlowControl.Factory cannot be null");
      this.flowControlFactory = factory;
      return this;
    }
  }
}
