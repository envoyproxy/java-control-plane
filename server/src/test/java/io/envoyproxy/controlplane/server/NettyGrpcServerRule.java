package io.envoyproxy.controlplane.server;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyGrpcServerRule extends ExternalResource {

  private static final Logger logger = LoggerFactory.getLogger(NettyGrpcServerRule.class);

  private Server server;
  private MutableHandlerRegistry serviceRegistry;

  /**
   * Returns the underlying gRPC {@link Server} for this service.
   */
  public final Server getServer() {
    return server;
  }

  /**
   * Returns the service registry for this service. The registry is used to add service instances
   * (e.g. {@link BindableService} or {@link ServerServiceDefinition} to the server.
   */
  public final MutableHandlerRegistry getServiceRegistry() {
    return serviceRegistry;
  }

  /**
   * After the test has completed, clean up the channel and server.
   */
  @Override
  protected void after() {
    serviceRegistry = null;

    server.shutdown();

    try {
      server.awaitTermination(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      server.shutdownNow();
      server = null;
    }
  }

  /**
   * Before the test has started, create the server and channel.
   */
  @Override
  protected void before() throws Throwable {
    serviceRegistry = new MutableHandlerRegistry();

    NettyServerBuilder serverBuilder = NettyServerBuilder.forPort(getAvailablePort())
        .fallbackHandlerRegistry(serviceRegistry);

    configureServerBuilder(serverBuilder);

    server = serverBuilder.build().start();
    logger.info("Started gRPC server on port: " + server.getPort());
  }

  protected void configureServerBuilder(NettyServerBuilder builder) throws Throwable {

  }

  private int getAvailablePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException("Failed to get available port", e);
    }
  }
}
