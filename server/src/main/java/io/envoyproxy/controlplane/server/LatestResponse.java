package io.envoyproxy.controlplane.server;

import java.util.Objects;
import java.util.Set;

class LatestResponse {

  private final String nonce;
  private final Set<String> resources;

  public LatestResponse(String nonce, Set<String> resources) {
    this.nonce = nonce;
    this.resources = resources;
  }

  public String getNonce() {
    return nonce;
  }

  public Set<String> getResources() {
    return resources;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LatestResponse that = (LatestResponse) o;
    return Objects.equals(nonce, that.nonce)
        && Objects.equals(resources, that.resources);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nonce, resources);
  }

  @Override
  public String toString() {
    return "LatestResponse{"
        + "nonce='" + nonce + '\''
        + ", resources=" + resources
        + '}';
  }
}
