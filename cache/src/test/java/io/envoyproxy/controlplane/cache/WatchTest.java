package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class WatchTest {

  @Test
  public void stopIsCalledOnCancel() {
    AtomicInteger count = new AtomicInteger();

    Watch watch = new Watch(ImmutableList.of(), ResourceType.LISTENER);

    watch.setStop(count::getAndIncrement);

    watch.cancel();
    watch.cancel();

    assertThat(count).hasValue(1);
  }
}
