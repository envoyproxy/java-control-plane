package io.envoyproxy.controlplane.v3.server.exception;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import javax.annotation.Nullable;

public class RequestException extends StatusRuntimeException {
  public RequestException(Status status) {
    this(status, null);
  }

  public RequestException(Status status, @Nullable Metadata trailers) {
    super(status, trailers);
  }
}
