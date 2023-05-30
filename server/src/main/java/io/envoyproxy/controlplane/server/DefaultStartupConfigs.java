package io.envoyproxy.controlplane.server;

public class DefaultStartupConfigs implements StartupConfigs {
  @Override
  public boolean allowDefaultEmptyEdsUpdate() {
    return false;
  }
}
