package io.cmdb.collector.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One empty PostgreSQL 17 container shared by the whole test JVM. The schema is created by Flyway
 * (db/migration), exactly like in production, when the first Spring context starts.
 */
public final class SharedPostgres {

  public static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17");

  static {
    DB.start(); // removed by Testcontainers' Ryuk when the JVM exits
  }

  private SharedPostgres() {}
}
