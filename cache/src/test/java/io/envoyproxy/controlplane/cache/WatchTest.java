package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import envoy.api.v2.Discovery.DiscoveryRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class WatchTest {

  @Test
  public void cancelTerminatesResponseStream() {
    Watch watch = new Watch(DiscoveryRequest.getDefaultInstance());

    assertThat(watch.valueEmitter().isTerminated()).isFalse();

    watch.cancel();

    assertThat(watch.valueEmitter().isTerminated()).isTrue();
  }

  @Test
  public void cancelWithStopTerminatesResponseStreamAndCallsStop() {
    AtomicInteger count = new AtomicInteger();

    Watch watch = new Watch(DiscoveryRequest.getDefaultInstance());

    watch.setStop(count::getAndIncrement);

    assertThat(watch.valueEmitter().isTerminated()).isFalse();

    watch.cancel();
    watch.cancel();

    assertThat(count).hasValue(1);

    assertThat(watch.valueEmitter().isTerminated()).isTrue();
  }
}
