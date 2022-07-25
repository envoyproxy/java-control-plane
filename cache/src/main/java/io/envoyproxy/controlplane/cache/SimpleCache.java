package io.envoyproxy.controlplane.cache;

import static io.envoyproxy.controlplane.cache.Resources.RESOURCE_TYPES_IN_ORDER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
import java.util.stream.Stream;
import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SimpleCache} provides a default implementation of {@link SnapshotCache}. It maintains a single versioned
 * {@link Snapshot} per node group. For the protocol to work correctly in ADS mode, EDS/RDS requests are responded to
 * only when all resources in the snapshot xDS response are named as part of the request. It is expected that the CDS
 * response names all EDS clusters, and the LDS response names all RDS routes in a snapshot, to ensure that Envoy makes
 * the request for all EDS clusters or RDS routes eventually.
 *
 * <p>The snapshot can be partial, e.g. only include RDS or EDS resources.
 */
public abstract class SimpleCache<T, U extends Snapshot> implements SnapshotCache<T, U> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

  private final NodeGroup<T> groups;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Lock readLock = lock.readLock();
  private final Lock writeLock = lock.writeLock();

  @GuardedBy("lock")
  private final Map<T, U> snapshots = new HashMap<>();
  private final CacheStatusInfoAggregator<T> statuses = new CacheStatusInfoAggregator<>();

  private AtomicLong watchCount = new AtomicLong();

  /**
   * Constructs a simple cache.
   *
   * @param groups maps an envoy host to a node group
   */
  protected SimpleCache(NodeGroup<T> groups) {
    this.groups = groups;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean clearSnapshot(T group) {
    // we take a writeLock to prevent watches from being created
    writeLock.lock();
    try {
      // If we don't know about this group, do nothing.
      if (statuses.hasStatuses(group)) {
        LOGGER.warn("tried to clear snapshot for group with existing watches, group={}", group);

        return false;
      }

      statuses.remove(group);
      snapshots.remove(group);

      return true;
    } finally {
      writeLock.unlock();
    }
  }

  public Watch createWatch(
      boolean ads,
      XdsRequest request,
      Set<String> knownResourceNames,
      Consumer<Response> responseConsumer) {
    return createWatch(ads, request, knownResourceNames, responseConsumer, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Watch createWatch(
      boolean ads,
      XdsRequest request,
      Set<String> knownResourceNames,
      Consumer<Response> responseConsumer,
      boolean hasClusterChanged) {
    ResourceType requestResourceType = request.getResourceType();
    Preconditions.checkNotNull(requestResourceType, "unsupported type URL %s",
        request.getTypeUrl());
    T group;

    group = groups.hash(request.v3Request().getNode());

    // even though we're modifying, we take a readLock to allow multiple watches to be created in parallel since it
    // doesn't conflict
    readLock.lock();
    try {
      CacheStatusInfo<T> status = statuses.getOrAddStatusInfo(group, requestResourceType);
      status.setLastWatchRequestTime(System.currentTimeMillis());

      U snapshot = snapshots.get(group);
      String version = snapshot == null ? "" : snapshot.version(requestResourceType,
          request.getResourceNamesList());

      Watch watch = new Watch(ads, request, responseConsumer);

      if (snapshot != null) {
        Set<String> requestedResources = ImmutableSet.copyOf(request.getResourceNamesList());

        // If the request is asking for resources we haven't sent to the proxy yet, see if we have additional resources.
        if (!knownResourceNames.equals(requestedResources)) {
          Sets.SetView<String> newResourceHints = Sets.difference(requestedResources, knownResourceNames);

          // If any of the newly requested resources are in the snapshot respond immediately. If not we'll fall back to
          // version comparisons.
          if (snapshot.resources(requestResourceType)
              .keySet()
              .stream()
              .anyMatch(newResourceHints::contains)) {
            respond(watch, snapshot, group);

            return watch;
          }
        } else if (hasClusterChanged
            && (requestResourceType.equals(ResourceType.ENDPOINT))) {
          respond(watch, snapshot, group);

          return watch;
        }
      }

      // If the requested version is up-to-date or missing a response, leave an open watch.
      if (snapshot == null || request.getVersionInfo().equals(version)) {
        openWatch(status, watch, request.getTypeUrl(), request.getResourceNamesList(), group, request.getVersionInfo());
        return watch;
      }

      // Otherwise, the watch may be responded immediately
      boolean responded = respond(watch, snapshot, group);

      if (!responded) {
        openWatch(status, watch, request.getTypeUrl(), request.getResourceNamesList(), group, request.getVersionInfo());
      }

      return watch;
    } finally {
      readLock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
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
    Preconditions.checkNotNull(requestResourceType, "unsupported type URL %s",
        request.getTypeUrl());
    T group;
    group = groups.hash(request.v3Request().getNode());

    // even though we're modifying, we take a readLock to allow multiple watches to be created in parallel since it
    // doesn't conflict
    readLock.lock();
    try {
      DeltaCacheStatusInfo<T> status = statuses.getOrAddDeltaStatusInfo(group, requestResourceType);
      status.setLastWatchRequestTime(System.currentTimeMillis());

      U snapshot = snapshots.get(group);
      String version = snapshot == null ? "" : snapshot.version(requestResourceType, Collections.emptyList());

      DeltaWatch watch = new DeltaWatch(request,
          ImmutableMap.copyOf(resourceVersions),
          ImmutableSet.copyOf(pendingResources),
          requesterVersion,
          isWildcard,
          responseConsumer);

      // If no snapshot, leave an open watch.

      if (snapshot == null) {
        openWatch(status, watch, request.getTypeUrl(), watch.trackedResources().keySet(), group, requesterVersion);
        return watch;
      }

      // If the requested version is up-to-date or missing a response, leave an open watch.
      if (version.equals(requesterVersion)) {
        // If the request is not wildcard, we have pending resources and we have them, we should respond immediately.
        if (!isWildcard && watch.pendingResources().size() != 0) {
          // If any of the pending resources are in the snapshot respond immediately. If not we'll fall back to
          // version comparisons.
          Map<String, VersionedResource<?>> resources = snapshot.versionedResources(request.getResourceType());
          Map<String, VersionedResource<?>> requestedResources = watch.pendingResources()
              .stream()
              .filter(resources::containsKey)
              .collect(Collectors.toMap(Function.identity(), resources::get));
          ResponseState responseState = respondDelta(watch,
              requestedResources,
              Collections.emptyList(),
              version,
              group);
          if (responseState.isFinished()) {
            return watch;
          }
        } else if (hasClusterChanged && requestResourceType.equals(ResourceType.ENDPOINT)) {
          ResponseState responseState = respondDelta(request, watch, snapshot, version, group);
          if (responseState.isFinished()) {
            return watch;
          }
        }

        openWatch(status, watch, request.getTypeUrl(), watch.trackedResources().keySet(), group, requesterVersion);

        return watch;
      }

      // Otherwise, version is different, the watch may be responded immediately
      ResponseState responseState = respondDelta(request, watch, snapshot, version, group);
      if (responseState.isFinished()) {
        return watch;
      }

      openWatch(status, watch, request.getTypeUrl(), watch.trackedResources().keySet(), group, requesterVersion);
      return watch;
    } finally {
      readLock.unlock();
    }
  }

  private <V extends AbstractWatch<?, ?>> void openWatch(MutableStatusInfo<T, V> status,
                                                         V watch,
                                                         String url,
                                                         Collection<String> resources,
                                                         T group,
                                                         String version) {
    long watchId = watchCount.incrementAndGet();
    status.setWatch(watchId, watch);
    watch.setStop(() -> status.removeWatch(watchId));
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("open watch {} for {}[{}] from node {} for version {}",
          watchId,
          url,
          String.join(", ", resources),
          group,
          version);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public U getSnapshot(T group) {
    readLock.lock();

    try {
      return snapshots.get(group);
    } finally {
      readLock.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Collection<T> groups() {
    return ImmutableSet.copyOf(statuses.groups());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void setSnapshot(T group, U snapshot) {
    // we take a writeLock to prevent watches from being created while we update the snapshot
    Map<ResourceType, CacheStatusInfo<T>> status;
    Map<ResourceType, DeltaCacheStatusInfo<T>> deltaStatus;
    U previousSnapshot;
    writeLock.lock();
    try {
      // Update the existing snapshot entry.
      previousSnapshot = snapshots.put(group, snapshot);
      status = statuses.getStatus(group);
      deltaStatus = statuses.getDeltaStatus(group);
    } finally {
      writeLock.unlock();
    }

    if (status.isEmpty() && deltaStatus.isEmpty()) {
      return;
    }

    // Responses should be in specific order and typeUrls has a list of resources in the right
    // order.
    respondWithSpecificOrder(group, previousSnapshot, snapshot, status, deltaStatus);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StatusInfo<T> statusInfo(T group) {
    readLock.lock();

    try {
      Map<ResourceType, CacheStatusInfo<T>> statusMap = statuses.getStatus(group);
      Map<ResourceType, DeltaCacheStatusInfo<T>> deltaStatusMap = statuses.getDeltaStatus(group);

      if (statusMap.isEmpty() && deltaStatusMap.isEmpty()) {
        return null;
      }

      List<StatusInfo<T>> collection = Stream.concat(statusMap.values().stream(),
          deltaStatusMap.values().stream()).collect(Collectors.toList());

      return new GroupCacheStatusInfo<>(collection);
    } finally {
      readLock.unlock();
    }
  }

  @VisibleForTesting
  protected void respondWithSpecificOrder(T group,
                                          U previousSnapshot, U snapshot,
                                          Map<ResourceType, CacheStatusInfo<T>> statusMap,
                                          Map<ResourceType, DeltaCacheStatusInfo<T>> deltaStatusMap) {
    for (ResourceType resourceType : RESOURCE_TYPES_IN_ORDER) {
      CacheStatusInfo<T> status = statusMap.get(resourceType);
      if (status != null) {
        status.watchesRemoveIf((id, watch) -> {
          if (!watch.request().getResourceType().equals(resourceType)) {
            return false;
          }
          String version = snapshot.version(watch.request().getResourceType(),
              watch.request().getResourceNamesList());

          if (!watch.request().getVersionInfo().equals(version)) {
            if (LOGGER.isDebugEnabled()) {
              LOGGER.debug("responding to open watch {}[{}] with new version {}",
                  id,
                  String.join(", ", watch.request().getResourceNamesList()),
                  version);
            }

            respond(watch, snapshot, group);

            // Discard the watch. A new watch will be created for future snapshots once envoy ACKs the response.
            return true;
          }

          // Do not discard the watch. The request version is the same as the snapshot version, so we wait to respond.
          return false;
        });
      }

      DeltaCacheStatusInfo<T> deltaStatus = deltaStatusMap.get(resourceType);
      if (deltaStatus != null) {
        Map<String, VersionedResource<?>> previousResources = previousSnapshot == null
            ? Collections.emptyMap()
            : previousSnapshot.versionedResources(resourceType);
        Map<String, VersionedResource<?>> snapshotResources = snapshot.versionedResources(resourceType);

        Map<String, VersionedResource<?>> snapshotChangedResources = snapshotResources.entrySet()
            .stream()
            .filter(entry -> {
              VersionedResource<?> versionedResource = previousResources.get(entry.getKey());
              return versionedResource == null || !versionedResource
                  .version().equals(entry.getValue().version());
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Set<String> snapshotRemovedResources = previousResources.keySet()
            .stream()
            .filter(s -> !snapshotResources.containsKey(s))
            .collect(Collectors.toSet());
        deltaStatus.watchesRemoveIf((id, watch) -> {
          String version = snapshot.version(watch.request().getResourceType(), Collections.emptyList());

          if (!watch.version().equals(version)) {
            if (LOGGER.isDebugEnabled()) {
              LOGGER.debug("responding to open watch {}[{}] with new version {}",
                  id,
                  String.join(", ", watch.trackedResources().keySet()),
                  version);
            }

            List<String> removedResources = snapshotRemovedResources.stream()
                .filter(s -> watch.trackedResources().get(s) != null)
                .collect(Collectors.toList());

            Map<String, VersionedResource<?>> changedResources = findChangedResources(watch, snapshotChangedResources);


            ResponseState responseState = respondDelta(watch,
                changedResources,
                removedResources,
                version,
                group);
            // Discard the watch if it was responded or cancelled.
            // A new watch will be created for future snapshots once envoy ACKs the response.
            return responseState.isFinished();
          }

          // Do not discard the watch. The request version is the same as the snapshot version, so we wait to respond.
          return false;
        });
      }
    }
  }

  private Response createResponse(XdsRequest request, Map<String, VersionedResource<?>> resources,
                                  String version) {
    Collection<? extends Message> filtered = request.getResourceNamesList().isEmpty()
        ? resources.values().stream()
        .map(VersionedResource::resource)
        .collect(Collectors.toList())
        : request.getResourceNamesList().stream()
        .map(resources::get)
        .filter(Objects::nonNull)
        .map(VersionedResource::resource)
        .collect(Collectors.toList());

    return Response.create(request, filtered, version);
  }

  private boolean respond(Watch watch, U snapshot, T group) {
    Map<String, VersionedResource<?>> snapshotResources =
        snapshot.versionedResources(watch.request().getResourceType());

    if (!watch.request().getResourceNamesList().isEmpty() && watch.ads()) {
      Collection<String> missingNames = watch.request().getResourceNamesList().stream()
          .filter(name -> !snapshotResources.containsKey(name))
          .collect(Collectors.toList());

      if (!missingNames.isEmpty()) {
        LOGGER.info(
            "not responding in ADS mode for {} from node {} at version {} for request [{}] since [{}] not in snapshot",
            watch.request().getTypeUrl(),
            group,
            snapshot.version(watch.request().getResourceType(), watch.request().getResourceNamesList()),
            String.join(", ", watch.request().getResourceNamesList()),
            String.join(", ", missingNames));

        return false;
      }
    }

    String version = snapshot.version(watch.request().getResourceType(),
        watch.request().getResourceNamesList());

    LOGGER.debug("responding for {} from node {} at version {} with version {}",
        watch.request().getTypeUrl(),
        group,
        watch.request().getVersionInfo(),
        version);

    Response response = createResponse(
        watch.request(),
        snapshotResources,
        version);

    try {
      watch.respond(response);
      return true;
    } catch (WatchCancelledException e) {
      LOGGER.error(
          "failed to respond for {} from node {} at version {} with version {} because watch was already cancelled",
          watch.request().getTypeUrl(),
          group,
          watch.request().getVersionInfo(),
          version);
    }

    return false;
  }

  private List<String> findRemovedResources(DeltaWatch watch, Map<String, VersionedResource<?>> snapshotResources) {
    // remove resources for which client has a tracked version but do not exist in snapshot
    return watch.trackedResources().keySet()
        .stream()
        .filter(s -> !snapshotResources.containsKey(s))
        .collect(Collectors.toList());
  }

  private Map<String, VersionedResource<?>> findChangedResources(DeltaWatch watch,
                                                                 Map<String, VersionedResource<?>> snapshotResources) {
    return snapshotResources.entrySet()
        .stream()
        .filter(entry -> {
          if (watch.pendingResources().contains(entry.getKey())) {
            return true;
          }
          String resourceVersion = watch.trackedResources().get(entry.getKey());
          if (resourceVersion == null) {
            // resource is not tracked, should respond it only if watch is wildcard
            return watch.isWildcard();
          }
          return !entry.getValue().version().equals(resourceVersion);
        })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }


  private ResponseState respondDelta(DeltaXdsRequest request, DeltaWatch watch, U snapshot, String version, T group) {
    Map<String, VersionedResource<?>> snapshotResources = snapshot.versionedResources(request.getResourceType());
    List<String> removedResources = findRemovedResources(watch,
        snapshotResources);
    Map<String, VersionedResource<?>> changedResources = findChangedResources(watch, snapshotResources);
    return respondDelta(watch,
        changedResources,
        removedResources,
        version,
        group);
  }

  private ResponseState respondDelta(DeltaWatch watch,
                                     Map<String, VersionedResource<?>> resources,
                                     List<String> removedResources,
                                     String version,
                                     T group) {
    if (resources.isEmpty() && removedResources.isEmpty()) {
      return ResponseState.UNRESPONDED;
    }

    DeltaResponse response = DeltaResponse.create(
        watch.request(),
        resources,
        removedResources,
        version);

    try {
      watch.respond(response);
      return ResponseState.RESPONDED;
    } catch (WatchCancelledException e) {
      LOGGER.error(
          "failed to respond for {} from node {} with version {} because watch was already cancelled",
          watch.request().getTypeUrl(),
          group,
          version);
    }

    return ResponseState.CANCELLED;
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
