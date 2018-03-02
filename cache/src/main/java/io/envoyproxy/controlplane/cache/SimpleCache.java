package io.envoyproxy.controlplane.cache;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;
import envoy.api.v2.core.Base.Node;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SimpleCache} is a snapshot-based cache that maintains a single versioned snapshot of responses per node group,
 * with no canary updates. SimpleCache does not track status of remote nodes and consistently replies with the latest
 * snapshot. For the protocol to work correctly, EDS/RDS requests are responded only when all resources in the snapshot
 * xDS response are named as part of the request. It is expected that the CDS response names all EDS clusters, and the
 * LDS response names all RDS routes in a snapshot, to ensure that Envoy makes the request for all EDS clusters or RDS
 * routes eventually.
 *
 * <p>SimpleCache can also be used as a config cache for distinct xDS requests. The snapshots are required to contain
 * only the responses for the particular type of the xDS service that the cache serves. Synchronization of multiple
 * caches for different response types is left to the configuration producer.
 */
public class SimpleCache<T> implements Cache<T> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

  private final Consumer<T> callback;
  private final NodeGroup<T> groups;
  private final ExecutorService executorService;

  private final Object lock = new Object();

  @GuardedBy("lock")
  private final Map<T, Snapshot> snapshots = new HashMap<>();
  @GuardedBy("lock")
  private final Map<T, Map<Long, Watch>> watches = new HashMap<>();

  @GuardedBy("lock")
  private long watchCount;

  /**
   * Constructs a simple cache that uses {@link ForkJoinPool#commonPool()} to execute async callbacks.
   *
   * @param callback callback invoked when a group is seen for the first time
   * @param groups maps an envoy host to a node group
   */
  public SimpleCache(Consumer<T> callback, NodeGroup groups) {
    this(callback, groups, ForkJoinPool.commonPool());
  }

  /**
   * Constructs a simple cache.
   *
   * @param callback callback invoked when a group is seen for the first time
   * @param groups maps an envoy host to a node group
   * @param executorService executor service used to execute async callbacks
   */
  public SimpleCache(Consumer<T> callback, NodeGroup groups, ExecutorService executorService) {
    this.callback = callback;
    this.groups = groups;
    this.executorService = executorService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSnapshot(T group, Snapshot snapshot) {
    synchronized (lock) {
      // Update the existing entry.
      snapshots.put(group, snapshot);

      // Trigger existing watches.
      if (watches().containsKey(group)) {
        watches().get(group).values().forEach(watch -> respond(watch, snapshot, group));

        // Discard all watches; the client must request a new watch to receive updates and ACK/NACK.
        watches().remove(group);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Watch watch(ResourceType type, Node node, String version, Collection<String> names) {
    Watch watch = new Watch(names, type);

    final T group;

    // Do nothing if group hashing failed.
    try {
      group = groups.hash(node);
    } catch (Exception e) {
      LOGGER.error("failed to hash node {} into group", node, e);

      return watch;
    }

    synchronized (lock) {
      // If the requested version is up-to-date or missing a response, leave an open watch.
      Snapshot snapshot = snapshots.get(group);

      if (snapshot == null || snapshot.version().equals(version)) {
        if (snapshot == null && callback != null) {
          LOGGER.info("callback {} at {}", group, version);

          // TODO(jbratton): Should we track these CFs somewhere (e.g. to force completion on shutdown)?
          executorService.submit(() -> callback.accept(group));
        }

        LOGGER.info("open watch for {}[{}] from key {} from version {}",
            type,
            String.join(", ", names),
            group,
            version);

        watchCount++;

        long id = watchCount;

        watches().computeIfAbsent(group, g -> new HashMap<>()).put(id, watch);

        watch.setStop(() -> {
          synchronized (lock) {
            Map<Long, Watch> groupWatches = watches().get(group);

            if (groupWatches != null) {
              groupWatches.remove(id);
            }
          }
        });

        return watch;
      }

      // Otherwise, the watch may be responded immediately.
      respond(watch, snapshot, group);
      return watch;
    }
  }

  private void respond(Watch watch, Snapshot snapshot, T group) {
    Collection<Message> snapshotResources = snapshot.resources().get(watch.type());

    // Remove clean-up as the watch is discarded immediately
    watch.setStop(null);

    // The request names must match the snapshot names. If they do not, then the watch is never responded, and it is
    // expected that envoy makes another request.
    if (!watch.names().isEmpty()) {
      Set<String> watchedResourceNames = new HashSet<>(watch.names());

      Optional<String> missingResourceName = snapshotResources.stream()
          .map(Resources::getResourceName)
          .filter(n -> !watchedResourceNames.contains(n))
          .findFirst();

      if (missingResourceName.isPresent()) {
        LOGGER.info("not responding for {} from {} at {} since {} not requested [{}]",
            watch.type(),
            group,
            snapshot.version(),
            missingResourceName.get(),
            String.join(", ", watch.names()));

        return;
      }
    }

    watch.valueEmitter().onNext(Response.create(false, snapshotResources, snapshot.version()));
  }

  @VisibleForTesting
  Map<T, Map<Long, Watch>> watches() {
    return watches;
  }
}
