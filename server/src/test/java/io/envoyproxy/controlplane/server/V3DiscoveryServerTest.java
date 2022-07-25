package io.envoyproxy.controlplane.server;

import static io.envoyproxy.envoy.config.core.v3.ApiVersion.V3;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.DeltaResponse;
import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.DeltaXdsRequest;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.WatchCancelledException;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.cluster.v3.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.endpoint.v3.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceStub;
import io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.listener.v3.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceStub;
import io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.route.v3.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceStub;
import io.envoyproxy.envoy.service.secret.v3.SecretDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.secret.v3.SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcServerRule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.assertj.core.api.Condition;
import org.junit.Rule;
import org.junit.Test;

public class V3DiscoveryServerTest {

  private static final boolean ADS = ThreadLocalRandom.current().nextBoolean();

  private static final String CLUSTER_NAME  = "cluster0";
  private static final String LISTENER_NAME = "listener0";
  private static final String ROUTE_NAME    = "route0";
  private static final String SECRET_NAME   = "secret0";

  private static final int ENDPOINT_PORT = Ports.getAvailablePort();
  private static final int LISTENER_PORT = Ports.getAvailablePort();

  private static final Node NODE = Node.newBuilder()
      .setId("test-id")
      .setCluster("test-cluster")
      .build();

  private static final String VERSION = Integer.toString(ThreadLocalRandom.current().nextInt(1, 1000));

  private static final Cluster CLUSTER = TestResources.createCluster(CLUSTER_NAME);
  private static final ClusterLoadAssignment
      ENDPOINT = TestResources.createEndpoint(CLUSTER_NAME, ENDPOINT_PORT);
  private static final Listener
      LISTENER = TestResources.createListener(ADS, false, V3, V3, LISTENER_NAME, LISTENER_PORT,
      ROUTE_NAME);
  private static final RouteConfiguration ROUTE = TestResources.createRoute(ROUTE_NAME,
      CLUSTER_NAME);
  private static final Secret SECRET = TestResources.createSecret(SECRET_NAME);

  @Rule
  public final GrpcServerRule grpcServer = new GrpcServerRule().directExecutor();

