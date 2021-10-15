package io.envoyproxy.controlplane.cache;

public class DeltaCacheStatusInfo<T> extends MutableStatusInfo<T, DeltaWatch> {

  public DeltaCacheStatusInfo(T nodeGroup) {
    super(nodeGroup);
  }
}
