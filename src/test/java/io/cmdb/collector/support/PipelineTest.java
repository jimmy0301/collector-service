package io.cmdb.collector.support;

import static io.cmdb.collector.support.Fakes.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cmdb.collector.rawevent.application.RawEventRecorder;
import io.cmdb.collector.rawevent.domain.RawEvent;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base class for tests of the whole pipeline: RawEventRecorder.record → Spring event (persisted by
 * Modulith) → async listener → handler → domain tables. Each test instance has its own random
 * suffix ({@link #payloads}), so tests sharing one database do not interfere with each other.
 */
public abstract class PipelineTest extends PostgresIntegrationTest {

  @Autowired protected RawEventRecorder recorder;
  @Autowired protected JdbcClient jdbc;

  protected final String run = UUID.randomUUID().toString().substring(0, 8);
  protected final TestPayloads payloads = new TestPayloads(run);
  protected final String site = payloads.site;
  protected final String clusterId = payloads.clusterId;
  protected final String depUid = payloads.depUid;
  protected final String rsUid = payloads.rsUid;
  protected final String podUid = payloads.podUid;

  /** Lands a raw event; the event id gets the run suffix so it is unique per test. */
  protected void record(
      String provider, String type, String id, String orderingKey, String payload) {
    recorder.record(new RawEvent(provider, type, id + "-" + run, orderingKey, json(payload)));
  }

  protected String status(String provider, String id) {
    return jdbc.sql(
            "SELECT process_status FROM integration_raw_events WHERE provider=? AND external_event_id=?")
        .params(provider, id + "-" + run)
        .query(String.class)
        .optional()
        .orElse("(none)");
  }

  protected void awaitStatus(String provider, String id, String expected) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(status(provider, id)).isEqualTo(expected));
  }

  protected void awaitProcessed(String provider, String id) {
    awaitStatus(provider, id, "PROCESSED");
  }

  protected Map<String, Object> resource(String uid) {
    return jdbc.sql("SELECT * FROM k8s_resources WHERE resource_uid=?")
        .param(uid)
        .query()
        .singleRow();
  }

  protected Map<String, Object> row(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query().singleRow();
  }

  protected int count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Integer.class).single();
  }

  /**
   * A timestamp column of k8s_events as HH:mm:ss in UTC (JDBC would render it in the JVM's time
   * zone).
   */
  protected String utcTime(String table, String column, String where, Object param) {
    return jdbc.sql(
            "SELECT to_char(%s AT TIME ZONE 'UTC', 'HH24:MI:SS') FROM %s WHERE %s"
                .formatted(column, table, where))
        .param(param)
        .query(String.class)
        .single();
  }
}
