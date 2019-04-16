package io.envoyproxy.controlplane.server.limits;

import com.google.common.util.concurrent.RateLimiter;

public class GuavaRequestLimiter implements RequestLimiter {
  private final RateLimiter rateLimiter;

  public GuavaRequestLimiter(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  @Override
  public void acquire() {
    rateLimiter.acquire();
  }
}
