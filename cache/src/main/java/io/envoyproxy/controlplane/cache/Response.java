package io.envoyproxy.controlplane.cache;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Message;
import java.util.Collection;

/**
 * {@code Response} is a data class that contains the response for an assumed configuration type.
 */
@AutoValue
public abstract class Response {

  public static Response create(XdsRequest request, Collection<? extends Message> resources,
      String version) {
    return new AutoValue_Response(request, resources, version);
  }

  /**
   * Returns the original request associated with the response.
   */
  public abstract XdsRequest request();

  /**
   * Returns the resources to include in the response.
   */
  public abstract Collection<? extends Message> resources();

  /**
   * Returns the version of the resources as tracked by the cache for the given type. Envoy responds with this version
   * as an acknowledgement.
   */
  public abstract String version();
}
