package io.envoyproxy.controlplane.server;

import java.util.concurrent.Executor;

/**
 * The {@link ExecutorGroup} is responsible for providing the {@link Executor}'s to use
 * via its {@link #next()} method.
 */
public interface ExecutorGroup {
  /**
   * Returns the next {@link Executor} to use.
   */
  Executor next();
}
