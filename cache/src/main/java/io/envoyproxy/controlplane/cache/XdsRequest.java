package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.TYPE_URLS_TO_RESOURCE_TYPE;

import com.google.auto.value.AutoValue;
import com.google.protobuf.ProtocolStringList;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;

/**
 * XdsRequest wraps a v3 DiscoveryRequest and provides common methods as a workaround to the proto
 * messages not implementing a common interface that can be used to abstract away xDS version.
 * XdsRequest is passed around the codebase through common code
 */
@AutoValue
public abstract class XdsRequest {

  public static XdsRequest create(DiscoveryRequest discoveryRequest) {
    return new AutoValue_XdsRequest(discoveryRequest);
  }

  /**
   * Returns the underlying v3 request.
   *
   * @return v3 DiscoveryRequest
   */
  public abstract DiscoveryRequest v3Request();

  /** Returns the type URL of the v3 request. */
  public String getTypeUrl() {
    return v3Request().getTypeUrl();
  }

  /**
   * Returns the ResourceType of the underlying request. This is useful for accepting requests for
   * v3 resource types and having a key to normalize on the logical resource.
   */
  public ResourceType getResourceType() {
    return TYPE_URLS_TO_RESOURCE_TYPE.get(v3Request().getTypeUrl());
  }

  /** Returns the resource names from the underlying DiscoveryRequest. */
  public ProtocolStringList getResourceNamesList() {
    return v3Request().getResourceNamesList();
  }

  /** Returns the version_info from the underlying DiscoveryRequest. */
  public String getVersionInfo() {
    return v3Request().getVersionInfo();
  }

  /** Returns the response nonce from the underlying DiscoveryRequest. */
  public String getResponseNonce() {
    return v3Request().getResponseNonce();
  }

  /** Returns the error_detail from the underlying v3 request. */
  public boolean hasErrorDetail() {
    return v3Request().hasErrorDetail();
  }
}
