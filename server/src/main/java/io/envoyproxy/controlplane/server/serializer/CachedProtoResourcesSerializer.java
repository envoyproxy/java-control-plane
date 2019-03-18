package io.envoyproxy.controlplane.server.serializer;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Cached version of the {@link ProtoResourcesSerializer}. It uses Guava Cache with weak values to store the serialized
 * messages. The improvement especially visible when the same message is send to multiple Envoys (i.e. Edge Envoys).
 * The message is then only serialized once as long as it's referenced anywhere else.
 * Moreover, when the value is stored somewhere later, the same instance can be reused.
 */
public class CachedProtoResourcesSerializer implements ProtoResourcesSerializer {

  private static final Cache<Collection<? extends Message>, List<Any>> cache = CacheBuilder.newBuilder()
      .weakValues()
      .build();

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<Any> serialize(Collection<? extends Message> resources) {
    try {
      return cache.get(resources, () -> resources.stream().map(Any::pack).collect(Collectors.toList()));
    } catch (ExecutionException e) {
      throw new ProtoSerializerException("Error while serializing resources", e);
    }
  }
}
