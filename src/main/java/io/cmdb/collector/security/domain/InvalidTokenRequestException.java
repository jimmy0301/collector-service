package io.cmdb.collector.security.domain;

/** The request asked for something the token policy does not allow. */
public class InvalidTokenRequestException extends RuntimeException {

  public InvalidTokenRequestException(String message) {
    super(message);
  }
}
