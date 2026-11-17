package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.artifact.ArtifactStore;
import io.cmdb.collector.support.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({JpaArtifactStore.class})
class JpaArtifactStoreIT extends DatabaseTest {

  @Autowired ArtifactStore artifacts;

  @Test
  void ensureExistsIsKeyedByRegistryRepositoryAndDigest() {
    String repo = "team/shop-" + run;
    artifacts.ensureExists("acr.example.io", repo, "1.2", "sha256:aaa" + run);
    artifacts.ensureExists("acr.example.io", repo, "retagged", "sha256:aaa" + run); // same digest
    artifacts.ensureExists("acr.example.io", repo, "1.3", "sha256:bbb" + run); // new digest
    artifacts.ensureExists("other.example.io", repo, "1.2", "sha256:aaa" + run); // other registry

    assertThat(count("SELECT count(*) FROM artifacts WHERE repository=?", repo)).isEqualTo(3);
    // the first tag seen for a digest is kept
    assertThat(
            row(
                    "SELECT tag FROM artifacts WHERE registry='acr.example.io' AND repository=? AND digest=?",
                    repo,
                    "sha256:aaa" + run)
                .get("tag"))
        .isEqualTo("1.2");
  }

  @Test
  void aMissingTagIsAllowed() {
    String repo = "team/notag-" + run;
    artifacts.ensureExists("acr.example.io", repo, null, "sha256:ccc" + run);

    assertThat(row("SELECT tag FROM artifacts WHERE repository=?", repo).get("tag")).isNull();
  }
}
