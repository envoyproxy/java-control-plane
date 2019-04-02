package io.envoyproxy.controlplane.server.serializer;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

/**
 * Default implementation of ProtoResourcesSerializer that uses {@link Any#pack(Message)} method on {@link Message}.
 */
public class DefaultProtoResourcesSerializer implements ProtoResourcesSerializer {

  /**
   * {@inheritDoc}
   */
  @Override
  public Any serialize(Message resource) {
    return Any.pack(resource);
  }
}
