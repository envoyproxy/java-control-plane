package io.envoyproxy.controlplane.server.serializer;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Default implementation of ProtoResourcesSerializer that uses {@link Any#pack(Message)} method on {@link Message}.
 */
public class DefaultProtoResourcesSerializer implements ProtoResourcesSerializer {

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<Any> serialize(Collection<? extends Message> resources) {
    return resources.stream()
        .map(Any::pack)
        .collect(Collectors.toList());
  }
}
