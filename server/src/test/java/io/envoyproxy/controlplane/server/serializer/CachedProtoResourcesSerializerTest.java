package io.envoyproxy.controlplane.server.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Lists;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import java.util.Collection;
import java.util.List;
import org.junit.Test;

public class CachedProtoResourcesSerializerTest {

  CachedProtoResourcesSerializer serializer = new CachedProtoResourcesSerializer();

  @Test
  public void shouldKeepCachedProtoWhenSerializingSameMessage() {
    // given
    List<ClusterLoadAssignment> endpoints = Lists.newArrayList(
        ClusterLoadAssignment.newBuilder()
            .setClusterName("service1")
            .build(),
        ClusterLoadAssignment.newBuilder()
            .setClusterName("service2")
            .build()
    );

    // when
    Collection<Any> serializedEndpoints = serializer.serialize(endpoints);
    Collection<Any> serializedSameEndpoints = serializer.serialize(endpoints);

    // then
    assertThat(serializedEndpoints).isSameAs(serializedSameEndpoints);
  }
}
