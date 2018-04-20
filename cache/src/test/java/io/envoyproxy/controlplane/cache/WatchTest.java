package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import envoy.api.v2.Discovery.DiscoveryRequest;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import reactor.core.publisher.EmitterProcessor;

public class WatchTest {

  @Test
  public void adsReturnsGivenValue() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    Watch watch = new Watch(ads, DiscoveryRequest.getDefaultInstance());

    assertThat(watch.ads()).isEqualTo(ads);
  }

  @Test
  public void cancelTerminatesResponseStream() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    Watch watch = new Watch(ads, DiscoveryRequest.getDefaultInstance());

    assertThat(((EmitterProcessor<Response>) watch.value()).isTerminated()).isFalse();

    watch.cancel();

    assertThat(((EmitterProcessor<Response>) watch.value()).isTerminated()).isTrue();
  }

  @Test
  public void cancelWithStopTerminatesResponseStreamAndCallsStop() {
    final boolean ads = ThreadLocalRandom.current().nextBoolean();

    AtomicInteger count = new AtomicInteger();

    Watch watch = new Watch(ads, DiscoveryRequest.getDefaultInstance());

    watch.setStop(count::getAndIncrement);

    assertThat(((EmitterProcessor<Response>) watch.value()).isTerminated()).isFalse();

    watch.cancel();
    watch.cancel();

    assertThat(count).hasValue(1);

    assertThat(((EmitterProcessor<Response>) watch.value()).isTerminated()).isTrue();
  }
}
