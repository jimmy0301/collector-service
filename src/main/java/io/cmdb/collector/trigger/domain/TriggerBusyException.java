package io.cmdb.collector.trigger.domain;

/** A collect of the same source is already being triggered; try again when it has finished. */
public class TriggerBusyException extends RuntimeException {

  public TriggerBusyException(String source) {
    super("a collect of " + source + " is already running");
  }
}
