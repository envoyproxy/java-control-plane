package io.envoyproxy.controlplane.server;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import io.envoyproxy.controlplane.cache.ConfigWatcher;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DiscoveryServer<T, U> {
  static final String ANY_TYPE_URL = "";
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryServer.class);
  final List<DiscoveryServerCallbacks> callbacks;
  final ConfigWatcher configWatcher;
  final ProtoResourcesSerializer protoResourcesSerializer;
  private final ExecutorGroup executorGroup;
  private final AtomicLong streamCount = new AtomicLong();

  /**
   * Creates the server.
   *
   * @param callbacks                server callbacks
   * @param configWatcher            source of configuration updates
   * @param protoResourcesSerializer serializer of proto buffer messages
   */
  protected DiscoveryServer(List<DiscoveryServerCallbacks> callbacks,
                         ConfigWatcher configWatcher,
                         ExecutorGroup executorGroup,
                         ProtoResourcesSerializer protoResourcesSerializer) {
    Preconditions.checkNotNull(executorGroup, "executorGroup cannot be null");
    Preconditions.checkNotNull(callbacks, "callbacks cannot be null");
    Preconditions.checkNotNull(configWatcher, "configWatcher cannot be null");
    Preconditions.checkNotNull(protoResourcesSerializer, "protoResourcesSerializer cannot be null");

    this.callbacks = callbacks;
    this.configWatcher = configWatcher;
    this.protoResourcesSerializer = protoResourcesSerializer;
    this.executorGroup = executorGroup;
  }

  protected abstract XdsRequest wrapXdsRequest(T request);

  protected abstract U makeResponse(String version, Collection<Any> resources, String typeUrl,
      String nonce);

  protected abstract void runStreamRequestCallbacks(long streamId, T request);

  protected abstract void runStreamResponseCallbacks(long streamId, XdsRequest request, U response);

  StreamObserver<T> createRequestHandler(
      StreamObserver<U> responseObserver,
      boolean ads,
      String defaultTypeUrl) {

    long streamId = streamCount.getAndIncrement();
    Executor executor = executorGroup.next();

    LOGGER.debug("[{}] open stream from {}", streamId, defaultTypeUrl);

    callbacks.forEach(cb -> cb.onStreamOpen(streamId, defaultTypeUrl));

    final DiscoveryRequestStreamObserver<T, U> requestStreamObserver;
    if (ads) {
      requestStreamObserver = new AdsDiscoveryRequestStreamObserver<>(
          responseObserver,
          streamId,
          executor,
          this);
    } else {
      requestStreamObserver = new XdsDiscoveryRequestStreamObserver<>(
          defaultTypeUrl,
          responseObserver,
          streamId,
          executor,
          this);
    }

    if (responseObserver instanceof ServerCallStreamObserver) {
      ((ServerCallStreamObserver) responseObserver).setOnCancelHandler(requestStreamObserver::onCancelled);
    }

    return requestStreamObserver;
  }
}
