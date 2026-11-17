package io.cmdb.collector.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncResult;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.trigger.application.CollectionTrigger;
import io.cmdb.collector.trigger.domain.CollectionJob;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract of the trigger API (paths, bodies, status codes) through the real Spring MVC setup:
 * only this controller and the web layer start, with Boot's JSON and error-body configuration. The
 * security filters are switched off here; who may call it is TriggerSecurityTest.
 */
@WebMvcTest(controllers = TriggerController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TriggerControllerTest.Jobs.class)
class TriggerControllerTest {

  static final List<String> ran = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class Jobs {
    @Bean
    CollectionTrigger trigger() {
      return new CollectionTrigger(
          List.of(
              job("argo", "sites", CollectionResult.none().with(SyncStatus.SUCCESS)),
              job(
                  "argo",
                  "applications",
                  CollectionResult.none()
                      .with(new SyncResult("ARGO:PLANT-A", SyncStatus.PARTIAL, "tree shop: 502"))),
              job("k8s", "workloads", CollectionResult.none().with(SyncStatus.SUCCESS)),
              CollectionJob.of(
                  "ado",
                  "releases",
                  () -> {
                    ran.add("ado/releases");
                    throw new IllegalStateException("hub down");
                  })));
    }

    static CollectionJob job(String source, String name, CollectionResult result) {
      return CollectionJob.of(
          source,
          name,
          () -> {
            ran.add(source + "/" + name);
            return result;
          });
    }
  }

  @Autowired MockMvc mvc;

  @BeforeEach
  void clear() {
    ran.clear();
  }

  @Test
  void triggersOneJobAndReturnsItsOutcomeAndWhyItWasNotSuccessful() throws Exception {
    mvc.perform(post("/collect/argo/applications"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("argo"))
        .andExpect(jsonPath("$.jobs[0].job").value("applications"))
        .andExpect(jsonPath("$.jobs[0].status").value("PARTIAL"))
        .andExpect(jsonPath("$.jobs[0].runs").value(1))
        .andExpect(jsonPath("$.jobs[0].errors[0]").value("ARGO:PLANT-A: tree shop: 502"));
    assertThat(ran).containsExactly("argo/applications");
  }

  @Test
  void aSuccessfulJobHasAnEmptyErrorList() throws Exception {
    mvc.perform(post("/collect/argo/sites"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].status").value("SUCCESS"))
        .andExpect(jsonPath("$.jobs[0].errors").isEmpty());
  }

  @Test
  void triggersAWholeSourceInOrder() throws Exception {
    mvc.perform(post("/collect/argo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs.length()").value(2))
        .andExpect(jsonPath("$.jobs[0].job").value("sites"))
        .andExpect(jsonPath("$.jobs[1].job").value("applications"));
  }

  @Test
  void triggersEverySourceInRegistrationOrder() throws Exception {
    mvc.perform(post("/collect"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].source").value("argo"))
        .andExpect(jsonPath("$[1].source").value("k8s"))
        .andExpect(jsonPath("$[2].source").value("ado"));
  }

  @Test
  void listsTheJobs() throws Exception {
    mvc.perform(get("/collect"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.argo[0]").value("sites"))
        .andExpect(jsonPath("$.k8s[0]").value("workloads"))
        .andExpect(jsonPath("$.ado[0]").value("releases"));
  }

  @Test
  void aJobThatThrewReportsItsErrorInTheBodyInsteadOfFailingTheRequest() throws Exception {
    mvc.perform(post("/collect/ado/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].status").value("FAILED"))
        .andExpect(jsonPath("$.jobs[0].errors[0]").value(containsString("hub down")));
  }

  @Test
  void unknownSourceOrJobIs404WithTheReasonInTheBody() throws Exception {
    mvc.perform(post("/collect/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(containsString("unknown source: nope")));
    mvc.perform(post("/collect/argo/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value(containsString("unknown job: argo/nope")));
    assertThat(ran).isEmpty();
  }
}
