package io.envoyproxy.controlplane.server;

import com.google.auto.value.AutoValue;
import java.util.Set;

/**
 * Class introduces optimization which store only required data during next request.
 */
@AutoValue
public abstract class LatestDiscoveryResponse {
  static LatestDiscoveryResponse create(String nonce, Set<String> resourceNames) {
    return new AutoValue_LatestDiscoveryResponse(nonce, resourceNames);
  }

  abstract String nonce();

  abstract Set<String> resourceNames();
}
