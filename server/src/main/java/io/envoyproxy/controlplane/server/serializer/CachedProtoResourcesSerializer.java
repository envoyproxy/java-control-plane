package io.envoyproxy.controlplane.server.serializer;

import static io.envoyproxy.controlplane.cache.Resources.ApiVersion.V3;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ApiVersion;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Cached version of the {@link ProtoResourcesSerializer}. It uses Guava Cache with weak values to
 * store the serialized messages. The weak values are used so it's possible to share the same proto
 * instance between snapshots that are kept in the memory. The improvement especially visible when
 * the same message is send to multiple Envoys. The message is then only serialized once as long as
 * it's referenced anywhere else. The same instance is used not only between snapshots for different
 * groups but also between subsequent snapshots for the same group, because the last serialized
 * proto instance of {@link DiscoveryResponse} is set in
 * DiscoveryRequestStreamObserver#latestResponse.
 */
public class CachedProtoResourcesSerializer extends DefaultProtoResourcesSerializer {

  private static final Map<ApiVersion, Cache<Message, Any>> caches =
      new HashMap<ApiVersion, Cache<Message, Any>>() {
        {
          put(V3, CacheBuilder.newBuilder().weakValues().build());
        }
      };

  /**
   * {@inheritDoc}
   */
  @Override
  public Any serialize(Message resource, ApiVersion apiVersion) {
    try {
      return caches.get(apiVersion).get(resource, () -> Any.pack(resource));
    } catch (ExecutionException e) {
      throw new ProtoSerializerException("Error while serializing resources", e);
    }
  }
}
