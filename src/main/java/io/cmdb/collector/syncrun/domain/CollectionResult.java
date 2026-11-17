package io.cmdb.collector.syncrun.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of one collect call, which may track several sync runs (one per site, cluster or
 * project): the worst status among them, how many runs actually happened (0 when nothing was
 * configured or in scope, so a caller can tell "nothing to do" from "succeeded"), and why the runs
 * that were not successful failed ("provider: reason", at most {@value #MAX_ERRORS} of them).
 */
public record CollectionResult(SyncStatus status, int runs, List<String> errors) {

  static final int MAX_ERRORS = 20;
  private static final int MAX_ERROR_LENGTH = 500;

  public CollectionResult {
    errors = List.copyOf(errors);
  }

  public CollectionResult(SyncStatus status, int runs) {
    this(status, runs, List.of());
  }

  public static CollectionResult none() {
    return new CollectionResult(SyncStatus.SUCCESS, 0);
  }

  /** Adds one more tracked run; FAILED outranks PARTIAL, which outranks SUCCESS. */
  public CollectionResult with(SyncResult next) {
    List<String> all = new ArrayList<>(errors);
    if (next.status() != SyncStatus.SUCCESS && all.size() < MAX_ERRORS) {
      all.add(describe(next));
    }
    return new CollectionResult(worse(status, next.status()), runs + 1, all);
  }

  /** Adds a run whose reason is not known (or not needed). */
  public CollectionResult with(SyncStatus next) {
    return new CollectionResult(worse(status, next), runs + 1, errors);
  }

  private static String describe(SyncResult run) {
    String reason = run.error() == null ? run.status().name() : run.error();
    String text = run.provider() + ": " + reason;
    return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
  }

  private static SyncStatus worse(SyncStatus a, SyncStatus b) {
    return rank(b) > rank(a) ? b : a;
  }

  private static int rank(SyncStatus s) {
    return switch (s) {
      case SUCCESS -> 0;
      case RUNNING -> 1; // never returned by track(); treated as not-yet-successful
      case PARTIAL -> 2;
      case FAILED -> 3;
    };
  }
}
