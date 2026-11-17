package io.cmdb.collector.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the few tests that need the WHOLE application (pipeline, end-to-end, wiring). Do
 * not use it for a single class: prefer a plain unit test, or {@link DatabaseTest} when the SQL
 * itself is what is being tested.
 */
@SpringBootTest(
    properties = {
      // Keep schedulers from really calling the integration services, purging or resubmitting
      // during tests
      "collector.argo.initial-delay=1h",
      "collector.argo.cluster-initial-delay=1h",
      "collector.k8s.initial-delay=1h",
      "collector.ado.initial-delay=1h",
      "collector.normalization.resubmit-initial-delay=1h",
      "collector.sync-run-purge-cron=-",
      "collector.normalization.max-attempts=2"
    })
public abstract class PostgresIntegrationTest {

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SharedPostgres.DB::getJdbcUrl);
    registry.add("spring.datasource.username", SharedPostgres.DB::getUsername);
    registry.add("spring.datasource.password", SharedPostgres.DB::getPassword);
  }
}
