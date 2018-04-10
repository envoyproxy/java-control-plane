package io.envoyproxy.controlplane.server;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

class EnvoyContainer extends GenericContainer<EnvoyContainer> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EnvoyContainer.class);

  private static final String CONFIG_DEST = "/etc/envoy/envoy.yaml";
  private static final String HOST_IP_SCRIPT = "docker/host_ip.sh";
  private static final String HOST_IP_SCRIPT_DEST = "/usr/local/bin/host_ip.sh";
  private static final String LAUNCH_ENVOY_SCRIPT = "envoy/launch_envoy.sh";
  private static final String LAUNCH_ENVOY_SCRIPT_DEST = "/usr/local/bin/launch_envoy.sh";

  static final int ADMIN_PORT = 9901;

  private final String config;
  private final Supplier<Integer> controlPlanePortSupplier;

  EnvoyContainer(String config, Supplier<Integer> controlPlanePortSupplier) {
    super("envoyproxy/envoy-alpine:latest");

    this.config = config;
    this.controlPlanePortSupplier = controlPlanePortSupplier;
  }

  @Override
  protected void configure() {
    super.configure();

    withClasspathResourceMapping(HOST_IP_SCRIPT, HOST_IP_SCRIPT_DEST, BindMode.READ_ONLY);
    withClasspathResourceMapping(LAUNCH_ENVOY_SCRIPT, LAUNCH_ENVOY_SCRIPT_DEST, BindMode.READ_ONLY);
    withClasspathResourceMapping(config, CONFIG_DEST, BindMode.READ_ONLY);

    withCommand(
        "/bin/sh", "/usr/local/bin/launch_envoy.sh",
        Integer.toString(controlPlanePortSupplier.get()),
        CONFIG_DEST,
        "-l", "debug");

    getExposedPorts().add(0, ADMIN_PORT);
  }

  @Override
  protected void containerIsStarting(InspectContainerResponse containerInfo) {
    followOutput(new Slf4jLogConsumer(LOGGER).withPrefix("ENVOY"));

    super.containerIsStarting(containerInfo);
  }
}
