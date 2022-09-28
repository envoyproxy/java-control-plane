package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.StreamSupport;

@AutoValue
public abstract class SnapshotResources<T extends Message> {

  /**
   * Returns a new {@link SnapshotResources} instance.
   *
   * @param resources the resources in this collection
   * @param version   the version associated with the resources in this collection
   * @param <T>       the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<?> resources,
      String version) {
    ResourceMapBuilder<T> resourcesMapBuilder = createResourcesMap(resources);
    return new AutoValue_SnapshotResources<>(
        resourcesMapBuilder.getVersionedResources(),
        resourcesMapBuilder.getResources(),
        (r) -> version
    );
  }


  /**
   * Returns a new {@link SnapshotResources} instance with versions by resource name.
   *
   * @param resources       the resources in this collection
   * @param versionResolver version resolver for the resources in this collection
   * @param <T>             the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<VersionedResource<T>> resources,
      ResourceVersionResolver versionResolver) {
    ResourceMapBuilder<T> resourcesMapBuilder = createResourcesMap(resources);
    return new AutoValue_SnapshotResources<>(
        resourcesMapBuilder.getVersionedResources(),
        resourcesMapBuilder.getResources(),
        versionResolver);
  }

  private static <T extends Message> ResourceMapBuilder<T> createResourcesMap(
      Iterable<?> resources) {

    return StreamSupport.stream(resources.spliterator(), false)
        .collect(
            Collector.of(
                ResourceMapBuilder<T>::new,
                ResourceMapBuilder::put,
                ResourceMapBuilder::putAll));
  }

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, VersionedResource<T>> versionedResources();

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, T> resources();

  /**
   * Returns the version associated with this all resources in this collection.
   */
  public String version() {
    return resourceVersionResolver().version();
  }

  /**
   * Returns the version associated with the requested resources in this collection.
   *
   * @param resourceNames list of list of requested resources.
   */
  public String version(List<String> resourceNames) {
    return resourceVersionResolver().version(resourceNames);
  }

  /**
   * Returns the version resolver associated with this resources in this collection.
   */
  public abstract ResourceVersionResolver resourceVersionResolver();

}
