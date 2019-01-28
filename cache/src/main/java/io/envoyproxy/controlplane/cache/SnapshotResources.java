package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;

import java.util.HashMap;
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
   * @param version the version associated with the resources in this collection
   * @param <T> the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(Iterable<T> resources, String version) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        version,
        new HashMap<>());
  }

  /**
   * Returns a new {@link SnapshotResources} instance with versions by resource name.
   *
   * @param resources the resources in this collection
   * @param version the aggregated version for the resources in this collection
   * @param versionsByResourceName map of versions by resource name
   * @param <T> the type of resources in this collection
   */
  public static <T extends Message> SnapshotResources<T> create(
      Iterable<T> resources,
      String version,
      Map<String, String> versionsByResourceName
  ) {
    return new AutoValue_SnapshotResources<>(
        resourcesMap(resources),
        version,
        versionsByResourceName);
  }

  private static <T extends Message> ImmutableMap<String, T> resourcesMap(Iterable<T> resources) {
    return StreamSupport.stream(resources.spliterator(), false)
        .collect(
            Collector.of(
                ImmutableMap.Builder<String, T>::new,
                (b, e) -> b.put(Resources.getResourceName(e), e),
                (b1, b2) -> b1.putAll(b2.build()),
                ImmutableMap.Builder::build));
  }

  /**
   * Returns a map of the resources in this collection, where the key is the name of the resource.
   */
  public abstract Map<String, T> resources();

  /**
   * Returns the version associated with this resources in this collection.
   */
  public abstract String version();

  /**
   * Returns the version associated with this resources in this collection, where the key is the name of the resource.
   */
  public String version(List<String> resourceNames) {
    if (resourceNames.size() != 1 || !versionsByResourceName().containsKey(resourceNames.get(0))) {
      return version();
    }
    return versionsByResourceName().get(resourceNames.get(0));
  }

  /**
   * Returns the versions associated with this resources in this collection.
   */
  public abstract Map<String, String> versionsByResourceName();

}
