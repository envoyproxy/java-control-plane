package io.envoyproxy.controlplane.v3.server.serializer;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Serializer of the proto buffers resource messages.
 */
public interface ProtoResourcesSerializer {

  /**
   * Serialize messages to proto buffers.
   * @param resources list of resources to serialize
   * @return serialized resources
   */
  default Collection<Any> serialize(Collection<? extends Message> resources) {
    return resources.stream().map(this::serialize).collect(Collectors.toList());
  }

  /**
   * Serialize message to proto buffers.
   * @param resource the resource to serialize
   * @return serialized resource
   */
  Any serialize(Message resource);

  class ProtoSerializerException extends RuntimeException {
    ProtoSerializerException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
