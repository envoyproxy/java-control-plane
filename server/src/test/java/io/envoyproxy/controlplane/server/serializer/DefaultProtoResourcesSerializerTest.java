package io.envoyproxy.controlplane.server.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Lists;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import java.util.Collection;
import java.util.List;
import org.junit.Test;

public class DefaultProtoResourcesSerializerTest {

  DefaultProtoResourcesSerializer serializer = new DefaultProtoResourcesSerializer();

  @Test
  public void shouldReturnDifferentInstanceOfSerializedProtoWhenResourcesAreTheSame() {
    ClusterLoadAssignment endpoint = ClusterLoadAssignment.newBuilder()
        .setClusterName("service1")
        .build();

    Any serializedEndpoint = serializer.serialize(endpoint);
    Any serializedSameEndpoint = serializer.serialize(endpoint);

    assertThat(serializedEndpoint).isEqualTo(serializedSameEndpoint);
    assertThat(serializedEndpoint).isNotSameAs(serializedSameEndpoint);
  }

  @Test
  public void shouldReturnDifferentInstancesOfSerializedProtoWhenResourcesAreTheSame() {
    List<ClusterLoadAssignment> endpoints = Lists.newArrayList(
        ClusterLoadAssignment.newBuilder()
            .setClusterName("service1")
            .build(),
        ClusterLoadAssignment.newBuilder()
            .setClusterName("service2")
            .build()
    );

    Collection<Any> serializedEndpoints = serializer.serialize(endpoints);
    Collection<Any> serializedSameEndpoints = serializer.serialize(endpoints);

    assertThat(serializedEndpoints).isEqualTo(serializedSameEndpoints);
    assertThat(serializedEndpoints).isNotSameAs(serializedSameEndpoints);
    assertThat(serializedEndpoints) // elements are not the same instances
        .usingElementComparator((x, y) -> x == y ? 0 : 1)
        .doesNotContainAnyElementsOf(serializedSameEndpoints);
  }
}
