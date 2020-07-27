package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.TYPE_URLS_TO_RESOURCE_TYPE;

import com.google.auto.value.AutoValue;
import com.google.protobuf.ProtocolStringList;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import javax.annotation.Nullable;

/**
 * XdsRequest wraps a v2 or v3 DiscoveryRequest of and provides common methods as a
 * workaround to the proto messages not implementing a common interface that can be used to
 * abstract away xDS version. XdsRequest is passed around the codebase through common code,
 * however the callers that need the raw request from it have knowledge of whether it is a v2 or
 * a v3 request.
 */
@AutoValue
public abstract class XdsRequest {
  public static XdsRequest create(DiscoveryRequest discoveryRequest) {
    return new AutoValue_XdsRequest(discoveryRequest, null);
  }

  public static XdsRequest create(io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest discoveryRequest) {
    return new AutoValue_XdsRequest(null, discoveryRequest);
  }

  /**
   * Returns the underlying v2 request, or null if this was a v3 request. Callers should have
   * knowledge of whether the request was v2 or not.
   * @return v2 DiscoveryRequest or null
   */
  @Nullable public abstract DiscoveryRequest v2Request();

  /**
   * Returns he underlying v3 request, or null if this was a v2 request. Callers should have
   * knowledge of whether the request was v3 or not.
   * @return v3 DiscoveryRequest or null
   */
  @Nullable public abstract io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest v3Request();

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
   * Returns the resource names from the underlying DiscoveryRequest.
   */
  public ProtocolStringList getResourceNamesList() {
    if (v2Request() != null) {
      return v2Request().getResourceNamesList();
    }
    return v3Request().getResourceNamesList();
  }

  /**
   * Returns the version_info from the underlying DiscoveryRequest.
   */
  public String getVersionInfo() {
    if (v2Request() != null) {
      return v2Request().getVersionInfo();
    }
    return v3Request().getVersionInfo();
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
}
