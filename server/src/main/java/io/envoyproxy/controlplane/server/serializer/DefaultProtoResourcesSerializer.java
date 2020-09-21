package io.envoyproxy.controlplane.server.serializer;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Resources.ApiVersion;

/**
 * Default implementation of ProtoResourcesSerializer that uses {@link Any#pack(Message)} method on {@link Message}.
 */
public class DefaultProtoResourcesSerializer implements ProtoResourcesSerializer {

  /**
   * {@inheritDoc}
   */
  @Override
  public Any serialize(Message resource, ApiVersion apiVersion) {
    Any output = Any.pack(resource);

    return maybeRewriteTypeUrl(output, apiVersion);
  }

  protected Any maybeRewriteTypeUrl(Any output, ApiVersion apiVersion) {
    // If the requested version and output version match, we can just return the output.
    if (Resources.getResourceApiVersion(output.getTypeUrl()) == apiVersion) {
      return output;
    }

    // Rewrite the type URL to the requested version. This takes advantage of the fact that v2
    // and v3 resources are wire compatible (as long as callers are careful about deprecated v2
    // fields or new v3 fields).
    // TODO: revisit this if https://github.com/envoyproxy/envoy/issues/10776 is implemented which
    //  would mean we don't need to do any type URL rewriting since Envoy can handle either.
    switch (apiVersion) {
      case V2:
        return output.toBuilder()
            .setTypeUrl(Resources.V3_TYPE_URLS_TO_V2.get(output.getTypeUrl()))
            .build();
      case V3:
        return output.toBuilder()
            .setTypeUrl(Resources.V2_TYPE_URLS_TO_V3.get(output.getTypeUrl()))
            .build();
      default:
        throw new RuntimeException(String.format("Unsupported API version %s", apiVersion));
    }
  }
}
