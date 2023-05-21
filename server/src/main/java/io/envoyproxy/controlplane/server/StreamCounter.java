package io.envoyproxy.controlplane.server;

import java.util.concurrent.atomic.AtomicLong;

public final class StreamCounter {
  private static final AtomicLong streamCount = new AtomicLong();

  public static long getAndIncrement() {
    return streamCount.getAndIncrement();
  }
}
