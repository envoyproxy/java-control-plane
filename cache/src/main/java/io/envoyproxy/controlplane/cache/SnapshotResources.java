package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import java.util.Map;
import java.util.stream.StreamSupport;

@AutoValue
public abstract class SnapshotResources<T extends Message> {

  /**
   * Returns a new {@link SnapshotResources} instance.
   *
   * @param resources the resources in this collection
   * @param version the version associated with the resources in this collection
   * @param <T> the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(Iterable<T> resources, String version) {
    return new AutoValue_SnapshotResources<>(
        StreamSupport.stream(resources.spliterator(), false)
            .collect(ImmutableMap.toImmutableMap(Resources::getResourceName, r -> r)),
        version);
  }

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, T> resources();

  /**
   * Returns the version associated with this resources in this collection.
   */
  public abstract String version();
}
