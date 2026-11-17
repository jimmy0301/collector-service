package io.cmdb.collector.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests of ONE persistence class (a store or repository) against a real PostgreSQL.
 * Only the JPA slice starts (entities, Spring Data repositories, DataSource), never the whole
 * application: each subclass pulls in just the class under test with {@code @Import}. Every test
 * runs in a transaction that is rolled back, and has a random suffix for its keys.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class DatabaseTest {

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SharedPostgres.DB::getJdbcUrl);
    registry.add("spring.datasource.username", SharedPostgres.DB::getUsername);
    registry.add("spring.datasource.password", SharedPostgres.DB::getPassword);
  }

  @Autowired protected JdbcClient jdbc;

  protected final String run = UUID.randomUUID().toString().substring(0, 8);

  protected Map<String, Object> row(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query().singleRow();
  }

  protected int count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Integer.class).single();
  }

  /** Reads a timestamptz as an Instant (never rendered in the JVM's time zone). */
  protected Instant instant(String sql, Object... params) {
    return jdbc.sql(sql)
        .params(params)
        .query(OffsetDateTime.class)
        .optional()
        .map(OffsetDateTime::toInstant)
        .orElse(null);
  }
}