  @Test
  public void testAggregatedHandler() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.LISTENER_TYPE_URL)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.ENDPOINT_TYPE_URL)
        .addResourceNames(CLUSTER_NAME)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.ROUTE_TYPE_URL)
        .addResourceNames(ROUTE_NAME)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.SECRET_TYPE_URL)
        .addResourceNames(SECRET_NAME)
        .build());

    requestObserver.onCompleted();

    if (!responseObserver.completedLatch.await(1, TimeUnit.SECONDS) || responseObserver.error.get()) {
      fail(format("failed to complete request before timeout, error = %b", responseObserver.error.get()));
    }

    responseObserver.assertThatNoErrors();

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      assertThat(configWatcher.counts).containsEntry(typeUrl, 1);
    }

    assertThat(configWatcher.counts).hasSize(Resources.V3.TYPE_URLS.size());

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      assertThat(responseObserver.responses).haveAtLeastOne(new Condition<>(
          r -> r.getTypeUrl().equals(typeUrl) && r.getVersionInfo().equals(VERSION),
          "missing expected response of type %s", typeUrl));
    }
  }

  @Test
  public void testSeparateHandlers() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getClusterDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getEndpointDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getListenerDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getRouteDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getSecretDiscoveryServiceImpl());

    ClusterDiscoveryServiceStub clusterStub  = ClusterDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    EndpointDiscoveryServiceStub endpointStub = EndpointDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    ListenerDiscoveryServiceStub listenerStub = ListenerDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    RouteDiscoveryServiceStub routeStub    = RouteDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    SecretDiscoveryServiceStub secretStub   = SecretDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = null;
      DiscoveryRequest.Builder discoveryRequestBuilder = DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .setTypeUrl(typeUrl);

      switch (typeUrl) {
        case Resources.V3.CLUSTER_TYPE_URL:
          requestObserver = clusterStub.streamClusters(responseObserver);
          break;
        case Resources.V3.ENDPOINT_TYPE_URL:
          requestObserver = endpointStub.streamEndpoints(responseObserver);
          discoveryRequestBuilder.addResourceNames(CLUSTER_NAME);
          break;
        case Resources.V3.LISTENER_TYPE_URL:
          requestObserver = listenerStub.streamListeners(responseObserver);
          break;
        case Resources.V3.ROUTE_TYPE_URL:
          requestObserver = routeStub.streamRoutes(responseObserver);
          discoveryRequestBuilder.addResourceNames(ROUTE_NAME);
          break;
        case Resources.V3.SECRET_TYPE_URL:
          requestObserver = secretStub.streamSecrets(responseObserver);
          discoveryRequestBuilder.addResourceNames(SECRET_NAME);
          break;
        default:
          fail("Unsupported resource type: " + typeUrl);
      }

      requestObserver.onNext(discoveryRequestBuilder.build());
      requestObserver.onCompleted();

      if (!responseObserver.completedLatch.await(1, TimeUnit.SECONDS) || responseObserver.error.get()) {
        fail(format("failed to complete request before timeout, error = %b", responseObserver.error.get()));
      }

      responseObserver.assertThatNoErrors();

      assertThat(configWatcher.counts).containsEntry(typeUrl, 1);
      assertThat(responseObserver.responses).haveAtLeastOne(new Condition<>(
          r -> r.getTypeUrl().equals(typeUrl) && r.getVersionInfo().equals(VERSION),
          "missing expected response of type %s", typeUrl));
    }

    assertThat(configWatcher.counts).hasSize(Resources.V3.TYPE_URLS.size());
  }

  @Test
  public void testWatchClosed() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(true, ImmutableTable.of());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.V3.TYPE_URLS) {

      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

      requestObserver.onNext(DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .setTypeUrl(typeUrl)
          .build());

      requestObserver.onError(new RuntimeException("send error"));

      if (!responseObserver.errorLatch.await(1, TimeUnit.SECONDS)
          || responseObserver.completed.get()
          || !responseObserver.responses.isEmpty()) {
        fail(format("failed to error before timeout, completed = %b, responses.count = %d",
            responseObserver.completed.get(),
            responseObserver.responses.size()));
      }

      responseObserver.assertThatNoErrors();
    }
  }

  @Test
  public void testSendError() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();
      responseObserver.sendError = true;

      StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

      requestObserver.onNext(DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .setTypeUrl(typeUrl)
          .build());

      if (!responseObserver.errorLatch.await(1, TimeUnit.SECONDS) || responseObserver.completed.get()) {
        fail(format("failed to error before timeout, completed = %b", responseObserver.completed.get()));
      }

      responseObserver.assertThatNoErrors();
    }
  }

  @Test
  public void testStaleNonce() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

      requestObserver.onNext(DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .setTypeUrl(typeUrl)
          .build());

      // Stale request, should not create a new watch.
      requestObserver.onNext(
          DiscoveryRequest.newBuilder()
              .setNode(NODE)
              .setTypeUrl(typeUrl)
              .setResponseNonce("xyz")
              .build());

      // Fresh request, should create a new watch.
      requestObserver.onNext(
          DiscoveryRequest.newBuilder()
              .setNode(NODE)
              .setTypeUrl(typeUrl)
              .setResponseNonce("0")
              .setVersionInfo("0")
              .build());

      requestObserver.onCompleted();

      if (!responseObserver.completedLatch.await(1, TimeUnit.SECONDS) || responseObserver.error.get()) {
        fail(format("failed to complete request before timeout, error = %b", responseObserver.error.get()));
      }

      // Assert that 2 watches have been created for this resource type.
      assertThat(configWatcher.counts.get(typeUrl)).isEqualTo(2);
    }
  }

  @Test
  public void testAggregateHandlerDefaultRequestType() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(true, ImmutableTable.of());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    // Leave off the type URL. For ADS requests it should fail because the type URL is required.
    requestObserver.onNext(
        DiscoveryRequest.newBuilder()
            .setNode(NODE)
            .build());

    requestObserver.onCompleted();

    if (!responseObserver.errorLatch.await(1, TimeUnit.SECONDS) || responseObserver.completed.get()) {
      fail(format("failed to error before timeout, completed = %b", responseObserver.completed.get()));
    }
  }

  @Test
  public void testSeparateHandlersDefaultRequestType() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getClusterDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getEndpointDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getListenerDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getRouteDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getSecretDiscoveryServiceImpl());

    ClusterDiscoveryServiceStub  clusterStub  = ClusterDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    EndpointDiscoveryServiceStub endpointStub = EndpointDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    ListenerDiscoveryServiceStub listenerStub = ListenerDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    RouteDiscoveryServiceStub    routeStub    = RouteDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    SecretDiscoveryServiceStub   secretStub   = SecretDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = null;

      switch (typeUrl) {
        case Resources.V3.CLUSTER_TYPE_URL:
          requestObserver = clusterStub.streamClusters(responseObserver);
          break;
        case Resources.V3.ENDPOINT_TYPE_URL:
          requestObserver = endpointStub.streamEndpoints(responseObserver);
          break;
        case Resources.V3.LISTENER_TYPE_URL:
          requestObserver = listenerStub.streamListeners(responseObserver);
          break;
        case Resources.V3.ROUTE_TYPE_URL:
          requestObserver = routeStub.streamRoutes(responseObserver);
          break;
        case Resources.V3.SECRET_TYPE_URL:
          requestObserver = secretStub.streamSecrets(responseObserver);
          break;
        default:
          fail("Unsupported resource type: " + typeUrl);
      }

      // Leave off the type URL. For xDS requests it should default to the value for that handler's type.
      DiscoveryRequest discoveryRequest = DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .build();

      requestObserver.onNext(discoveryRequest);
      requestObserver.onCompleted();

      if (!responseObserver.completedLatch.await(1, TimeUnit.SECONDS) || responseObserver.error.get()) {
        fail(format("failed to complete request before timeout, error = %b", responseObserver.error.get()));
      }

      responseObserver.assertThatNoErrors();
    }
  }

  @Test
  public void testCallbacksAggregateHandler() throws InterruptedException {
    final CountDownLatch streamCloseLatch = new CountDownLatch(1);
    final CountDownLatch streamOpenLatch = new CountDownLatch(1);
    final AtomicReference<CountDownLatch> streamRequestLatch =
        new AtomicReference<>(new CountDownLatch(Resources.V3.TYPE_URLS.size()));
    final AtomicReference<CountDownLatch> streamResponseLatch =
        new AtomicReference<>(new CountDownLatch(Resources.V3.TYPE_URLS.size()));

    MockDiscoveryServerCallbacks callbacks = new MockDiscoveryServerCallbacks() {
      @Override
      public void onStreamClose(long streamId, String typeUrl) {
        super.onStreamClose(streamId, typeUrl);

        if (!typeUrl.equals(DiscoveryServer.ANY_TYPE_URL)) {
          this.assertionErrors.add(format(
              "onStreamClose#typeUrl => expected %s, got %s",
              DiscoveryServer.ANY_TYPE_URL,
              typeUrl));
        }

        streamCloseLatch.countDown();
      }

      @Override
      public void onStreamOpen(long streamId, String typeUrl) {
        super.onStreamOpen(streamId, typeUrl);

        if (!typeUrl.equals(DiscoveryServer.ANY_TYPE_URL)) {
          this.assertionErrors.add(format(
              "onStreamOpen#typeUrl => expected %s, got %s",
              DiscoveryServer.ANY_TYPE_URL,
              typeUrl));
        }

        streamOpenLatch.countDown();
      }

      @Override
      public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
        super.onV3StreamRequest(streamId, request);
        streamRequestLatch.get().countDown();
      }

      @Override
      public void onV3StreamResponse(long streamId, DiscoveryRequest request,
          DiscoveryResponse response) {
        super.onV3StreamResponse(streamId, request, response);

        streamResponseLatch.get().countDown();
      }
    };

    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(callbacks, configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.LISTENER_TYPE_URL)
        .build());

    if (!streamOpenLatch.await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamOpen callback before timeout");
    }

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.ENDPOINT_TYPE_URL)
        .addResourceNames(CLUSTER_NAME)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.ROUTE_TYPE_URL)
        .addResourceNames(ROUTE_NAME)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.SECRET_TYPE_URL)
        .addResourceNames(SECRET_NAME)
        .build());

    if (!streamRequestLatch.get().await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamRequest callback before timeout");
    }

    if (!streamResponseLatch.get().await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamResponse callback before timeout");
    }

    // Send another round of requests. These should not trigger any responses.
    streamResponseLatch.set(new CountDownLatch(1));
    streamRequestLatch.set(new CountDownLatch(Resources.V3.TYPE_URLS.size()));

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setResponseNonce("0")
        .setVersionInfo(VERSION)
        .setTypeUrl(Resources.V3.LISTENER_TYPE_URL)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setResponseNonce("1")
        .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
        .setVersionInfo(VERSION)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setResponseNonce("2")
        .setTypeUrl(Resources.V3.ENDPOINT_TYPE_URL)
        .addResourceNames(CLUSTER_NAME)
        .setVersionInfo(VERSION)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setResponseNonce("3")
        .setTypeUrl(Resources.V3.ROUTE_TYPE_URL)
        .addResourceNames(ROUTE_NAME)
        .setVersionInfo(VERSION)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setResponseNonce("4")
        .setTypeUrl(Resources.V3.SECRET_TYPE_URL)
        .addResourceNames(SECRET_NAME)
        .setVersionInfo(VERSION)
        .build());

    if (!streamRequestLatch.get().await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamRequest callback before timeout");
    }

    if (streamResponseLatch.get().await(1, TimeUnit.SECONDS)) {
      fail("unexpected onStreamResponse callback");
    }

    requestObserver.onCompleted();

    if (!streamCloseLatch.await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamClose callback before timeout");
    }

    callbacks.assertThatNoErrors();

    assertThat(callbacks.streamCloseCount).hasValue(1);
    assertThat(callbacks.streamCloseWithErrorCount).hasValue(0);
    assertThat(callbacks.streamOpenCount).hasValue(1);
    assertThat(callbacks.streamRequestCount).hasValue(Resources.V3.TYPE_URLS.size() * 2);
    assertThat(callbacks.streamResponseCount).hasValue(Resources.V3.TYPE_URLS.size());
  }

  @Test
  public void testCallbacksSeparateHandlers() throws InterruptedException {
    final Map<String, CountDownLatch> streamCloseLatches = new ConcurrentHashMap<>();
    final Map<String, CountDownLatch> streamOpenLatches = new ConcurrentHashMap<>();
    final Map<String, CountDownLatch> streamRequestLatches = new ConcurrentHashMap<>();
    final Map<String, CountDownLatch> streamResponseLatches = new ConcurrentHashMap<>();

    Resources.V3.TYPE_URLS.forEach(typeUrl -> {
      streamCloseLatches.put(typeUrl, new CountDownLatch(1));
      streamOpenLatches.put(typeUrl, new CountDownLatch(1));
      streamRequestLatches.put(typeUrl, new CountDownLatch(1));
      streamResponseLatches.put(typeUrl, new CountDownLatch(1));
    });

    MockDiscoveryServerCallbacks callbacks = new MockDiscoveryServerCallbacks() {

      @Override
      public void onStreamClose(long streamId, String typeUrl) {
        super.onStreamClose(streamId, typeUrl);

        if (!Resources.V3.TYPE_URLS.contains(typeUrl)) {
          this.assertionErrors.add(format(
              "onStreamClose#typeUrl => expected one of [%s], got %s",
              String.join(",", Resources.V3.TYPE_URLS),
              typeUrl));
        }

        streamCloseLatches.get(typeUrl).countDown();
      }

      @Override
      public void onStreamOpen(long streamId, String typeUrl) {
        super.onStreamOpen(streamId, typeUrl);

        if (!Resources.V3.TYPE_URLS.contains(typeUrl)) {
          this.assertionErrors.add(format(
              "onStreamOpen#typeUrl => expected one of [%s], got %s",
              String.join(",", Resources.V3.TYPE_URLS),
              typeUrl));
        }

        streamOpenLatches.get(typeUrl).countDown();
      }

      @Override
      public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
        super.onV3StreamRequest(streamId, request);

        streamRequestLatches.get(request.getTypeUrl()).countDown();
      }

      @Override
      public void onV3StreamResponse(long streamId, DiscoveryRequest request,
          DiscoveryResponse response) {
        super.onV3StreamResponse(streamId, request, response);

        streamResponseLatches.get(request.getTypeUrl()).countDown();
      }
    };

    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(callbacks, configWatcher);

    grpcServer.getServiceRegistry().addService(server.getClusterDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getEndpointDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getListenerDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getRouteDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getSecretDiscoveryServiceImpl());

    ClusterDiscoveryServiceStub  clusterStub  = ClusterDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    EndpointDiscoveryServiceStub endpointStub = EndpointDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    ListenerDiscoveryServiceStub listenerStub = ListenerDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    RouteDiscoveryServiceStub    routeStub    = RouteDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    SecretDiscoveryServiceStub   secretStub    = SecretDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.V3.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = null;

      switch (typeUrl) {
        case Resources.V3.CLUSTER_TYPE_URL:
          requestObserver = clusterStub.streamClusters(responseObserver);
          break;
        case Resources.V3.ENDPOINT_TYPE_URL:
          requestObserver = endpointStub.streamEndpoints(responseObserver);
          break;
        case Resources.V3.LISTENER_TYPE_URL:
          requestObserver = listenerStub.streamListeners(responseObserver);
          break;
        case Resources.V3.ROUTE_TYPE_URL:
          requestObserver = routeStub.streamRoutes(responseObserver);
          break;
        case Resources.V3.SECRET_TYPE_URL:
          requestObserver = secretStub.streamSecrets(responseObserver);
          break;
        default:
          fail("Unsupported resource type: " + typeUrl);
      }

      DiscoveryRequest discoveryRequest = DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .setTypeUrl(typeUrl)
          .build();

      requestObserver.onNext(discoveryRequest);

      if (!streamOpenLatches.get(typeUrl).await(1, TimeUnit.SECONDS)) {
        fail(format("failed to execute onStreamOpen callback for typeUrl %s before timeout", typeUrl));
      }

      if (!streamRequestLatches.get(typeUrl).await(1, TimeUnit.SECONDS)) {
        fail(format("failed to execute onStreamOpen callback for typeUrl %s before timeout", typeUrl));
      }

      requestObserver.onCompleted();

      if (!streamResponseLatches.get(typeUrl).await(1, TimeUnit.SECONDS)) {
        fail(format("failed to execute onStreamResponse callback for typeUrl %s before timeout", typeUrl));
      }

      if (!streamCloseLatches.get(typeUrl).await(1, TimeUnit.SECONDS)) {
        fail(format("failed to execute onStreamClose callback for typeUrl %s before timeout", typeUrl));
      }
    }

    callbacks.assertThatNoErrors();

    assertThat(callbacks.streamCloseCount).hasValue(5);
    assertThat(callbacks.streamCloseWithErrorCount).hasValue(0);
    assertThat(callbacks.streamOpenCount).hasValue(5);
    assertThat(callbacks.streamRequestCount).hasValue(5);
    assertThat(callbacks.streamResponseCount).hasValue(5);
  }

  @Test
  public void testCallbacksOnError() throws InterruptedException {
    final CountDownLatch streamCloseWithErrorLatch = new CountDownLatch(1);

    MockDiscoveryServerCallbacks callbacks = new MockDiscoveryServerCallbacks() {
      @Override
      public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
        super.onStreamCloseWithError(streamId, typeUrl, error);

        streamCloseWithErrorLatch.countDown();
      }
    };

    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(callbacks, configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    requestObserver.onError(new RuntimeException("send error"));

    if (!streamCloseWithErrorLatch.await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamCloseWithError callback before timeout");
    }

    callbacks.assertThatNoErrors();

    assertThat(callbacks.streamCloseCount).hasValue(0);
    assertThat(callbacks.streamCloseWithErrorCount).hasValue(1);
    assertThat(callbacks.streamOpenCount).hasValue(1);
    assertThat(callbacks.streamRequestCount).hasValue(0);
    assertThat(callbacks.streamResponseCount).hasValue(0);
  }

  @Test
  public void callbackOnError_logsError_onException() {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase service =
        server.getAggregatedDiscoveryServiceImpl();

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();
    StreamObserver<DiscoveryRequest> requestObserver = service.streamAggregatedResources(responseObserver);

    try {
      ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
      System.setErr(new PrintStream(stdErr));

      requestObserver.onError(new StatusRuntimeException(Status.INTERNAL
          .withDescription("internal error")
          .withCause(new RuntimeException("some error"))));

      assertThat(stdErr.toString()).contains("ERROR ");
      assertThat(stdErr.toString()).contains("io.grpc.StatusRuntimeException: INTERNAL: internal error");
    } finally {
      System.setErr(System.err);
    }
  }

  @Test
  public void callbackOnError_doesNotLogError_whenCancelled() {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(configWatcher);

    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase service =
        server.getAggregatedDiscoveryServiceImpl();

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();
    StreamObserver<DiscoveryRequest> requestObserver = service.streamAggregatedResources(responseObserver);

    try {
      ByteArrayOutputStream stdErr = new ByteArrayOutputStream();
      System.setErr(new PrintStream(stdErr));

      requestObserver.onError(new StatusRuntimeException(Status.CANCELLED
          .withDescription("internal error")
          .withCause(new RuntimeException("some error"))));

      assertThat(stdErr.toString()).doesNotContain("ERROR ");
      assertThat(stdErr.toString()).doesNotContain("io.grpc.StatusRuntimeException: CANCELLED:");
    } finally {
      System.setErr(System.err);
    }
  }

  @Test
  public void testCallbacksOnCancelled() throws InterruptedException, ClassNotFoundException {
    final CountDownLatch streamCloseWithErrorLatch = new CountDownLatch(1);
    final CountDownLatch watchCreated = new CountDownLatch(1);
    AtomicReference<Watch> watch = new AtomicReference<>();

    MockDiscoveryServerCallbacks callbacks = new MockDiscoveryServerCallbacks() {
      @Override
      public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
        // watch should already be closed by the time we report a stream close error
        assertThat(watch.get().isCancelled()).isTrue();
        super.onStreamCloseWithError(streamId, typeUrl, error);
        streamCloseWithErrorLatch.countDown();
      }
    };

    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses()) {
      @Override
      public Watch createWatch(boolean ads, XdsRequest request, Set<String> knownResources,
                               Consumer<Response> responseConsumer, boolean hasClusterChanged) {
        watchCreated.countDown();
        watch.set(super.createWatch(ads, request, knownResources, responseConsumer, false));
        return watch.get();
      }
    };
    V3DiscoveryServer server = new V3DiscoveryServer(callbacks, configWatcher);

    grpcServer.getServiceRegistry().addService(server.getClusterDiscoveryServiceImpl());

    ClusterDiscoveryServiceStub stub = ClusterDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

    StreamObserver<DiscoveryRequest> requestObserver = stub.streamClusters(responseObserver);

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setResponseNonce("1")
        .setTypeUrl(Resources.V3.CLUSTER_TYPE_URL)
        .setVersionInfo(VERSION)
        .build());

    if (!watchCreated.await(1, TimeUnit.SECONDS)) {
      fail("failed to execute watchCreated callback before timeout");
    }

    requestObserver.onError(Status.CANCELLED.asException());

    if (!streamCloseWithErrorLatch.await(1, TimeUnit.SECONDS)) {
      fail("failed to execute onStreamCloseWithError callback before timeout");
    }

    callbacks.assertThatNoErrors();

    assertThat(callbacks.streamCloseCount).hasValue(0);
    assertThat(callbacks.streamCloseWithErrorCount).hasValue(1);
    assertThat(callbacks.streamOpenCount).hasValue(1);
    assertThat(callbacks.streamRequestCount).hasValue(1);
    assertThat(callbacks.streamResponseCount).hasValue(1);
  }

  @Test
  public void testCallbacksRequestException() throws InterruptedException {
    MockDiscoveryServerCallbacks callbacks = new MockDiscoveryServerCallbacks() {
      @Override
      public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
        super.onV3StreamRequest(streamId, request);
        throw new RequestException(Status.INVALID_ARGUMENT.withDescription("request not valid"));
      }
    };

    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(callbacks, configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());
    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();
    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.LISTENER_TYPE_URL)
        .build());

    if (!responseObserver.errorLatch.await(1, TimeUnit.SECONDS) || responseObserver.completed.get()) {
      fail(format("failed to error before timeout, completed = %b", responseObserver.completed.get()));
    }

    callbacks.assertThatNoErrors();

    assertThat(responseObserver.errorException).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
      assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(ex.getStatus().getDescription()).isEqualTo("request not valid");
    });

    assertThat(callbacks.streamCloseCount).hasValue(0);
    assertThat(callbacks.streamCloseWithErrorCount).hasValue(0);
    assertThat(callbacks.streamOpenCount).hasValue(1);
    assertThat(callbacks.streamRequestCount).hasValue(1);
    assertThat(callbacks.streamResponseCount).hasValue(0);
  }

  @Test
  public void testCallbacksOtherStatusException() throws InterruptedException {
    MockDiscoveryServerCallbacks callbacks = new MockDiscoveryServerCallbacks() {
      @Override
      public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
        super.onV3StreamRequest(streamId, request);
        throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("request not valid"));
      }
    };

    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    V3DiscoveryServer server = new V3DiscoveryServer(callbacks, configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());
    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();
    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.V3.LISTENER_TYPE_URL)
        .build());

    if (!responseObserver.errorLatch.await(1, TimeUnit.SECONDS) || responseObserver.completed.get()) {
      fail(format("failed to error before timeout, completed = %b", responseObserver.completed.get()));
    }

    callbacks.assertThatNoErrors();

    assertThat(responseObserver.errorException).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
      assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
      assertThat(ex.getStatus().getDescription()).isNull();
    });

    assertThat(callbacks.streamCloseCount).hasValue(0);
    assertThat(callbacks.streamCloseWithErrorCount).hasValue(0);
    assertThat(callbacks.streamOpenCount).hasValue(1);
    assertThat(callbacks.streamRequestCount).hasValue(1);
    assertThat(callbacks.streamResponseCount).hasValue(0);
  }

  private static Table<String, String, Collection<? extends Message>> createResponses() {
    return ImmutableTable.<String, String, Collection<? extends Message>>builder()
        .put(Resources.V3.CLUSTER_TYPE_URL, VERSION, ImmutableList.of(CLUSTER))
        .put(Resources.V3.ENDPOINT_TYPE_URL, VERSION, ImmutableList.of(ENDPOINT))
        .put(Resources.V3.LISTENER_TYPE_URL, VERSION, ImmutableList.of(LISTENER))
        .put(Resources.V3.ROUTE_TYPE_URL, VERSION, ImmutableList.of(ROUTE))
        .put(Resources.V3.SECRET_TYPE_URL, VERSION, ImmutableList.of(SECRET))
        .build();
  }

  private static class MockConfigWatcher implements ConfigWatcher {

    private final boolean closeWatch;
    private final Map<String, Integer> counts;
    private final Table<String, String, Collection<? extends Message>> responses;
    private final Map<String, Set<String>> expectedKnownResources = new ConcurrentHashMap<>();

    MockConfigWatcher(boolean closeWatch, Table<String, String, Collection<? extends Message>> responses) {
      this.closeWatch = closeWatch;
      this.counts = new HashMap<>();
      this.responses = HashBasedTable.create(responses);
    }

    @Override
    public Watch createWatch(
        boolean ads,
        XdsRequest request,
        Set<String> knownResourceNames,
        Consumer<Response> responseConsumer,
        boolean hasClusterChanged) {

      counts.put(request.getTypeUrl(), counts.getOrDefault(request.getTypeUrl(), 0) + 1);

      Watch watch = new Watch(ads, request, responseConsumer);

      if (responses.row(request.getTypeUrl()).size() > 0) {
        final Response response;

        synchronized (responses) {
          String version = responses.row(request.getTypeUrl()).keySet().iterator().next();
          Collection<? extends Message> resources = responses.row(request.getTypeUrl()).remove(version);
          response = Response.create(request, resources, version);
        }

        expectedKnownResources.put(
            request.getTypeUrl(),
            response.resources().stream()
                .map(Resources::getResourceName)
                .collect(Collectors.toSet()));

        try {
          watch.respond(response);
        } catch (WatchCancelledException e) {
          fail("watch should not be cancelled", e);
        }
      } else if (closeWatch) {
        watch.cancel();
      } else {
        Set<String> expectedKnown = expectedKnownResources.get(request.getTypeUrl());
        if (expectedKnown != null && !expectedKnown.equals(knownResourceNames)) {
          fail("unexpected known resources after sending all responses");
        }
      }

      return watch;
    }

    @Override
    public DeltaWatch createDeltaWatch(DeltaXdsRequest request, String requesterVersion,
                                       Map<String, String> resourceVersions,
                                       Set<String> pendingResources,
                                       boolean isWildcard,
                                       Consumer<DeltaResponse> responseConsumer,
                                       boolean hasClusterChanged) {
      throw new IllegalStateException("not implemented");
    }
  }

  private static class MockDiscoveryServerCallbacks
      implements DiscoveryServerCallbacks {

    private final AtomicInteger streamCloseCount = new AtomicInteger();
    private final AtomicInteger streamCloseWithErrorCount = new AtomicInteger();
    private final AtomicInteger streamOpenCount = new AtomicInteger();
    private final AtomicInteger streamRequestCount = new AtomicInteger();
    private final AtomicInteger streamResponseCount = new AtomicInteger();

    final Collection<String> assertionErrors = new LinkedList<>();

    @Override
    public void onStreamClose(long streamId, String typeUrl) {
      streamCloseCount.getAndIncrement();
    }

    @Override
    public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
      streamCloseWithErrorCount.getAndIncrement();
    }

    @Override
    public void onStreamOpen(long streamId, String typeUrl) {
      streamOpenCount.getAndIncrement();
    }

    @Override
    public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
      streamRequestCount.getAndIncrement();

      if (request == null) {
        this.assertionErrors.add("onStreamRequest#request => expected not null");
      } else if (!request.getNode().equals(NODE)) {
        this.assertionErrors.add(format(
            "onStreamRequest#request => expected node = %s, got %s",
            NODE,
            request.getNode()));
      }
    }

    @Override
    public void onV3StreamDeltaRequest(long streamId,
                                       DeltaDiscoveryRequest request) {
      throw new IllegalStateException("Unexpected delta request");
    }

    @Override
    public void onV3StreamResponse(long streamId, DiscoveryRequest request,
        DiscoveryResponse response) {
      streamResponseCount.getAndIncrement();

      if (request == null) {
        this.assertionErrors.add("onStreamResponse#request => expected not null");
      } else if (!request.getNode().equals(NODE)) {
        this.assertionErrors.add(format(
            "onStreamResponse#request => expected node = %s, got %s",
            NODE,
            request.getNode()));
      }

      if (response == null) {
        this.assertionErrors.add("onStreamResponse#response => expected not null");
      }
    }

    void assertThatNoErrors() {
      if (!assertionErrors.isEmpty()) {
        throw new AssertionError(String.join(", ", assertionErrors));
      }
    }
  }

  private static class MockDiscoveryResponseObserver implements StreamObserver<DiscoveryResponse> {

    private final Collection<String> assertionErrors = new LinkedList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final CountDownLatch completedLatch = new CountDownLatch(1);
    private final AtomicBoolean error = new AtomicBoolean();
    private final CountDownLatch errorLatch = new CountDownLatch(1);
    private final AtomicInteger nonce = new AtomicInteger();
    private final Collection<DiscoveryResponse> responses = new LinkedList<>();

    private Throwable errorException;
    private boolean sendError = false;

    void assertThatNoErrors() {
      if (!assertionErrors.isEmpty()) {
        throw new AssertionError(String.join(", ", assertionErrors));
      }
    }

    @Override
    public void onNext(DiscoveryResponse value) {
      // Assert that the nonce is monotonically increasing.
      String nonce = Integer.toString(this.nonce.getAndIncrement());

      if (!nonce.equals(value.getNonce())) {
        assertionErrors.add(String.format("Nonce => got %s, wanted %s", value.getNonce(), nonce));
      }

      // Assert that the version is set.
      if (Strings.isNullOrEmpty(value.getVersionInfo())) {
        assertionErrors.add("VersionInfo => got none, wanted non-empty");
      }

      // Assert that resources are non-empty.
      if (value.getResourcesList().isEmpty()) {
        assertionErrors.add("Resources => got none, wanted non-empty");
      }

      if (Strings.isNullOrEmpty(value.getTypeUrl())) {
        assertionErrors.add("TypeUrl => got none, wanted non-empty");
      }

      value.getResourcesList().forEach(r -> {
        if (!value.getTypeUrl().equals(r.getTypeUrl())) {
          assertionErrors.add(String.format("TypeUrl => got %s, wanted %s", r.getTypeUrl(), value.getTypeUrl()));
        }
      });

      responses.add(value);

      if (sendError) {
        throw Status.INTERNAL
            .withDescription("send error")
            .asRuntimeException();
      }
    }

    @Override
    public void onError(Throwable t) {
      error.set(true);
      errorException = t;
      errorLatch.countDown();
    }

    @Override
    public void onCompleted() {
      completed.set(true);
      completedLatch.countDown();
    }
  }
}
