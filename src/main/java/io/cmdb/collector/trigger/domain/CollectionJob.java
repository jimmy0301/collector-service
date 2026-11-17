package io.cmdb.collector.trigger.domain;

import io.cmdb.collector.syncrun.domain.CollectionResult;
import java.util.function.Supplier;

/** One collect operation that can be started on demand, such as "argo / applications". */
public interface CollectionJob {

  /** The source system: argo, k8s or ado. */
  String source();

  /** The job within the source, such as applications or workloads. */
  String name();

  CollectionResult run();

  static CollectionJob of(String source, String name, Supplier<CollectionResult> action) {
    return new CollectionJob() {
      @Override
      public String source() {
        return source;
      }

      @Override
      public String name() {
        return name;
      }

      @Override
      public CollectionResult run() {
        return action.get();
      }
    };
  }
}
