package io.envoyproxy.controlplane.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import org.junit.Test;

/**
 * Tests for {@link VersionedResource#createWithContentHash(com.google.protobuf.Message)}, the strong
 * content-hash version used by {@link LinearCache}. The key property — relied on for TLS certificate
 * rotation — is that the version changes iff the resource content changes.
 */
public class VersionedResourceTest {

  private static Secret secret(String name, String cert) {
    return Secret.newBuilder()
        .setName(name)
        .setTlsCertificate(TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString(cert).build()))
        .build();
  }

  @Test
  public void sameContentYieldsSameVersion() {
    String v1 = VersionedResource.createWithContentHash(secret("s", "cert-a")).version();
    String v2 = VersionedResource.createWithContentHash(secret("s", "cert-a")).version();
    assertThat(v1).isEqualTo(v2);
  }

  @Test
  public void differentContentYieldsDifferentVersion() {
    // This is the certificate-rotation guarantee: new cert content => new version => Envoy refetches.
    String oldCert = VersionedResource.createWithContentHash(secret("s", "old-cert")).version();
    String newCert = VersionedResource.createWithContentHash(secret("s", "new-cert")).version();
    assertThat(oldCert).isNotEqualTo(newCert);
  }

  @Test
  public void versionIsSha256Hex() {
    String version = VersionedResource.createWithContentHash(secret("s", "cert")).version();
    assertThat(version).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  public void preservesResource() {
    Secret secret = secret("s", "cert");
    assertThat(VersionedResource.createWithContentHash(secret).resource()).isEqualTo(secret);
  }
}
