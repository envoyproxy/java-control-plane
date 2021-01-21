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

  private static final int DEFAULT_API_VERSION = 3;
  private static final String CONFIG_DEST = "/etc/envoy/envoy.yaml";
  private static final String HOST_IP_SCRIPT = "docker/host_ip.sh";
  private static final String HOST_IP_SCRIPT_DEST = "/usr/local/bin/host_ip.sh";
  private static final String LAUNCH_ENVOY_SCRIPT = "envoy/launch_envoy.sh";
  private static final String LAUNCH_ENVOY_SCRIPT_DEST = "/usr/local/bin/launch_envoy.sh";

  static final int ADMIN_PORT = 9901;

  private final String config;
  private final Supplier<Integer> controlPlanePortSupplier;
  private final int apiVersion;

  EnvoyContainer(String config, Supplier<Integer> controlPlanePortSupplier) {
    this(config, controlPlanePortSupplier, DEFAULT_API_VERSION);
  }

  EnvoyContainer(String config, Supplier<Integer> controlPlanePortSupplier, int apiVersion) {
    super("envoyproxy/envoy-alpine-dev:5c801b25cae04f06bf48248c90e87d623d7a6283");

    this.config = config;
    this.controlPlanePortSupplier = controlPlanePortSupplier;
    this.apiVersion = apiVersion;
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
        "-l", "debug",
        "--bootstrap-version", Integer.toString(apiVersion)
    );

    getExposedPorts().add(0, ADMIN_PORT);
  }

  @Override
  protected void containerIsStarting(InspectContainerResponse containerInfo) {
    followOutput(new Slf4jLogConsumer(LOGGER).withPrefix("ENVOY"));

    super.containerIsStarting(containerInfo);
  }
}
