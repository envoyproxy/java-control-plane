package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.TYPE_URLS_TO_RESOURCE_TYPE;

import com.google.auto.value.AutoValue;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * XdsRequest wraps a v2 or v3 DiscoveryRequest of and provides common methods as a
 * workaround to the proto messages not implementing a common interface that can be used to
 * abstract away xDS version. XdsRequest is passed around the codebase through common code,
 * however the callers that need the raw request from it have knowledge of whether it is a v2 or
 * a v3 request.
 */
@AutoValue
public abstract class DeltaXdsRequest {
  public static DeltaXdsRequest create(DeltaDiscoveryRequest discoveryRequest) {
    return new AutoValue_DeltaXdsRequest(discoveryRequest, null);
  }

  public static DeltaXdsRequest create(
      io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest discoveryRequest) {
    return new AutoValue_DeltaXdsRequest(null, discoveryRequest);
  }

  /**
   * Returns the underlying v2 request, or null if this was a v3 request. Callers should have
   * knowledge of whether the request was v2 or not.
   *
   * @return v2 DiscoveryRequest or null
   */
  @Nullable
  public abstract DeltaDiscoveryRequest v2Request();

  /**
   * Returns he underlying v3 request, or null if this was a v2 request. Callers should have
   * knowledge of whether the request was v3 or not.
   *
   * @return v3 DiscoveryRequest or null
   */
  @Nullable
  public abstract io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest v3Request();

  /**
   * Returns the type URL of the v2 or v3 request.
   */
  public String getTypeUrl() {
    if (v2Request() != null) {
      return v2Request().getTypeUrl();
    }
    return v3Request().getTypeUrl();
  }

  /**
   * Returns the ResourceType of the underlying request. This is useful for accepting requests
   * for both v2 and v3 resource types and having a key to normalize on the logical resource.
   */
  public ResourceType getResourceType() {
    if (v2Request() != null) {
      return TYPE_URLS_TO_RESOURCE_TYPE.get(v2Request().getTypeUrl());
    }
    return TYPE_URLS_TO_RESOURCE_TYPE.get(v3Request().getTypeUrl());
  }

  /**
   * Returns the response nonse from the underlying DiscoveryRequest.
   */
  public String getResponseNonce() {
    if (v2Request() != null) {
      return v2Request().getResponseNonce();
    }
    return v3Request().getResponseNonce();
  }

  /**
   * Returns the error_detail from the underlying v2 or v3 request.
   */
  public boolean hasErrorDetail() {
    if (v2Request() != null) {
      return v2Request().hasErrorDetail();
    }
    return v3Request().hasErrorDetail();
  }

  /**
   * Returns the resource_names_subscribe from the underlying v2 or v3 request.
   */
  public List<String> getResourceNamesSubscribeList() {
    if (v2Request() != null) {
      return v2Request().getResourceNamesSubscribeList();
    }
    return v3Request().getResourceNamesSubscribeList();
  }

  /**
   * Returns the resource_names_unsubscribe from the underlying v2 or v3 request.
   */
  public List<String> getResourceNamesUnsubscribeList() {
    if (v2Request() != null) {
      return v2Request().getResourceNamesUnsubscribeList();
    }
    return v3Request().getResourceNamesUnsubscribeList();
  }

  /**
   * Returns the initial_resource_versions from the underlying v2 or v3 request.
   */
  public Map<String, String> getInitialResourceVersionsMap() {
    if (v2Request() != null) {
      return v2Request().getInitialResourceVersionsMap();
    }
    return v3Request().getInitialResourceVersionsMap();
  }
}
