package io.cmdb.collector.rawevent.domain;

/** A (provider, event type) pair that has raw events still PENDING. */
public record PendingKind(String provider, String eventType) {}
