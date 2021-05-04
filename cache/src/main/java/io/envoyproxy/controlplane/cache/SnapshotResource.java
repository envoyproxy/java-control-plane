package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Message;

@AutoValue
public abstract class SnapshotResource<T extends Message> {

  /**
   * Returns a new {@link SnapshotResource} instance.
   *
   * @param resource the resource
   * @param version  the version associated with the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> SnapshotResource<T> create(T resource, String version) {
    return new AutoValue_SnapshotResource<>(
        resource,
        version
    );
  }

  /**
   * Returns the resource.
   */
  public abstract T resource();

  /**
   * Returns the version associated with the resource.
   */
  public abstract String version();
}
