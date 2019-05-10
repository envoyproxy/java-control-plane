package io.envoyproxy.controlplane.server.limits;

import com.google.common.util.concurrent.RateLimiter;

/**
 * {@link RequestLimiter} which delegates permit acquisition to Guava's {@link RateLimiter}.
 */
public class GuavaRequestLimiter implements RequestLimiter {

  private final RateLimiter rateLimiter;

  /**
   * {@link RequestLimiter} delegating to Guava's {@link RateLimiter}.
   * @param rateLimiter instance of Guava's rate limiter configured by the user.
   */
  public GuavaRequestLimiter(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  @Override
  public void acquire() {
    rateLimiter.acquire();
  }
}
