package io.envoyproxy.controlplane.server;

import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executor;

/**
 * Default implementation of {@link ExecutorGroup} which
 * always returns {@link com.google.common.util.concurrent.MoreExecutors#directExecutor()}.
 */
public class DefaultExecutorGroup implements ExecutorGroup {
  /**
   * Creates a new instance.
   */
  public DefaultExecutorGroup() {
  }

  @Override
  public Executor next() {
    return MoreExecutors.directExecutor();
  }
}
