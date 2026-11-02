package com.hospital.opd.queue;

import com.hospital.opd.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure functions: ordering, position, waiting-time baseline. No I/O, so it is trivially testable
 * and positions are always DERIVED from the live set of active entries, never stored.
 *
 * Effective priority (higher is served first):
 *   hospitalPriority                                  (staff-set, in "waiting-minute equivalents")
 * + (emergency ? emergencyPriority : 0)               (dominates everything)
 * + min(maxWaitBoost, minutesWaited * waitBoostPerMinute)   (fairness / anti-starvation)
 * Tie-break: earlier joinedAt, then lower tokenNumber.
 *
 * With hospitalPriority = 0 for everyone this is exactly FCFS. Because the boost is capped
 * (maxWaitBoost) and hospitalPriority is bounded by policy, a non-emergency patient who has waited
 * more than (hospitalPriority gap) minutes can never be overtaken - no starvation.
 */
public final class QueueOrdering {
    private QueueOrdering() {}

    public static double effectivePriority(QueueEntry e, long now, QueueConfig c) {
        double waited = Math.max(0, (now - e.joinedAt) / 60000.0);
        return e.hospitalPriority
             + (e.emergency ? c.emergencyPriority : 0)
             + Math.min(c.maxWaitBoost, waited * c.waitBoostPerMinute);
    }

    /** Full line: in-flight patients first (they are already being served), then by effective priority. */
    public static List<QueueEntry> ordered(Collection<QueueEntry> active, long now, QueueConfig c) {
        Comparator<QueueEntry> cmp = Comparator
            .comparing((QueueEntry e) -> e.status == QueueStatus.IN_CONSULTATION ? 0 : e.status == QueueStatus.CALLED ? 1 : 2)
            .thenComparing((QueueEntry e) -> -effectivePriority(e, now, c))
            .thenComparing(e -> e.joinedAt)
            .thenComparing(e -> e.tokenNumber);
        return active.stream().filter(e -> e.status.isActive()).sorted(cmp).collect(Collectors.toList());
    }

    /** Patients ahead = entries ranked before this one in the live ordering. -1 if not active. */
    public static int patientsAhead(String entryId, List<QueueEntry> ordered) {
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).queueEntryId.equals(entryId)) return i;
        return -1;
    }

    public static double baselineWait(int patientsAhead, double avgDurationMinutes) {
        return patientsAhead * avgDurationMinutes;
    }

    /** Next patient to call: highest-ranked entry that is physically present (CHECKED_IN). */
    public static Optional<QueueEntry> nextToCall(List<QueueEntry> ordered) {
        return ordered.stream().filter(e -> e.status == QueueStatus.CHECKED_IN).findFirst();
    }
}
