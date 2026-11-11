package io.envoyproxy.controlplane.server.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.Status;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class AckTrackerTest {

  private static final String CLUSTERS = Resources.V3.CLUSTER_TYPE_URL;

  private final AckTracker tracker = new AckTracker();

  private void connect(long stream, String group) {
    tracker.onStreamOpen(stream, "");
    tracker.onV3StreamRequest(
        stream,
        DiscoveryRequest.newBuilder()
            .setNode(Node.newBuilder().setId("envoy-" + stream).setCluster(group))
            .setTypeUrl(CLUSTERS)
            .build());
  }

  private void send(long stream, String version) {
    tracker.onV3StreamResponse(
        stream,
        DiscoveryRequest.getDefaultInstance(),
        DiscoveryResponse.newBuilder()
            .setTypeUrl(CLUSTERS)
            .setVersionInfo(version)
            .setNonce("n" + version)
            .build());
  }

  private void ack(long stream, String version) {
    tracker.onV3StreamRequest(
        stream,
        DiscoveryRequest.newBuilder()
            .setTypeUrl(CLUSTERS)
            .setVersionInfo(version)
            .setResponseNonce("n" + version)
            .build());
  }

  private void nack(long stream, String version, String stillOn) {
    tracker.onV3StreamRequest(
        stream,
        DiscoveryRequest.newBuilder()
            .setTypeUrl(CLUSTERS)
            .setVersionInfo(stillOn)
            .setResponseNonce("n" + version)
            .setErrorDetail(Status.newBuilder().setMessage("invalid cluster"))
            .build());
  }

  private AckTracker.Status await(String group, String version) throws InterruptedException {
    return tracker.await(group, CLUSTERS, version, 100, TimeUnit.MILLISECONDS);
  }

  @Test
  public void ackedOnlyWhenEveryEnvoyInTheGroupAppliedTheVersion() throws Exception {
    connect(1, "canary");
    connect(2, "canary");
    send(1, "2");
    send(2, "2");
    ack(1, "2");
    assertThat(await("canary", "2")).isEqualTo(AckTracker.Status.TIMEOUT);
    ack(2, "2");
    assertThat(await("canary", "2")).isEqualTo(AckTracker.Status.ACKED);
  }

  @Test
  public void nackedWhenAnyEnvoyRejectsTheVersion() throws Exception {
    connect(1, "canary");
    send(1, "2");
    nack(1, "2", "1");
    assertThat(await("canary", "2")).isEqualTo(AckTracker.Status.NACKED);
  }

  @Test
  public void otherGroupsAndClosedStreamsDoNotCount() throws Exception {
    connect(1, "stable");
    send(1, "2");
    ack(1, "2");
    assertThat(await("canary", "2")).isEqualTo(AckTracker.Status.NO_NODES);
    tracker.onStreamClose(1, "");
    assertThat(await("stable", "2")).isEqualTo(AckTracker.Status.NO_NODES);
  }
}
