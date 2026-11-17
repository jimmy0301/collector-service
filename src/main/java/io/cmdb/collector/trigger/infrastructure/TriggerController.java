package io.cmdb.collector.trigger.infrastructure;

import io.cmdb.collector.trigger.application.CollectionTrigger;
import io.cmdb.collector.trigger.domain.JobReport;
import io.cmdb.collector.trigger.domain.TriggerBusyException;
import io.cmdb.collector.trigger.domain.TriggerReport;
import io.cmdb.collector.trigger.domain.UnknownJobException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implements the generated {@link CollectApi} (contract: src/main/resources/openapi/trigger.yaml).
 * Starts collects on demand and returns when they have finished; the bearer token is checked by
 * {@link TriggerAuthInterceptor}.
 */
@RestController
class TriggerController implements CollectApi {

  private final CollectionTrigger trigger;

  TriggerController(CollectionTrigger trigger) {
    this.trigger = trigger;
  }

  @Override
  public ResponseEntity<Map<String, List<String>>> listCollectJobs() {
    return ResponseEntity.ok(trigger.available());
  }

  @Override
  public ResponseEntity<List<TriggerReportResponse>> collectAll() {
    return ResponseEntity.ok(trigger.runAll().stream().map(TriggerController::toResponse).toList());
  }

  @Override
  public ResponseEntity<TriggerReportResponse> collectSource(String source) {
    return ResponseEntity.ok(toResponse(run(source, null)));
  }

  @Override
  public ResponseEntity<TriggerReportResponse> collectJob(String source, String job) {
    return ResponseEntity.ok(toResponse(run(source, job)));
  }

  private TriggerReport run(String source, String job) {
    try {
      return trigger.run(source, job);
    } catch (UnknownJobException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (TriggerBusyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  private static TriggerReportResponse toResponse(TriggerReport report) {
    return new TriggerReportResponse(
        report.source(), report.jobs().stream().map(TriggerController::toResponse).toList());
  }

  private static JobReportResponse toResponse(JobReport job) {
    return new JobReportResponse(
        job.job(),
        SyncStatusResponse.fromValue(job.status().name()),
        job.runs(),
        job.durationMs(),
        job.errors());
  }
}
