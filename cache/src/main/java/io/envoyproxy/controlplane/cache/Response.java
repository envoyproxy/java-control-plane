package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Message;
import java.util.Collection;

/**
 * {@code Response} is a data class that contains the response for an assumed configuration type.
 */
@AutoValue
public abstract class Response {

  public static Response create(boolean canary, Collection<Message> resources, String version) {
    return new AutoValue_Response(canary, resources, version);
  }

  /**
   * Returns the canary bit to control Envoy config transition.
   */
  public abstract boolean canary();

  /**
   * Returns the resources to include in the response.
   */
  public abstract Collection<Message> resources();

  /**
   * Returns the version of the resources as tracked by the cache for the given type. Envoy responds with this version
   * as an acknowledgement.
   */
  public abstract String version();
}
