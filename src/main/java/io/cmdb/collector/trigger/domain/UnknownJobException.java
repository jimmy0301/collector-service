package io.cmdb.collector.trigger.domain;

/** The requested source or job does not exist. */
public class UnknownJobException extends RuntimeException {

  public UnknownJobException(String message) {
    super(message);
  }
}
