package io.envoyproxy.controlplane.server.exception;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import javax.annotation.Nullable;

public class RequestException extends StatusException {
  public RequestException(Status status) {
    this(status, null);
  }

  public RequestException(Status status, @Nullable Metadata trailers) {
    super(status, trailers);
  }
}
