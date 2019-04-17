package io.envoyproxy.controlplane.cache;

import java.util.Collections;
import java.util.List;

/**
 * {@code ResourceVersionResolver} calculates a version for resources in {@link SnapshotCache}.
 * It can be used to take advantage of Envoy's per-resource versioning.
 */
public interface ResourceVersionResolver {
  /**
   * Returns a version for resources.
   *
   * @param resourceNames list of resourceNames requested an empty list means all resources.
   * @return version for the resources received
   */
  String version(List<String> resourceNames);

  /**
   * Returns a version for all resources.
   *
   * @return version for all the resources in the {@link SnapshotCache}.
   */
  default String version() {
    return version(Collections.emptyList());
  }
}
