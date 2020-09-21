package io.envoyproxy.controlplane.server.serializer;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ApiVersion;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Serializer of the proto buffers resource messages.
 */
public interface ProtoResourcesSerializer {

  /**
   * Serialize messages to proto buffers.
   * @param resources list of resources to serialize
   * @param apiVersion the API version
   * @return serialized resources
   */
  default Collection<Any> serialize(Collection<? extends Message> resources, ApiVersion apiVersion) {
    return resources.stream()
        .map(resource -> serialize(resource, apiVersion))
        .collect(Collectors.toList());
  }

  /**
   * Serialize message to proto buffers.
   * @param resource the resource to serialize
   * @return serialized resource
   */
  Any serialize(Message resource, ApiVersion apiVersion);

  class ProtoSerializerException extends RuntimeException {
    ProtoSerializerException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
