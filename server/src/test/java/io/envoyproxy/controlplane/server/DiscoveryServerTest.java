package io.envoyproxy.controlplane.server;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import envoy.api.v2.Cds.Cluster;
import envoy.api.v2.ClusterDiscoveryServiceGrpc;
import envoy.api.v2.ClusterDiscoveryServiceGrpc.ClusterDiscoveryServiceStub;
import envoy.api.v2.Discovery.DiscoveryRequest;
import envoy.api.v2.Discovery.DiscoveryResponse;
import envoy.api.v2.Eds.ClusterLoadAssignment;
import envoy.api.v2.EndpointDiscoveryServiceGrpc;
import envoy.api.v2.EndpointDiscoveryServiceGrpc.EndpointDiscoveryServiceStub;
import envoy.api.v2.Lds.Listener;
import envoy.api.v2.ListenerDiscoveryServiceGrpc;
import envoy.api.v2.ListenerDiscoveryServiceGrpc.ListenerDiscoveryServiceStub;
import envoy.api.v2.Rds.RouteConfiguration;
import envoy.api.v2.RouteDiscoveryServiceGrpc;
import envoy.api.v2.RouteDiscoveryServiceGrpc.RouteDiscoveryServiceStub;
import envoy.api.v2.core.Base.Node;
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.Watch;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcServerRule;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Condition;
import org.junit.Rule;
import org.junit.Test;
import reactor.core.publisher.EmitterProcessor;

public class DiscoveryServerTest {

  private static final String CLUSTER_NAME  = "cluster0";
  private static final String LISTENER_NAME = "listener0";
  private static final String ROUTE_NAME    = "route0";

  private static final int ENDPOINT_PORT = Ports.getAvailablePort();
  private static final int LISTENER_PORT = Ports.getAvailablePort();

  private static final Node NODE = Node.newBuilder()
      .setId("test-id")
      .setCluster("test-cluster")
      .build();

  private static final String VERSION = Integer.toString(ThreadLocalRandom.current().nextInt(1, 1000));

  private static final Cluster CLUSTER = TestResources.createCluster(CLUSTER_NAME);
  private static final ClusterLoadAssignment ENDPOINT = TestResources.createEndpoint(CLUSTER_NAME, ENDPOINT_PORT);
  private static final Listener LISTENER = TestResources.createListener(LISTENER_NAME, LISTENER_PORT, ROUTE_NAME);
  private static final RouteConfiguration ROUTE = TestResources.createRoute(ROUTE_NAME, CLUSTER_NAME);

  @Rule
  public final GrpcServerRule grpcServer = new GrpcServerRule().directExecutor();

  @Test
  public void testAggregatedHandler() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    DiscoveryServer server = new DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

    StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(responseObserver);

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.LISTENER_TYPE_URL)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.CLUSTER_TYPE_URL)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.ENDPOINT_TYPE_URL)
        .addResourceNames(CLUSTER_NAME)
        .build());

    requestObserver.onNext(DiscoveryRequest.newBuilder()
        .setNode(NODE)
        .setTypeUrl(Resources.ROUTE_TYPE_URL)
        .addResourceNames(ROUTE_NAME)
        .build());

    requestObserver.onCompleted();

    if (!responseObserver.completedLatch.await(1, TimeUnit.SECONDS) || responseObserver.error.get()) {
      fail(format("failed to complete request before timeout, error = %b", responseObserver.error.get()));
    }

    responseObserver.assertThatNoErrors();

    for (String typeUrl : Resources.TYPE_URLS) {
      assertThat(configWatcher.counts).containsEntry(typeUrl, 1);
    }

    assertThat(configWatcher.counts).hasSize(Resources.TYPE_URLS.size());

    for (String typeUrl : Resources.TYPE_URLS) {
      assertThat(responseObserver.responses).haveAtLeastOne(new Condition<>(
          r -> r.getTypeUrl().equals(typeUrl) && r.getVersionInfo().equals(VERSION),
          "missing expected response of type %s", typeUrl));
    }
  }

  @Test
  public void testSeparateHandlers() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(false, createResponses());
    DiscoveryServer server = new DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getClusterDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getEndpointDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getListenerDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getRouteDiscoveryServiceImpl());

    ClusterDiscoveryServiceStub  clusterStub  = ClusterDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    EndpointDiscoveryServiceStub endpointStub = EndpointDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    ListenerDiscoveryServiceStub listenerStub = ListenerDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    RouteDiscoveryServiceStub    routeStub    = RouteDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = null;
      DiscoveryRequest.Builder discoveryRequestBuilder = DiscoveryRequest.newBuilder()
          .setNode(NODE)
          .setTypeUrl(typeUrl);

      switch (typeUrl) {
        case Resources.CLUSTER_TYPE_URL:
          requestObserver = clusterStub.streamClusters(responseObserver);
          break;
        case Resources.ENDPOINT_TYPE_URL:
          requestObserver = endpointStub.streamEndpoints(responseObserver);
          discoveryRequestBuilder.addResourceNames(CLUSTER_NAME);
          break;
        case Resources.LISTENER_TYPE_URL:
          requestObserver = listenerStub.streamListeners(responseObserver);
          break;
        case Resources.ROUTE_TYPE_URL:
          requestObserver = routeStub.streamRoutes(responseObserver);
          discoveryRequestBuilder.addResourceNames(ROUTE_NAME);
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

    assertThat(configWatcher.counts).hasSize(Resources.TYPE_URLS.size());
  }

  @Test
  public void testWatchClosed() throws InterruptedException {
    MockConfigWatcher configWatcher = new MockConfigWatcher(true, ImmutableMultimap.of());
    DiscoveryServer server = new DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.TYPE_URLS) {

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
    DiscoveryServer server = new DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.TYPE_URLS) {
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
    DiscoveryServer server = new DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getAggregatedDiscoveryServiceImpl());

    AggregatedDiscoveryServiceStub stub = AggregatedDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.TYPE_URLS) {
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
    MockConfigWatcher configWatcher = new MockConfigWatcher(true, ImmutableMultimap.of());
    DiscoveryServer server = new DiscoveryServer(configWatcher);

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
    DiscoveryServer server = new DiscoveryServer(configWatcher);

    grpcServer.getServiceRegistry().addService(server.getClusterDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getEndpointDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getListenerDiscoveryServiceImpl());
    grpcServer.getServiceRegistry().addService(server.getRouteDiscoveryServiceImpl());

    ClusterDiscoveryServiceStub  clusterStub  = ClusterDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    EndpointDiscoveryServiceStub endpointStub = EndpointDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    ListenerDiscoveryServiceStub listenerStub = ListenerDiscoveryServiceGrpc.newStub(grpcServer.getChannel());
    RouteDiscoveryServiceStub    routeStub    = RouteDiscoveryServiceGrpc.newStub(grpcServer.getChannel());

    for (String typeUrl : Resources.TYPE_URLS) {
      MockDiscoveryResponseObserver responseObserver = new MockDiscoveryResponseObserver();

      StreamObserver<DiscoveryRequest> requestObserver = null;

      switch (typeUrl) {
        case Resources.CLUSTER_TYPE_URL:
          requestObserver = clusterStub.streamClusters(responseObserver);
          break;
        case Resources.ENDPOINT_TYPE_URL:
          requestObserver = endpointStub.streamEndpoints(responseObserver);
          break;
        case Resources.LISTENER_TYPE_URL:
          requestObserver = listenerStub.streamListeners(responseObserver);
          break;
        case Resources.ROUTE_TYPE_URL:
          requestObserver = routeStub.streamRoutes(responseObserver);
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

  private static Multimap<String, Response> createResponses() {
    DiscoveryRequest request = DiscoveryRequest.getDefaultInstance();

    return ImmutableMultimap.<String, Response>builder()
        .put(Resources.CLUSTER_TYPE_URL, Response.create(request, ImmutableList.of(CLUSTER), VERSION))
        .put(Resources.ENDPOINT_TYPE_URL, Response.create(request, ImmutableList.of(ENDPOINT), VERSION))
        .put(Resources.LISTENER_TYPE_URL, Response.create(request, ImmutableList.of(LISTENER), VERSION))
        .put(Resources.ROUTE_TYPE_URL, Response.create(request, ImmutableList.of(ROUTE), VERSION))
        .build();
  }

  private static class MockConfigWatcher implements ConfigWatcher {

    private final boolean closeWatch;
    private final Map<String, Integer> counts;
    private final LinkedListMultimap<String, Response> responses;

    MockConfigWatcher(boolean closeWatch, Multimap<String, Response> responses) {
      this.closeWatch = closeWatch;
      this.counts = new HashMap<>();
      this.responses = LinkedListMultimap.create(responses);
    }

    @Override
    public Watch createWatch(DiscoveryRequest request) {
      counts.put(request.getTypeUrl(), counts.getOrDefault(request.getTypeUrl(), 0) + 1);

      Watch watch = new Watch(request);

      if (responses.get(request.getTypeUrl()).size() > 0) {
        Response response = responses.get(request.getTypeUrl()).remove(0);

        EmitterProcessor<Response> emitter = (EmitterProcessor<Response>) watch.value();

        emitter.onNext(response);
      } else if (closeWatch) {
        watch.cancel();
      }

      return watch;
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
      errorLatch.countDown();
    }

    @Override
    public void onCompleted() {
      completed.set(true);
      completedLatch.countDown();
    }
  }
}
