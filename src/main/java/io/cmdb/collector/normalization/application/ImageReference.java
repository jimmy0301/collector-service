package io.cmdb.collector.normalization.application;

import java.util.Optional;

/**
 * Parsing of container image strings: registry / repository / tag, and the digest of an imageID.
 */
record ImageReference(String registry, String repository, String tag) {

  private static final String DEFAULT_REGISTRY = "docker.io";

  /**
   * Parses forms such as {@code acr.example.io/team/shop:1.2}, {@code nginx} and {@code
   * host:5000/a/b@sha256:...}.
   */
  static Optional<ImageReference> parse(String image) {
    if (image == null || image.isBlank()) {
      return Optional.empty();
    }
    String name = image;
    int at = name.indexOf('@');
    if (at >= 0) {
      name = name.substring(0, at);
    }
    String tag = null;
    int lastSlash = name.lastIndexOf('/');
    int colon = name.lastIndexOf(':');
    if (colon > lastSlash) {
      tag = name.substring(colon + 1);
      name = name.substring(0, colon);
    }
    if (name.isBlank()) {
      return Optional.empty();
    }
    String registry = DEFAULT_REGISTRY;
    String repository = name;
    int firstSlash = name.indexOf('/');
    if (firstSlash > 0) {
      String first = name.substring(0, firstSlash);
      if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
        registry = first;
        repository = name.substring(firstSlash + 1);
      }
    }
    if (DEFAULT_REGISTRY.equals(registry) && !repository.contains("/")) {
      repository = "library/" + repository;
    }
    return Optional.of(new ImageReference(registry, repository, tag));
  }

  /**
   * Returns the {@code sha256:...} after the {@code @} in an imageID. Returns empty when there is
   * no {@code @} (only the image config id), because that is not a registry manifest digest and
   * cannot be matched to artifacts.
   */
  static Optional<String> digestOf(String imageId) {
    if (imageId == null) {
      return Optional.empty();
    }
    int at = imageId.lastIndexOf('@');
    if (at < 0) {
      return Optional.empty();
    }
    String digest = imageId.substring(at + 1);
    return digest.startsWith("sha256:") ? Optional.of(digest) : Optional.empty();
  }
}
