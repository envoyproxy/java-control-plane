package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code MuxCache} multiplexes across several {@link Cache} instances keyed by resource type, delegating each
 * watch to the cache responsible for the requested {@code typeUrl}. This lets each resource type use the cache
 * strategy that fits it — e.g. RDS/SDS on {@link LinearCache} for O(1) updates and certificate rotation, while
 * CDS/LDS stay on {@link SimpleCache}. Mirrors go-control-plane's {@code MuxCache}.
 *
 * <p>If a request arrives for a type with no configured cache it is a configuration error and a
 * {@link IllegalArgumentException} is raised (consistent with {@link SimpleCache} rejecting unsupported types).
 */
@ThreadSafe
public class MuxCache<T> implements Cache<T> {

  private final Map<ResourceType, Cache<T>> caches;

  /**
   * Constructs a mux cache.
   *
   * @param caches the per-resource-type caches to delegate to
   */
  public MuxCache(Map<ResourceType, Cache<T>> caches) {
    this.caches = ImmutableMap.copyOf(caches);
  }

  @Override
  public Watch createWatch(
      boolean ads,
      XdsRequest request,
      Set<String> knownResourceNames,
      Consumer<Response> responseConsumer,
      boolean hasClusterChanged,
      boolean allowDefaultEmptyEdsUpdate) {
    return resolve(request.getResourceType(), request.getTypeUrl())
        .createWatch(ads, request, knownResourceNames, responseConsumer, hasClusterChanged, allowDefaultEmptyEdsUpdate);
  }

  @Override
  public DeltaWatch createDeltaWatch(
      DeltaXdsRequest request,
      String requesterVersion,
      Map<String, String> resourceVersions,
      Set<String> pendingResources,
      boolean isWildcard,
      Consumer<DeltaResponse> responseConsumer,
      boolean hasClusterChanged) {
    return resolve(request.getResourceType(), request.getTypeUrl())
        .createDeltaWatch(request, requesterVersion, resourceVersions, pendingResources, isWildcard,
            responseConsumer, hasClusterChanged);
  }

  private Cache<T> resolve(ResourceType resourceType, String typeUrl) {
    Cache<T> cache = resourceType == null ? null : caches.get(resourceType);
    if (cache == null) {
      throw new IllegalArgumentException(String.format("no cache configured for type URL %s", typeUrl));
    }
    return cache;
  }

  @Override
  public Collection<T> groups() {
    Set<T> groups = new java.util.HashSet<>();
    for (Cache<T> cache : caches.values()) {
      groups.addAll(cache.groups());
    }
    return ImmutableSet.copyOf(groups);
  }

  @Override
  public StatusInfo<T> statusInfo(T group) {
    List<StatusInfo<T>> infos = new ArrayList<>();
    for (Cache<T> cache : caches.values()) {
      StatusInfo<T> info = cache.statusInfo(group);
      if (info != null) {
        infos.add(info);
      }
    }
    if (infos.isEmpty()) {
      return null;
    }
    return new GroupCacheStatusInfo<>(infos);
  }
}
