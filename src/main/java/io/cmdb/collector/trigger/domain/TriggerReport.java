package io.cmdb.collector.trigger.domain;

import java.util.List;

/** The jobs of one source that ran, in the order they ran. */
public record TriggerReport(String source, List<JobReport> jobs) {}
