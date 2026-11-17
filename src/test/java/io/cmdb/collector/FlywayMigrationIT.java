package io.cmdb.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.cmdb.collector.support.SharedPostgres;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The migrations in db/migration build the whole schema on an empty database and can be applied
 * again without changes. Runs Flyway directly in a throwaway schema; no Spring context starts.
 */
class FlywayMigrationIT {

  private final String schema =
      "fw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

  private Flyway flyway() {
    return Flyway.configure()
        .dataSource(
            SharedPostgres.DB.getJdbcUrl(),
            SharedPostgres.DB.getUsername(),
            SharedPostgres.DB.getPassword())
        .schemas(schema)
        .createSchemas(true)
        .locations("classpath:db/migration")
        .load();
  }

  @Test
  void buildsTheWholeSchemaOnAnEmptyDatabase() {
    var result = flyway().migrate();

    assertThat(result.success).isTrue();
    assertThat(result.migrationsExecuted).isPositive();
    assertThat(tables())
        .contains(
            "integration_raw_events",
            "integration_sync_runs",
            "k8s_resources",
            "k8s_resource_history",
            "releases",
            "release_deployments",
            "deployments",
            "event_publication");
  }

  @Test
  void aSecondRunAppliesNothingAndValidates() {
    Flyway flyway = flyway();
    flyway.migrate();

    var again = flyway.migrate();

    assertThat(again.migrationsExecuted).isZero();
    flyway.validate();
  }

  private java.util.List<String> tables() {
    DataSource ds =
        new DriverManagerDataSource(
            SharedPostgres.DB.getJdbcUrl(),
            SharedPostgres.DB.getUsername(),
            SharedPostgres.DB.getPassword());
    return JdbcClient.create(ds)
        .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = ?")
        .param(schema)
        .query(String.class)
        .list();
  }
}
