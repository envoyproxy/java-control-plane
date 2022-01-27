package io.envoyproxy.controlplane.server;

import com.google.auto.value.AutoValue;
import java.util.List;
import java.util.Map;

/**
 * Class introduces optimization which store only required data during next request.
 */
@AutoValue
public abstract class LatestDeltaDiscoveryResponse {
  static LatestDeltaDiscoveryResponse create(String nonce,
                                             String version,
                                             Map<String, String> resourceVersions,
                                             List<String> removedResources) {
    return new AutoValue_LatestDeltaDiscoveryResponse(nonce, version, resourceVersions, removedResources);
  }

  abstract String nonce();

  abstract String version();

  abstract Map<String, String> resourceVersions();

  abstract List<String> removedResources();
}
