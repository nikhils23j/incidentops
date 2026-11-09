package dev.nikhil.incidentops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the tests can move forward, so "wait 6 minutes for the SLA" takes zero real time. */
public class MutableClock extends Clock {
    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private volatile Instant now = START;

    public void set(Instant instant) { now = instant; }
    public void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
