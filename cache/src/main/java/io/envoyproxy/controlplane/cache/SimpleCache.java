package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
public class SimpleCache<T> implements SnapshotCache<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

  private final NodeGroup<T> groups;

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private final Lock readLock = lock.readLock();
  private final Lock writeLock = lock.writeLock();

  @GuardedBy("lock")
  private final Map<T, Snapshot> snapshots = new HashMap<>();
  private final ConcurrentMap<T, ConcurrentMap<String, CacheStatusInfo<T>>> statuses = new ConcurrentHashMap<>();

  private AtomicLong watchCount = new AtomicLong();

  /**
   * Constructs a simple cache.
   *
   * @param groups maps an envoy host to a node group
   */
  public SimpleCache(NodeGroup<T> groups) {
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
      Map<String, CacheStatusInfo<T>> status = statuses.get(group);

      // If we don't know about this group, do nothing.
      if (status != null && status.values().stream().mapToLong(CacheStatusInfo::numWatches).sum() > 0) {
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
      DiscoveryRequest request,
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
      DiscoveryRequest request,
      Set<String> knownResourceNames,
      Consumer<Response> responseConsumer,
      boolean hasClusterChanged) {

    T group = groups.hash(request.getNode());
    // even though we're modifying, we take a readLock to allow multiple watches to be created in parallel since it
    // doesn't conflict
    readLock.lock();
    try {
      CacheStatusInfo<T> status = statuses.computeIfAbsent(group, g -> new ConcurrentHashMap<>())
          .computeIfAbsent(request.getTypeUrl(), s -> new CacheStatusInfo<>(group));
      status.setLastWatchRequestTime(System.currentTimeMillis());

      Snapshot snapshot = snapshots.get(group);
      String version = snapshot == null ? "" : snapshot.version(request.getTypeUrl(), request.getResourceNamesList());

      Watch watch = new Watch(ads, request, responseConsumer);

      if (snapshot != null) {
        Set<String> requestedResources = ImmutableSet.copyOf(request.getResourceNamesList());

        // If the request is asking for resources we haven't sent to the proxy yet, see if we have additional resources.
        if (!knownResourceNames.equals(requestedResources)) {
          Sets.SetView<String> newResourceHints = Sets.difference(requestedResources, knownResourceNames);

          // If any of the newly requested resources are in the snapshot respond immediately. If not we'll fall back to
          // version comparisons.
          if (snapshot.resources(request.getTypeUrl())
              .keySet()
              .stream()
              .anyMatch(newResourceHints::contains)) {
            respond(watch, snapshot, group);

            return watch;
          }
        } else if (hasClusterChanged && request.getTypeUrl().equals(Resources.ENDPOINT_TYPE_URL)) {
          respond(watch, snapshot, group);

          return watch;
        }
      }

      // If the requested version is up-to-date or missing a response, leave an open watch.
      if (snapshot == null || request.getVersionInfo().equals(version)) {
        long watchId = watchCount.incrementAndGet();

        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("open watch {} for {}[{}] from node {} for version {}",
              watchId,
              request.getTypeUrl(),
              String.join(", ", request.getResourceNamesList()),
              group,
              request.getVersionInfo());
        }

        status.setWatch(watchId, watch);

        watch.setStop(() -> status.removeWatch(watchId));

        return watch;
      }

      // Otherwise, the watch may be responded immediately
      boolean responded = respond(watch, snapshot, group);

      if (!responded) {
        long watchId = watchCount.incrementAndGet();

        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("did not respond immediately, leaving open watch {} for {}[{}] from node {} for version {}",
              watchId,
              request.getTypeUrl(),
              String.join(", ", request.getResourceNamesList()),
              group,
              request.getVersionInfo());
        }

        status.setWatch(watchId, watch);

        watch.setStop(() -> status.removeWatch(watchId));
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
  public Snapshot getSnapshot(T group) {
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
    return ImmutableSet.copyOf(statuses.keySet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void setSnapshot(T group, Snapshot snapshot) {
    // we take a writeLock to prevent watches from being created while we update the snapshot
    ConcurrentMap<String, CacheStatusInfo<T>> status;
    writeLock.lock();
    try {
      // Update the existing snapshot entry.
      snapshots.put(group, snapshot);
      status = statuses.get(group);
    } finally {
      writeLock.unlock();
    }

    if (status == null) {
      return;
    }

    // Responses should be in specific order and TYPE_URLS has a list of resources in the right order.
    respondWithSpecificOrder(group, snapshot, status);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StatusInfo statusInfo(T group) {
    readLock.lock();

    try {
      ConcurrentMap<String, CacheStatusInfo<T>> statusMap = statuses.get(group);
      if (statusMap == null || statusMap.isEmpty()) {
        return null;
      }

      return new GroupCacheStatusInfo<T>(statusMap.values());
    } finally {
      readLock.unlock();
    }
  }

  @VisibleForTesting
  protected void respondWithSpecificOrder(T group, Snapshot snapshot, ConcurrentMap<String, CacheStatusInfo<T>> statusMap) {
    for (String typeUrl : Resources.TYPE_URLS) {
      CacheStatusInfo<T> status = statusMap.get(typeUrl);
      if (status == null) {
        continue;
      }

      status.watchesRemoveIf((id, watch) -> {
        if (!watch.request().getTypeUrl().equals(typeUrl)) {
          return false;
        }
        String version = snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList());

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
  }

  private Response createResponse(DiscoveryRequest request, Map<String, ? extends Message> resources, String version) {
    Collection<? extends Message> filtered = request.getResourceNamesList().isEmpty()
        ? resources.values()
        : request.getResourceNamesList().stream()
        .map(resources::get)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    return Response.create(request, filtered, version);
  }

  private boolean respond(Watch watch, Snapshot snapshot, T group) {
    Map<String, ? extends Message> snapshotResources = snapshot.resources(watch.request().getTypeUrl());

    if (!watch.request().getResourceNamesList().isEmpty() && watch.ads()) {
      Collection<String> missingNames = watch.request().getResourceNamesList().stream()
          .filter(name -> !snapshotResources.containsKey(name))
          .collect(Collectors.toList());

      if (!missingNames.isEmpty()) {
        LOGGER.info(
            "not responding in ADS mode for {} from node {} at version {} for request [{}] since [{}] not in snapshot",
            watch.request().getTypeUrl(),
            group,
            snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList()),
            String.join(", ", watch.request().getResourceNamesList()),
            String.join(", ", missingNames));

        return false;
      }
    }

    String version = snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList());

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
}
