package io.envoyproxy.controlplane.cache;

public class SnapshotConsistencyException extends Exception {

  public SnapshotConsistencyException(String message) {
    super(message);
  }
}
