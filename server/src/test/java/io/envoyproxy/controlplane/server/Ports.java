package io.envoyproxy.controlplane.server;

import java.io.IOException;
import java.net.ServerSocket;

public class Ports {

  /**
   * Returns a random available port.
   */
  public static int getAvailablePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException("Failed to get an available port", e);
    }
  }

  private Ports() { }
}
