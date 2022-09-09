package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;

class ResourceMapBuilder<T extends Message> {

  private final ImmutableMap.Builder<String, VersionedResource<T>> versionedResources = ImmutableMap.builder();
  private final ImmutableMap.Builder<String, T> resources = ImmutableMap.builder();


  ImmutableMap<String, VersionedResource<T>> getVersionedResources() {
    return versionedResources.build();
  }

  ImmutableMap<String, T> getResources() {
    return resources.build();
  }

  void put(Object resource) {
    if (resource instanceof VersionedResource) {
      VersionedResource<T> eCast = (VersionedResource<T>) resource;
      versionedResources.put(Resources.getResourceName(eCast.resource()), eCast);
      resources.put(Resources.getResourceName(eCast.resource()), eCast.resource());
    } else {
      T eCast = (T) resource;
      versionedResources.put(Resources.getResourceName(eCast), VersionedResource.create(eCast));
      resources.put(Resources.getResourceName(eCast), eCast);
    }
  }

  ResourceMapBuilder<T> putAll(ResourceMapBuilder<T> other) {
    versionedResources.putAll(other.getVersionedResources());
    resources.putAll(other.getResources());
    return this;
  }
}
