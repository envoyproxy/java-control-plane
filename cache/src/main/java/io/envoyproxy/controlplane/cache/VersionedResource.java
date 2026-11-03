package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.common.hash.Hashing;
import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;

@AutoValue
public abstract class VersionedResource<T extends Message> {

  /**
   * Returns a new {@link VersionedResource} instance.
   *
   * @param resource the resource
   * @param version  the version associated with the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> VersionedResource<T> create(T resource, String version) {
    return new AutoValue_VersionedResource<>(
        resource,
        version
    );
  }

  /**
   * Returns a new {@link VersionedResource} instance.
   *
   * @param resource the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> VersionedResource<T> create(T resource) {
    return new AutoValue_VersionedResource<>(
        resource,
        // todo: is this a stable hash?
        Hashing.sha256()
            .hashString(resourceHashCode(resource), StandardCharsets.UTF_8)
            .toString()
    );
  }

  /**
   * Returns a new {@link VersionedResource} whose version is a strong content hash of the
   * serialized protobuf bytes ({@code sha256(resource.toByteArray())}), mirroring go-control-plane's
   * {@code HashResource}. Prefer this over {@link #create(Message)} when the version must reliably
   * change whenever the resource content changes — most notably for TLS certificate (SDS) rotation,
   * where a missed version change means Envoy silently keeps the old certificate. Unlike
   * {@link #create(Message)} (whose distinguishing entropy is only the 32-bit {@code hashCode()}),
   * this has full collision resistance. Used by {@code LinearCache}.
   *
   * @param resource the resource
   * @param <T>      the type of resource
   */
  public static <T extends Message> VersionedResource<T> createWithContentHash(T resource) {
    return create(resource, contentHash(resource));
  }

  /**
   * Returns the strong content-hash version for a resource: {@code sha256(resource.toByteArray())}, mirroring
   * go-control-plane's {@code HashResource}. This is the single source of truth for content-based versions; it
   * marshals the resource, so callers that compute it lazily (e.g. {@code LinearCache}'s cached resources)
   * should memoize the result.
   *
   * @param resource the resource
   * @return the hex sha256 of the serialized resource
   */
  public static String contentHash(Message resource) {
    return Hashing.sha256().hashBytes(resource.toByteArray()).toString();
  }

  private static <T extends Message> String resourceHashCode(T resource) {
    return resource.getClass() + "@" + resource.hashCode();
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
