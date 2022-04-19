package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class WatchTest {

  @Test
  public void adsReturnsGivenValue() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    Watch watch = new Watch(ads, XdsRequest.create(DiscoveryRequest.getDefaultInstance()),
        r -> { });

    assertThat(watch.ads()).isEqualTo(ads);
  }

  @Test
  public void isCancelledTrueAfterCancel() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    Watch watch = new Watch(ads, XdsRequest.create(DiscoveryRequest.getDefaultInstance()), r -> { });

    assertThat(watch.isCancelled()).isFalse();

    watch.cancel();

    assertThat(watch.isCancelled()).isTrue();
  }

  @Test
  public void cancelWithStopCallsStop() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    AtomicInteger stopCount = new AtomicInteger();

    Watch watch = new Watch(ads, XdsRequest.create(DiscoveryRequest.getDefaultInstance()), r -> { });

    watch.setStop(stopCount::getAndIncrement);

    assertThat(watch.isCancelled()).isFalse();

    watch.cancel();
    watch.cancel();

    assertThat(stopCount).hasValue(1);

    assertThat(watch.isCancelled()).isTrue();
  }

  @Test
  public void responseHandlerExecutedForResponsesUntilCancelled() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    Response response1 = Response.create(
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()),
        ImmutableList.of(),
        UUID.randomUUID().toString());

    Response response2 = Response.create(
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()),
        ImmutableList.of(),
        UUID.randomUUID().toString());

    Response response3 = Response.create(
        XdsRequest.create(DiscoveryRequest.getDefaultInstance()),
        ImmutableList.of(),
        UUID.randomUUID().toString());

    List<Response> responses = new LinkedList<>();

    Watch watch = new Watch(ads, XdsRequest.create(DiscoveryRequest.getDefaultInstance()), responses::add);

    try {
      watch.respond(response1);
      watch.respond(response2);
    } catch (WatchCancelledException e) {
      fail("watch should not be cancelled", e);
    }

    watch.cancel();

    assertThatThrownBy(() -> watch.respond(response3)).isInstanceOf(WatchCancelledException.class);

    assertThat(responses).containsExactly(response1, response2);
  }
}
