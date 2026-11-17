package io.cmdb.collector.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.inventory.domain.site.SiteSnapshot;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import io.cmdb.collector.support.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({JpaSiteStore.class})
class JpaSiteStoreIT extends DatabaseTest {

  @Autowired SiteStore sites;

  String code() {
    return "S-" + run;
  }

  @Test
  void upsertInsertsAndTheNameDefaultsToTheCodeWhenMissing() {
    sites.upsert(new SiteSnapshot(code(), null, null));

    var r = row("SELECT name, region, status FROM sites WHERE code=?", code());
    assertThat(r.get("name")).isEqualTo(code());
    assertThat(r.get("region")).isNull();
    assertThat(r.get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void upsertUpdatesProvidedValuesButNeverBlanksExistingOnes() {
    sites.upsert(new SiteSnapshot(code(), "A Plant", "north"));

    sites.upsert(new SiteSnapshot(code(), null, null)); // a source that only knows the code
    var kept = row("SELECT name, region FROM sites WHERE code=?", code());
    assertThat(kept.get("name")).isEqualTo("A Plant");
    assertThat(kept.get("region")).isEqualTo("north");

    sites.upsert(new SiteSnapshot(code(), "A Plant (new)", null));
    var updated = row("SELECT name, region FROM sites WHERE code=?", code());
    assertThat(updated.get("name")).isEqualTo("A Plant (new)");
    assertThat(updated.get("region")).isEqualTo("north");
    assertThat(count("SELECT count(*) FROM sites WHERE code=?", code())).isEqualTo(1);
  }

  @Test
  void ensureExistsCreatesOnlyWhenAbsentAndNeverOverwrites() {
    sites.ensureExists(code());
    assertThat(row("SELECT name FROM sites WHERE code=?", code()).get("name")).isEqualTo(code());

    sites.upsert(new SiteSnapshot(code(), "Real Name", "south"));
    sites.ensureExists(code());

    var r = row("SELECT name, region FROM sites WHERE code=?", code());
    assertThat(r.get("name")).isEqualTo("Real Name");
    assertThat(r.get("region")).isEqualTo("south");
    assertThat(count("SELECT count(*) FROM sites WHERE code=?", code())).isEqualTo(1);
  }
}
