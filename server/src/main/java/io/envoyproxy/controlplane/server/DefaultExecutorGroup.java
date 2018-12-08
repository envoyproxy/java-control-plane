package io.envoyproxy.controlplane.server;

import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executor;

/**
 * Default implementation of {@link ExecutorGroup} which
 * always returns {@link MoreExecutors#directExecutor}.
 */
public class DefaultExecutorGroup implements ExecutorGroup {
  /**
   * Returns the next {@link Executor} to use, which in this case is
   * always {@link MoreExecutors#directExecutor}.
   */
  @Override
  public Executor next() {
    return MoreExecutors.directExecutor();
  }
}
