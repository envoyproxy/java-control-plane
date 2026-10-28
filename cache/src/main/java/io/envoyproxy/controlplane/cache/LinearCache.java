package io.envoyproxy.controlplane.cache;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code LinearCache} is a {@link Cache} for a single resource type (one {@code typeUrl}) backed by a flat
 * {@code Map<name, resource>}. Unlike {@link SimpleCache}, mutating one resource is O(1): it touches a single
 * map entry, bumps the cache version, and notifies <em>only the watches subscribed to the changed resource</em>
 * (plus wildcard watches) — there is no full-collection rebuild or diff. This mirrors go-control-plane's
 * {@code LinearCache} and is intended to be combined per-typeUrl via {@link MuxCache} (e.g. RDS/SDS on
 * {@code LinearCache} for O(1) domain add/remove and certificate rotation, CDS/LDS on {@link SimpleCache}).
 *
 * <p>The cache is node-agnostic: a single shared collection serves the whole fleet of proxies, so there is no
 * {@link NodeGroup} hashing. All watch bookkeeping is bucketed under a single, fixed {@code group} identity so
 * that {@link #groups()} / {@link #statusInfo(Object)} and {@link MuxCache} aggregation behave consistently.
 *
 * <p><b>Lazy serialization.</b> Each resource's strong content-hash version
 * ({@link VersionedResource#contentHash(Message)}, which marshals the resource) is computed on first use and
 * memoized, mirroring go-control-plane's {@code cached_resource}. A resource that is never sent to nor compared
 * for any watch is never marshaled — important when each proxy subscribes to only a few of many resources
 * (e.g. one TLS secret per SNI out of 50k).
 */
@ThreadSafe
public class LinearCache<T> implements Cache<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(LinearCache.class);

  private final ResourceType resourceType;
  private final T group;
  private final String versionPrefix;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Lock readLock = lock.readLock();
  private final Lock writeLock = lock.writeLock();

  @GuardedBy("lock")
  private final Map<String, CachedResource> resources = new HashMap<>();
  @GuardedBy("lock")
  private long version = 0;

  private final CacheStatusInfoAggregator<T> statuses = new CacheStatusInfoAggregator<>();
  private final AtomicLong watchCount = new AtomicLong();

  /**
   * Constructs a linear cache for the given resource type.
   *
   * @param resourceType the single resource type served by this cache
   * @param group        the fixed group identity all watches are bucketed under (e.g. {@code "group"})
   */
  public LinearCache(ResourceType resourceType, T group) {
    this(resourceType, group, "");
  }

  /**
   * Constructs a linear cache for the given resource type.
   *
   * @param resourceType  the single resource type served by this cache
   * @param group         the fixed group identity all watches are bucketed under
   * @param versionPrefix prefix applied to the (wildcard/sotw) cache version, useful to distinguish
   *                      replicated cache instances so a client re-connecting to another instance does not
   *                      observe a stale-looking version. May be empty.
   */
  public LinearCache(ResourceType resourceType, T group, String versionPrefix) {
    this.resourceType = Preconditions.checkNotNull(resourceType, "resourceType");
    this.group = Preconditions.checkNotNull(group, "group");
    this.versionPrefix = versionPrefix == null ? "" : versionPrefix;
  }

  // =========================================================================================
  // Mutation API (O(1) per resource). Mirrors go-control-plane LinearCache.
  // =========================================================================================

  /**
   * Creates or replaces a single resource and notifies only the watches that subscribe to it (and wildcard
   * watches). O(1) with respect to the total number of resources in the cache. This is the operation used for
   * TLS certificate rotation: same secret name, new content.
   *
   * @param name     the resource name
   * @param resource the resource message
   */
  public void updateResource(String name, Message resource) {
    Preconditions.checkNotNull(name, "name");
    Preconditions.checkNotNull(resource, "resource");
    writeLock.lock();
    try {
      version++;
      resources.put(name, new CachedResource(resource));
      notifyChanged(Collections.singleton(name));
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Removes a single resource and notifies only the watches that subscribe to it (and wildcard watches).
   *
   * @param name the resource name to remove
   */
  public void deleteResource(String name) {
    Preconditions.checkNotNull(name, "name");
    writeLock.lock();
    try {
      version++;
      resources.remove(name);
      notifyChanged(Collections.singleton(name));
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Applies a batch of updates and deletes atomically, notifying impacted watches once. More efficient than
   * looping over {@link #updateResource} / {@link #deleteResource} under delta/wildcard watches.
   *
   * @param toUpdate resources to create or replace, keyed by name
   * @param toDelete resource names to remove
   */
  public void updateResources(Map<String, ? extends Message> toUpdate, Collection<String> toDelete) {
    Map<String, ? extends Message> updates = toUpdate == null ? Collections.emptyMap() : toUpdate;
    Collection<String> deletes = toDelete == null ? Collections.emptyList() : toDelete;
    writeLock.lock();
    try {
      version++;
      Set<String> modified = new HashSet<>(updates.size() + deletes.size());
      for (Map.Entry<String, ? extends Message> entry : updates.entrySet()) {
        resources.put(entry.getKey(), new CachedResource(entry.getValue()));
        modified.add(entry.getKey());
      }
      for (String name : deletes) {
        resources.remove(name);
        modified.add(name);
      }
      notifyChanged(modified);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Replaces the entire resource set. Every passed resource is treated as changed (no proto equality check),
   * and any name absent from {@code newResources} is removed. When most resources are unchanged, prefer
   * {@link #updateResources} which is far cheaper.
   *
   * @param newResources the new full set of resources, keyed by name
   */
  public void setResources(Map<String, ? extends Message> newResources) {
    Map<String, ? extends Message> next = newResources == null ? Collections.emptyMap() : newResources;
    writeLock.lock();
    try {
      version++;
      Set<String> modified = new HashSet<>();
      // Removed resources.
      for (String name : new ArrayList<>(resources.keySet())) {
        if (!next.containsKey(name)) {
          resources.remove(name);
          modified.add(name);
        }
      }
      // Assume all passed resources changed.
      for (Map.Entry<String, ? extends Message> entry : next.entrySet()) {
        resources.put(entry.getKey(), new CachedResource(entry.getValue()));
        modified.add(entry.getKey());
      }
      notifyChanged(modified);
    } finally {
      writeLock.unlock();
    }
  }

  // =========================================================================================
  // Read accessors (diagnostics). Mirror go-control-plane GetResources / NumResources / NumWatches.
  // =========================================================================================

  /**
   * Returns an immutable copy of the current resources keyed by name. Does not force the lazy version
   * computation. Mirrors go-control-plane's {@code GetResources}.
   */
  public Map<String, Message> getResources() {
    readLock.lock();
    try {
      Map<String, Message> out = new HashMap<>(resources.size());
      resources.forEach((name, cached) -> out.put(name, cached.resource()));
      return ImmutableMap.copyOf(out);
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Returns the number of resources currently in the cache. Mirrors go-control-plane's {@code NumResources}.
   */
  public int numResources() {
    readLock.lock();
    try {
      return resources.size();
    } finally {
      readLock.unlock();
    }
  }

  /**
   * Returns the total number of open watches (sotw + delta) tracked by this cache. Mirrors the spirit of
   * go-control-plane's {@code NumWatches} family.
   */
  public int numWatches() {
    StatusInfo<T> info = statusInfo(group);
    return info == null ? 0 : info.numWatches();
  }

  /**
   * Returns an immutable snapshot of the current resource names. Mostly for tests/diagnostics.
   */
  public Set<String> resourceNames() {
    readLock.lock();
    try {
      return ImmutableSet.copyOf(resources.keySet());
    } finally {
      readLock.unlock();
    }
  }

  // =========================================================================================
  // Notification (runs under writeLock, mirroring go-control-plane which notifies under cache.mu.Lock()).
  // Cost is O(numWatches x numModified), independent of the total resource count.
  // =========================================================================================

  @GuardedBy("lock")
  private void notifyChanged(Set<String> modified) {
    String cacheVersion = getVersion();

    // SOTW watches.
    CacheStatusInfo<T> status = statuses.getStatus(group).get(resourceType);
    if (status != null) {
      status.watchesRemoveIf((id, watch) -> {
        List<String> names = watch.request().getResourceNamesList();
        boolean wildcard = names.isEmpty();
        if (!wildcard && names.stream().noneMatch(modified::contains)) {
          // This watch does not care about any of the modified resources.
          return false;
        }
        String newVersion = versionForNames(names);
        if (watch.request().getVersionInfo().equals(newVersion)) {
          // The resources this watch tracks did not actually change (e.g. same-content update). Keep it open
          // rather than resending a duplicate at an identical version.
          return false;
        }
        return respond(watch, newVersion);
      });
    }

    // Delta watches.
    DeltaCacheStatusInfo<T> deltaStatus = statuses.getDeltaStatus(group).get(resourceType);
    if (deltaStatus != null) {
      deltaStatus.watchesRemoveIf((id, watch) -> {
        Map<String, CachedResource> changed = new HashMap<>();
        List<String> removed = new ArrayList<>();
        collectDeltaChanges(watch, modified, changed, removed);
        if (changed.isEmpty() && removed.isEmpty()) {
          return false;
        }
        ResponseState state = respondDelta(watch, changed, removed, cacheVersion, group);
        return state.isFinished();
      });
    }
  }

  /**
   * Computes, restricted to the {@code modified} set, which resources a delta watch should be sent as changed
   * vs removed. Mirrors {@link SimpleCache}'s findChangedResources/findRemovedResources but scoped to the
   * modified names so the cost stays O(numModified), not O(numResources).
   */
  @GuardedBy("lock")
  private void collectDeltaChanges(DeltaWatch watch,
                                   Set<String> modified,
                                   Map<String, CachedResource> changed,
                                   List<String> removed) {
    for (String name : modified) {
      CachedResource cached = resources.get(name);
      if (cached == null) {
        // Removed from the cache. Only report if the client tracks it.
        if (watch.trackedResources().containsKey(name)) {
          removed.add(name);
        }
        continue;
      }
      boolean include;
      if (watch.pendingResources().contains(name)) {
        include = true;
      } else {
        String trackedVersion = watch.trackedResources().get(name);
        if (trackedVersion == null) {
          // Not tracked by the client: only send under wildcard subscriptions.
          include = watch.isWildcard();
        } else {
          include = !cached.version().equals(trackedVersion);
        }
      }
      if (include) {
        changed.put(name, cached);
      }
    }
  }

  // =========================================================================================
  // SOTW watch creation (ported from SimpleCache, operating on the flat resource map).
  // =========================================================================================

  @Override
  public Watch createWatch(
      boolean ads,
      XdsRequest request,
      Set<String> knownResourceNames,
      Consumer<Response> responseConsumer,
      boolean hasClusterChanged,
      boolean allowDefaultEmptyEdsUpdate) {
    ResourceType requestResourceType = request.getResourceType();
    Preconditions.checkNotNull(requestResourceType, "unsupported type URL %s", request.getTypeUrl());
    Preconditions.checkArgument(requestResourceType.equals(resourceType),
        "request type %s does not match cache type %s", requestResourceType, resourceType);

    readLock.lock();
    try {
      CacheStatusInfo<T> status = statuses.getOrAddStatusInfo(group, requestResourceType);
      status.setLastWatchRequestTime(System.currentTimeMillis());

      String version = versionForNames(request.getResourceNamesList());

      Watch watch = new Watch(ads, allowDefaultEmptyEdsUpdate, request, responseConsumer);

      Set<String> requestedResources = ImmutableSet.copyOf(request.getResourceNamesList());

      // If the request asks for resources we have not sent yet, respond immediately when we have them.
      if (!knownResourceNames.equals(requestedResources)) {
        Sets.SetView<String> newResourceHints = Sets.difference(requestedResources, knownResourceNames);
        if (resources.keySet().stream().anyMatch(newResourceHints::contains)) {
          respond(watch, version);
          return watch;
        }
      } else if (hasClusterChanged && requestResourceType.equals(ResourceType.ENDPOINT)) {
        respond(watch, version);
        return watch;
      }

      // If the requester is already up-to-date, leave an open watch.
      if (request.getVersionInfo().equals(version)) {
        openWatch(status, watch, request.getTypeUrl(), request.getResourceNamesList(), request.getVersionInfo());
        return watch;
      }

      // Otherwise respond immediately; if the response was withheld (ADS, missing names), leave an open watch.
      boolean responded = respond(watch, version);
      if (!responded) {
        openWatch(status, watch, request.getTypeUrl(), request.getResourceNamesList(), request.getVersionInfo());
      }
      return watch;
    } finally {
      readLock.unlock();
    }
  }

  // =========================================================================================
  // Delta watch creation (ported from SimpleCache, operating on the flat resource map).
  // =========================================================================================

  @Override
  public DeltaWatch createDeltaWatch(
      DeltaXdsRequest request,
      String requesterVersion,
      Map<String, String> resourceVersions,
      Set<String> pendingResources,
      boolean isWildcard,
      Consumer<DeltaResponse> responseConsumer,
      boolean hasClusterChanged) {
    ResourceType requestResourceType = request.getResourceType();
    Preconditions.checkNotNull(requestResourceType, "unsupported type URL %s", request.getTypeUrl());
    Preconditions.checkArgument(requestResourceType.equals(resourceType),
        "request type %s does not match cache type %s", requestResourceType, resourceType);

    readLock.lock();
    try {
      DeltaCacheStatusInfo<T> status = statuses.getOrAddDeltaStatusInfo(group, requestResourceType);
      status.setLastWatchRequestTime(System.currentTimeMillis());

      String version = getVersion();

      DeltaWatch watch = new DeltaWatch(request,
          ImmutableMap.copyOf(resourceVersions),
          ImmutableSet.copyOf(pendingResources),
          requesterVersion,
          isWildcard,
          responseConsumer);

      // If the requester is up-to-date with the cache version, we may still owe pending resources.
      if (version.equals(requesterVersion)) {
        if (!isWildcard && !watch.pendingResources().isEmpty()) {
          Map<String, CachedResource> requestedResources = watch.pendingResources()
              .stream()
              .filter(resources::containsKey)
              .collect(Collectors.toMap(Function.identity(), resources::get));
          ResponseState state = respondDelta(watch, requestedResources, Collections.emptyList(), version, group);
          if (state.isFinished()) {
            return watch;
          }
        } else if (hasClusterChanged && requestResourceType.equals(ResourceType.ENDPOINT)) {
          ResponseState state = respondDelta(watch, version, group);
          if (state.isFinished()) {
            return watch;
          }
        }
        openWatch(status, watch, request.getTypeUrl(), watch.trackedResources().keySet(), requesterVersion);
        return watch;
      }

      // Version differs: respond with the diff between what the client tracks and the current cache.
      ResponseState state = respondDelta(watch, version, group);
      if (state.isFinished()) {
        return watch;
      }
      openWatch(status, watch, request.getTypeUrl(), watch.trackedResources().keySet(), requesterVersion);
      return watch;
    } finally {
      readLock.unlock();
    }
  }

  private <V extends AbstractWatch<?, ?>> void openWatch(MutableStatusInfo<T, V> status,
                                                         V watch,
                                                         String url,
                                                         Collection<String> resourceNames,
                                                         String version) {
    long watchId = watchCount.incrementAndGet();
    status.setWatch(watchId, watch);
    watch.setStop(() -> status.removeWatch(watchId));
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("open watch {} for {}[{}] in group {} for version {}",
          watchId, url, String.join(", ", resourceNames), group, version);
    }
  }

  // =========================================================================================
  // Response building (ported from SimpleCache).
  // =========================================================================================

  @GuardedBy("lock")
  private boolean respond(Watch watch, String version) {
    boolean allowDefaultResource = false;
    if (!watch.request().getResourceNamesList().isEmpty() && watch.ads()) {
      Collection<String> missingNames = watch.request().getResourceNamesList().stream()
          .filter(name -> !resources.containsKey(name))
          .collect(Collectors.toList());

      if (!missingNames.isEmpty()) {
        if (watch.allowDefaultEmptyEdsUpdate() && watch.request().getResourceType().equals(ResourceType.ENDPOINT)) {
          allowDefaultResource = true;
        } else {
          LOGGER.info(
              "not responding in ADS mode for {} in group {} at version {} for request [{}] since [{}] not in cache",
              watch.request().getTypeUrl(), group, version,
              String.join(", ", watch.request().getResourceNamesList()),
              String.join(", ", missingNames));
          return false;
        }
      }
    }

    Response response = createResponse(watch.request(), version, allowDefaultResource);
    try {
      watch.respond(response);
      return true;
    } catch (WatchCancelledException e) {
      LOGGER.error("failed to respond for {} in group {} at version {} because watch was already cancelled",
          watch.request().getTypeUrl(), group, version);
    }
    return false;
  }

  @GuardedBy("lock")
  private Response createResponse(XdsRequest request, String version, boolean allowDefaultResource) {
    Collection<Message> filtered;
    if (request.getResourceNamesList().isEmpty()) {
      filtered = resources.values().stream().map(CachedResource::resource).collect(Collectors.toList());
    } else {
      filtered = request.getResourceNamesList().stream()
          .map(name -> {
            CachedResource cached = resources.get(name);
            if (cached != null) {
              return cached.resource();
            }
            return allowDefaultResource ? defaultResource(name, request.getResourceType()) : null;
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }
    return Response.create(request, filtered, version);
  }

  @GuardedBy("lock")
  private ResponseState respondDelta(DeltaWatch watch, String version, T group) {
    Map<String, CachedResource> changed = new HashMap<>();
    List<String> removed = new ArrayList<>();
    // Compare the full cache against what the client tracks/pends (used on the initial / version-mismatch path).
    for (Map.Entry<String, CachedResource> entry : resources.entrySet()) {
      String name = entry.getKey();
      if (watch.pendingResources().contains(name)) {
        changed.put(name, entry.getValue());
      } else {
        String trackedVersion = watch.trackedResources().get(name);
        if (trackedVersion == null) {
          if (watch.isWildcard()) {
            changed.put(name, entry.getValue());
          }
        } else if (!entry.getValue().version().equals(trackedVersion)) {
          changed.put(name, entry.getValue());
        }
      }
    }
    for (String name : watch.trackedResources().keySet()) {
      if (!resources.containsKey(name)) {
        removed.add(name);
      }
    }
    return respondDelta(watch, changed, removed, version, group);
  }

  @GuardedBy("lock")
  private ResponseState respondDelta(DeltaWatch watch,
                                     Map<String, CachedResource> resourcesToSend,
                                     List<String> removedResources,
                                     String version,
                                     T group) {
    if (resourcesToSend.isEmpty() && removedResources.isEmpty()) {
      return ResponseState.UNRESPONDED;
    }
    // Only the resources actually being sent get their (lazy) version computed and wrapped here.
    Map<String, VersionedResource<?>> versioned = new HashMap<>(resourcesToSend.size());
    resourcesToSend.forEach((name, cached) -> versioned.put(name, cached.toVersionedResource()));

    DeltaResponse response = DeltaResponse.create(watch.request(), versioned, removedResources, version);
    try {
      watch.respond(response);
      return ResponseState.RESPONDED;
    } catch (WatchCancelledException e) {
      LOGGER.error("failed to respond delta for {} in group {} with version {} because watch was already cancelled",
          watch.request().getTypeUrl(), group, version);
    }
    return ResponseState.CANCELLED;
  }

  private Message defaultResource(String resourceName, ResourceType resourceType) {
    if (resourceType.equals(ResourceType.ENDPOINT)) {
      return ClusterLoadAssignment.newBuilder().setClusterName(resourceName).build();
    }
    throw new IllegalArgumentException(String.format("no default resource for resourceType: [%s]", resourceType));
  }

  // =========================================================================================
  // Versioning.
  // =========================================================================================

  @GuardedBy("lock")
  private String getVersion() {
    return versionPrefix + version;
  }

  /**
   * Computes the SOTW version for a specific set of requested resource names. For a wildcard request (empty
   * names) this is the cache-global version (changes on any modification). For a named request it is a
   * deterministic hash over just those resources' content versions, so a watch is only considered out-of-date
   * when one of <em>its</em> resources actually changed — avoiding spurious pushes to unrelated watches.
   */
  @GuardedBy("lock")
  private String versionForNames(Collection<String> names) {
    if (names.isEmpty()) {
      return getVersion();
    }
    Hasher hasher = Hashing.sha256().newHasher();
    names.stream().sorted().forEach(name -> {
      CachedResource cached = resources.get(name);
      hasher.putString(name, StandardCharsets.UTF_8)
          .putString("=", StandardCharsets.UTF_8)
          .putString(cached == null ? "" : cached.version(), StandardCharsets.UTF_8)
          .putString(";", StandardCharsets.UTF_8);
    });
    return hasher.hash().toString();
  }

  // =========================================================================================
  // Cache<T>.
  // =========================================================================================

  @Override
  public Collection<T> groups() {
    return ImmutableSet.copyOf(statuses.groups());
  }

  @Override
  public StatusInfo<T> statusInfo(T group) {
    readLock.lock();
    try {
      Map<ResourceType, CacheStatusInfo<T>> statusMap = statuses.getStatus(group);
      Map<ResourceType, DeltaCacheStatusInfo<T>> deltaStatusMap = statuses.getDeltaStatus(group);
      if (statusMap.isEmpty() && deltaStatusMap.isEmpty()) {
        return null;
      }
      List<StatusInfo<T>> collection = new ArrayList<>();
      collection.addAll(statusMap.values());
      collection.addAll(deltaStatusMap.values());
      return new GroupCacheStatusInfo<>(collection);
    } finally {
      readLock.unlock();
    }
  }

  /**
   * A resource held in the cache whose strong content-hash version is computed lazily on first use and then
   * memoized — mirroring go-control-plane's {@code cached_resource}. Resources never sent to or compared for a
   * watch are therefore never marshaled.
   */
  private static final class CachedResource {
    private final Message resource;
    private final Supplier<String> version;

    CachedResource(Message resource) {
      this.resource = Preconditions.checkNotNull(resource, "resource");
      this.version = Suppliers.memoize(() -> VersionedResource.contentHash(resource));
    }

    Message resource() {
      return resource;
    }

    String version() {
      return version.get();
    }

    VersionedResource<?> toVersionedResource() {
      return VersionedResource.create(resource, version.get());
    }
  }

  private enum ResponseState {
    RESPONDED,
    UNRESPONDED,
    CANCELLED;

    private boolean isFinished() {
      return this.equals(RESPONDED) || this.equals(CANCELLED);
    }
  }
}
