package io.envoyproxy.controlplane.server;

import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

class EchoContainer extends GenericContainer<EchoContainer> {

  public static final Integer PORT = 5678;

  public final String response = UUID.randomUUID().toString();

  EchoContainer() {
    super("hashicorp/http-echo:latest");
  }

  @Override
  protected void configure() {
    super.configure();

    getExposedPorts().add(0, PORT);

    withCommand(String.format("-text=%s", response));

    waitingFor(Wait.forHttp("/").forStatusCode(200));
  }

  public String ipAddress() {
    return getContainerInfo()
        .getNetworkSettings()
        .getNetworks()
        .get(((Network.NetworkImpl) getNetwork()).getName())
        .getIpAddress();
  }
}
