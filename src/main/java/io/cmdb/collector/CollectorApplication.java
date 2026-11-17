package io.cmdb.collector;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Collector Service: only pulls data outward, lands it in integration_raw_events, then normalizes
 * it into the domain tables.
 *
 * <p>{@code sharedModules}: technical modules every module may depend on (leader election,
 * RestClient factory); they are not bounded contexts.
 */
@Modulithic(
    systemName = "CMDB Collector",
    sharedModules = {"leader", "http"})
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CollectorApplication {

  public static void main(String[] args) {
    SpringApplication.run(CollectorApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
