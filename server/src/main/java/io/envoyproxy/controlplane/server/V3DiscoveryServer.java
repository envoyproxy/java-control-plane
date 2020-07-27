package io.envoyproxy.controlplane.server;

import static io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import static io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceImplBase;
import static io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceImplBase;
import static io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceImplBase;
import static io.envoyproxy.envoy.service.secret.v3.SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.server.serializer.DefaultProtoResourcesSerializer;
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class V3DiscoveryServer extends DiscoveryServer<DiscoveryRequest, DiscoveryResponse>  {
  public V3DiscoveryServer(ConfigWatcher configWatcher) {
    this(Collections.emptyList(), configWatcher);
  }

  public V3DiscoveryServer(DiscoveryServerCallbacks callbacks,
      ConfigWatcher configWatcher) {
    this(Collections.singletonList(callbacks), configWatcher);
  }

  public V3DiscoveryServer(
      List<DiscoveryServerCallbacks> callbacks,
      ConfigWatcher configWatcher) {
    this(callbacks, configWatcher, new DefaultExecutorGroup(),
        new DefaultProtoResourcesSerializer());
  }

  public V3DiscoveryServer(List<DiscoveryServerCallbacks> callbacks,
      ConfigWatcher configWatcher, ExecutorGroup executorGroup, ProtoResourcesSerializer protoResourcesSerializer) {
    super(callbacks, configWatcher, executorGroup, protoResourcesSerializer);
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

        return createRequestHandler(responseObserver, false, Resources.V3_CLUSTER_TYPE_URL);
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

        return createRequestHandler(responseObserver, false, Resources.V3_ENDPOINT_TYPE_URL);
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

        return createRequestHandler(responseObserver, false, Resources.V3_LISTENER_TYPE_URL);
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

        return createRequestHandler(responseObserver, false, Resources.V3_ROUTE_TYPE_URL);
      }
    };
  }

  /**
   * Returns a SDS implementation that uses this server's {@link ConfigWatcher}.
   */
  public SecretDiscoveryServiceImplBase getSecretDiscoveryServiceImpl() {
    return new SecretDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamSecrets(
          StreamObserver<DiscoveryResponse> responseObserver) {
        return createRequestHandler(responseObserver, false, Resources.V3_SECRET_TYPE_URL);
      }
    };
  }

  @Override
  protected XdsRequest wrapXdsRequest(DiscoveryRequest request) {
    return XdsRequest.create(request);
  }

  @Override
  protected void runStreamRequestCallbacks(long streamId, DiscoveryRequest discoveryRequest) {
    callbacks.forEach(
        cb -> cb.onV3StreamRequest(streamId, discoveryRequest));
  }

  @Override
  protected void runStreamResponseCallbacks(long streamId, XdsRequest request,
      DiscoveryResponse discoveryResponse) {
    Preconditions.checkArgument(request.v3Request() != null);
    callbacks.forEach(
        cb -> cb.onV3StreamResponse(streamId,
            request.v3Request(),
            discoveryResponse));
  }

  @Override
  protected DiscoveryResponse makeResponse(String version, Collection<Any> resources,
      String typeUrl,
      String nonce) {
    return DiscoveryResponse.newBuilder()
        .setNonce(nonce)
        .setVersionInfo(version)
        .addAllResources(resources)
        .setTypeUrl(typeUrl)
        .build();
  }
}
