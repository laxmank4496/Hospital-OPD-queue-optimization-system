package com.hospital.opd.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Queue entry lifecycle. The transition table is the single source of truth for
 * what the backend accepts; anything else is rejected with InvalidTransitionException.
 *
 *   WAITING -> CHECKED_IN -> CALLED -> IN_CONSULTATION -> COMPLETED
 *   WAITING -> CANCELLED | NO_SHOW | LATE ;  CALLED -> LATE | NO_SHOW
 *   LATE -> CHECKED_IN | RESCHEDULED | NO_SHOW | CANCELLED
 */
public enum QueueStatus {
    WAITING, CHECKED_IN, CALLED, IN_CONSULTATION, COMPLETED, CANCELLED, NO_SHOW, LATE, RESCHEDULED;

    public Set<QueueStatus> allowedNext() {
        switch (this) {
            case WAITING:         return EnumSet.of(CHECKED_IN, CANCELLED, NO_SHOW, LATE);
            case CHECKED_IN:      return EnumSet.of(CALLED, CANCELLED, NO_SHOW);
            case CALLED:          return EnumSet.of(IN_CONSULTATION, NO_SHOW, LATE);
            case LATE:            return EnumSet.of(CHECKED_IN, RESCHEDULED, NO_SHOW, CANCELLED);
            case IN_CONSULTATION: return EnumSet.of(COMPLETED);
            default:              return EnumSet.noneOf(QueueStatus.class); // terminal
        }
    }

    public boolean canMoveTo(QueueStatus next) { return allowedNext().contains(next); }

    /** Entries that still occupy a place in a doctor's queue. */
    public boolean isActive() {
        return this == WAITING || this == CHECKED_IN || this == CALLED
            || this == IN_CONSULTATION || this == LATE;
    }

    /** Active entries being served right now (always "ahead" of everyone). */
    public boolean isInFlight() { return this == CALLED || this == IN_CONSULTATION; }

    public static final Set<QueueStatus> ACTIVE =
        EnumSet.of(WAITING, CHECKED_IN, CALLED, IN_CONSULTATION, LATE);
}
