package io.envoyproxy.controlplane.cache;

import java.util.Collections;
import java.util.List;

public interface ResourceVersionResolver {
  String version(List<String> resourceNames);

  default String version() {
    return version(Collections.emptyList());
  }
}
