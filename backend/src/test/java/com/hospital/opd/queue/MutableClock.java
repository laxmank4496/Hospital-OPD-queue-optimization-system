package com.hospital.opd.queue;

import java.time.*;

/** Test clock that only moves when told to. */
public class MutableClock extends Clock {
    private volatile long millis;
    public MutableClock(String isoInstant) { this.millis = Instant.parse(isoInstant).toEpochMilli(); }
    public void advanceMinutes(double m) { millis += (long) (m * 60_000); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    @Override public long millis() { return millis; }
}
