package io.cmdb.collector.normalization.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageReferenceTest {

  private static org.assertj.core.api.AbstractObjectAssert<?, ImageReference> assertRef(
      String image) {
    return assertThat(ImageReference.parse(image).orElseThrow());
  }

  @Test
  void parsesRegistryRepositoryAndTag() {
    assertRef("acr.example.io/team/shop:1.2.3")
        .extracting(ImageReference::registry, ImageReference::repository, ImageReference::tag)
        .containsExactly("acr.example.io", "team/shop", "1.2.3");
  }

  @Test
  void parsesRegistryWithPortAndDigest() {
    assertRef("reg.local:5000/a/b:v1@sha256:abc")
        .extracting(ImageReference::registry, ImageReference::repository, ImageReference::tag)
        .containsExactly("reg.local:5000", "a/b", "v1");
  }

  @Test
  void tagIsNullWhenOnlyDigestIsGiven() {
    assertRef("acr.example.io/shop@sha256:abc")
        .extracting(ImageReference::registry, ImageReference::repository, ImageReference::tag)
        .containsExactly("acr.example.io", "shop", null);
  }

  @Test
  void dockerHubImagesGetDefaultRegistryAndLibraryNamespace() {
    assertRef("nginx:1.27")
        .extracting(ImageReference::registry, ImageReference::repository, ImageReference::tag)
        .containsExactly("docker.io", "library/nginx", "1.27");
    assertRef("bitnami/redis")
        .extracting(ImageReference::registry, ImageReference::repository, ImageReference::tag)
        .containsExactly("docker.io", "bitnami/redis", null);
  }

  @Test
  void blankImageIsEmpty() {
    assertThat(ImageReference.parse(" ")).isEmpty();
    assertThat(ImageReference.parse(null)).isEmpty();
  }

  @Test
  void digestIsTakenAfterTheAtSign() {
    assertThat(ImageReference.digestOf("acr.example.io/team/shop@sha256:abc123"))
        .contains("sha256:abc123");
    assertThat(ImageReference.digestOf("docker-pullable://acr.example.io/shop@sha256:def"))
        .contains("sha256:def");
  }

  @Test
  void imageIdWithoutManifestDigestIsEmpty() {
    // Only the image config id (not the registry manifest digest); it cannot be matched to
    // artifacts
    assertThat(ImageReference.digestOf("sha256:abc")).isEmpty();
    assertThat(ImageReference.digestOf("")).isEmpty();
    assertThat(ImageReference.digestOf(null)).isEmpty();
  }
}
