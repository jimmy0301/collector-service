package io.cmdb.collector.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cmdb.collector.syncrun.domain.CollectionResult;
import io.cmdb.collector.syncrun.domain.SyncStatus;
import io.cmdb.collector.trigger.application.CollectionTrigger;
import io.cmdb.collector.trigger.domain.CollectionJob;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no token keys configured (no JwtDecoder, the default) the trigger API does not exist: 404.
 */
@WebMvcTest(controllers = TriggerController.class)
@Import({TriggerSecurityConfig.class, TriggerSecurityDisabledTest.Jobs.class})
class TriggerSecurityDisabledTest {

  static final List<String> ran = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class Jobs {
    @Bean
    CollectionTrigger trigger() {
      return new CollectionTrigger(
          List.of(
              CollectionJob.of(
                  "argo",
                  "sites",
                  () -> {
                    ran.add("argo/sites");
                    return CollectionResult.none().with(SyncStatus.SUCCESS);
                  })));
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void everyRequestIs404EvenWithATokenHeader() throws Exception {
    mvc.perform(post("/collect/argo/sites")).andExpect(status().isNotFound());
    mvc.perform(post("/collect/argo/sites").header("Authorization", "Bearer anything"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/collect").header("Authorization", "Bearer "))
        .andExpect(status().isNotFound());
    assertThat(ran).isEmpty();
  }
}
