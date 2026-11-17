package io.cmdb.collector.inventory.infrastructure;

import io.cmdb.collector.inventory.domain.site.SiteSnapshot;
import io.cmdb.collector.inventory.domain.site.SiteStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaSiteStore implements SiteStore {

  private final SpringDataSiteRepository sites;

  JpaSiteStore(SpringDataSiteRepository sites) {
    this.sites = sites;
  }

  @Override
  @Transactional
  public void upsert(SiteSnapshot site) {
    sites.upsert(site.code(), site.name(), site.region());
  }

  @Override
  @Transactional
  public void ensureExists(String code) {
    sites.insertIfAbsent(code);
  }
}
