package io.envoyproxy.controlplane.server.example;

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Learns which version of each resource type every connected Envoy has actually applied, from
 * the xDS conversation itself.
 *
 * <p>In state-of-the-world xDS, Envoy answers every {@link DiscoveryResponse} with a new {@link
 * DiscoveryRequest}. If {@code error_detail} is empty it is an ACK, and {@code version_info} is
 * the version Envoy applied. If {@code error_detail} is set it is a NACK: Envoy rejected the
 * response whose nonce it echoes, and keeps serving its previous config.
 *
 * <p>{@code SimpleCache.setSnapshot} only means "available"; waiting on this tracker means
 * "applied". A control plane that rolls config out in stages (for example canary first) should
 * wait for the ACK before judging or promoting the change. Register it with {@code new
 * V3DiscoveryServer(tracker, cache)}.
 */
public class AckTracker implements DiscoveryServerCallbacks {

  /** The outcome of waiting for a version. */
  public enum Status {
    /** Every connected Envoy in the group applied the version. */
    ACKED,
    /** At least one Envoy in the group rejected the version. */
    NACKED,
    /** Some Envoy has not answered yet. */
    TIMEOUT,
    /** No Envoy in the group is connected. */
    NO_NODES
  }

  /** What one ADS stream (one Envoy) has told us. */
  private static final class Stream {
    volatile String group;
    /** type URL -> last version ACKed / NACKed. */
    final Map<String, String> acked = new ConcurrentHashMap<>();
    final Map<String, String> nacked = new ConcurrentHashMap<>();
    /** response nonce -> version sent with it, so a NACK can name the version it rejects. */
    final Map<String, String> sent = new ConcurrentHashMap<>();
  }

  private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();

  @Override
  public void onStreamOpen(long streamId, String typeUrl) {
    streams.put(streamId, new Stream());
  }

  @Override
  public void onStreamClose(long streamId, String typeUrl) {
    streams.remove(streamId);
    signal();
  }

  @Override
  public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
    streams.remove(streamId);
    signal();
  }

  @Override
  public void onV3StreamResponse(
      long streamId, DiscoveryRequest request, DiscoveryResponse response) {
    Stream s = streams.get(streamId);
    if (s != null) {
      s.sent.put(response.getNonce(), response.getVersionInfo());
    }
  }

  @Override
  public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    Stream s = streams.computeIfAbsent(streamId, id -> new Stream());
    if (request.hasNode()) {
      s.group = request.getNode().getCluster();
    }
    if (request.getResponseNonce().isEmpty()) {
      return; // the first request for a type is a subscription, not a reply
    }
    String sentVersion = s.sent.remove(request.getResponseNonce());
    if (request.hasErrorDetail()) {
      if (sentVersion != null) {
        s.nacked.put(request.getTypeUrl(), sentVersion);
      }
    } else {
      s.acked.put(request.getTypeUrl(), request.getVersionInfo());
    }
    signal();
  }

  @Override
  public void onV3StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request) {
    // This example tracks state-of-the-world xDS only.
  }

  /**
   * Waits until every Envoy connected in {@code group} has ACKed {@code version} of {@code
   * typeUrl}, any of them NACKs it, or the timeout passes. Pass the latest version you pushed:
   * versions are compared for equality, since snapshot versions are opaque strings.
   */
  public Status await(String group, String typeUrl, String version, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    lock.lock();
    try {
      while (true) {
        Status now = status(group, typeUrl, version);
        if (now == Status.ACKED || now == Status.NACKED) {
          return now;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return now;
        }
        changed.awaitNanos(left);
      }
    } finally {
      lock.unlock();
    }
  }

  private Status status(String group, String typeUrl, String version) {
    boolean any = false;
    boolean all = true;
    for (Stream s : streams.values()) {
      if (!group.equals(s.group)) {
        continue;
      }
      any = true;
      if (version.equals(s.nacked.get(typeUrl))) {
        return Status.NACKED;
      }
      all &= version.equals(s.acked.get(typeUrl));
    }
    if (!any) {
      return Status.NO_NODES;
    }
    return all ? Status.ACKED : Status.TIMEOUT;
  }

  private void signal() {
    lock.lock();
    try {
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
