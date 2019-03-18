package io.envoyproxy.controlplane.server.serializer;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

import java.util.Collection;

/**
 * Serializer of the proto buffers resource messages.
 */
public interface ProtoResourcesSerializer {

  /**
   * Serialize messages to proto buffers.
   * @param resources list of resources to serialize
   * @return serialized resources
   */
  Collection<Any> serialize(Collection<? extends Message> resources);

  class ProtoSerializerException extends RuntimeException {
    ProtoSerializerException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
