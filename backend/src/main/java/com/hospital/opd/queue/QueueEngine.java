package com.hospital.opd.queue;

import com.hospital.opd.model.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The core domain service. Every state change is ONE store transaction that:
 *   1. loads the rows it needs, 2. validates the state transition, 3. mutates in memory,
 *   4. writes the results, 5. returns events that are published only AFTER commit.
 * Network calls (ML service) never happen inside a transaction.
 * Mutating operations are idempotent: repeating the same request returns the current state
 * instead of failing or double-applying.
 */
public class QueueEngine {

    public interface EventSink { void publish(List<QueueEvent> events); }

    public record JoinRequest(String patientId, String patientName, String doctorId, String departmentId,
                              Source source, int hospitalPriority, boolean emergency, String idempotencyKey) {}

    public record QueueView(String queueEntryId, String token, QueueStatus status, int position, int patientsAhead,
                            double baselineWaitMinutes, String doctorId, String departmentId) {}

    public record DoctorLoad(String doctorId, String name, DoctorStatus status, int patientsAhead, int queueLength,
                             double avgConsultationMinutes, double maxCurrentWaitMinutes, boolean idle,
                             double baselineWaitMinutes, List<Double> recentDurations) {}

    public record CapacitySummary(String departmentId, int dailyCapacity, int normalCapacity, int emergencyReserve,
                                  int normalUsed, int emergencyUsed, int remainingNormal, int remainingReserve) {}

    private final QueueStore store;
    private final Supplier<QueueConfig> config;
    private final Clock clock;
    private final EventSink sink;

    public QueueEngine(QueueStore store, Supplier<QueueConfig> config, Clock clock, EventSink sink) {
        this.store = store; this.config = config; this.clock = clock; this.sink = sink;
    }

    // ------------------------------------------------------------------ plumbing

    private final class Ctx {
        final QueueStore.Tx tx; final long now = clock.millis(); final QueueConfig cfg = config.get();
        final List<QueueEvent> events = new ArrayList<>();
        Ctx(QueueStore.Tx tx) { this.tx = tx; }
        void emit(EventType t, QueueEntry e, String detail) {
            QueueEvent ev = new QueueEvent(t, e == null ? null : e.queueEntryId, e == null ? null : e.doctorId,
                e == null ? null : e.departmentId, now, detail);
            ev.eventId = UUID.randomUUID().toString();
            events.add(ev); tx.put(ev);
        }
    }
    private record Result<T>(T value, List<QueueEvent> events) {}

    private <T> T inTx(Function<Ctx, T> work) {
        Result<T> r = store.transact(tx -> { Ctx c = new Ctx(tx); T v = work.apply(c); return new Result<>(v, c.events); });
        if (sink != null && !r.events().isEmpty()) sink.publish(r.events());
        return r.value();
    }

    private String dayKey(long now) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), clock.getZone()).toLocalDate().toString();
    }

    static double effectiveAverage(Doctor d, QueueConfig cfg) {
        return d.averageConsultationDuration > 0 ? d.averageConsultationDuration : cfg.defaultConsultationMinutes;
    }

    /** Change status or reject. Same-state request is an idempotent no-op (returns false). */
    private static boolean move(QueueEntry e, QueueStatus to) {
        if (e.status == to) return false;
        if (!e.status.canMoveTo(to)) throw new InvalidTransitionException(e.status, to);
        e.status = to;
        return true;
    }

    private QueueEntry load(Ctx c, String id) {
        return c.tx.entry(id).orElseThrow(() -> new QueueException("NOT_FOUND", "Queue entry not found."));
    }

    /** Loads the entry together with its doctor's active line so the SAME object is mutated and written. */
    private record Loaded(QueueEntry entry, List<QueueEntry> active) {}
    private Loaded loadWithLine(Ctx c, String id) {
        QueueEntry e = load(c, id);
        List<QueueEntry> active = c.tx.activeEntriesForDoctor(e.doctorId);
        for (int i = 0; i < active.size(); i++) if (active.get(i).queueEntryId.equals(id)) { e = active.get(i); break; }
        return new Loaded(e, active);
    }

    // ------------------------------------------------------------------ join

    public QueueEntry joinQueue(JoinRequest r) {
        return inTx(c -> {
            if (r.idempotencyKey() != null) {
                Optional<QueueEntry> existing = c.tx.entryByIdempotencyKey(r.idempotencyKey());
                if (existing.isPresent()) return existing.get();
            }
            Doctor d = c.tx.doctor(r.doctorId()).orElseThrow(() -> new QueueException("NOT_FOUND", "Doctor not found."));
            if (d.status != DoctorStatus.ONLINE || !d.departmentIds.contains(r.departmentId()))
                throw new QueueException("DOCTOR_UNAVAILABLE", "Doctor is no longer available.");
            Department dep = c.tx.department(r.departmentId())
                .orElseThrow(() -> new QueueException("NOT_FOUND", "Department not found."));
            if (r.patientId() != null && c.tx.activeEntryForPatient(r.patientId()).isPresent())
                throw new QueueException("ALREADY_IN_QUEUE", "You already have an active queue entry.");

            List<QueueEntry> active = c.tx.activeEntriesForDoctor(d.doctorId);
            if (active.size() >= c.cfg.maxActivePerDoctor)
                throw new QueueException("QUEUE_FULL", "This queue is currently full.");

            // Emergency reserve: normal joins may only use normalCapacity = daily - reserve.
            String day = dayKey(c.now);
            DailyUsage u = c.tx.usage(dep.departmentId, day);
            if (r.emergency()) {
                if (u.emergencyUsed < dep.emergencyReserve) u.emergencyUsed++;
                else if (u.normalUsed < dep.normalCapacity()) u.normalUsed++;      // reserve exhausted: spill into unused normal slots
                else throw new QueueException("CAPACITY_FULL", "Daily capacity is full.");
            } else {
                if (u.normalUsed >= dep.normalCapacity())
                    throw new QueueException("CAPACITY_FULL",
                        "Daily capacity for regular patients is full. Remaining slots are reserved for emergencies.");
                u.normalUsed++;
            }
            c.tx.put(u);

            // Token: atomic per-(department, day) counter. Never client-generated.
            int n = c.tx.nextTokenNumber(dep.departmentId, day);
            QueueEntry e = new QueueEntry();
            e.queueEntryId = UUID.randomUUID().toString();
            e.tokenNumber = n;
            e.token = dep.code + "-" + String.format("%03d", n);
            e.patientId = r.patientId(); e.patientName = r.patientName();
            e.doctorId = d.doctorId; e.departmentId = dep.departmentId;
            e.source = r.source(); e.hospitalPriority = r.hospitalPriority(); e.emergency = r.emergency();
            e.idempotencyKey = r.idempotencyKey();
            e.joinedAt = c.now;
            // Walk-ins are physically present, so they enter already checked in. Same engine, same queue.
            e.status = r.source() == Source.WALK_IN ? QueueStatus.CHECKED_IN : QueueStatus.WAITING;
            if (e.status == QueueStatus.CHECKED_IN) e.checkedInAt = c.now;

            List<QueueEntry> line = new ArrayList<>(active); line.add(e);
            int ahead = QueueOrdering.patientsAhead(e.queueEntryId, QueueOrdering.ordered(line, c.now, c.cfg));
            double avg = effectiveAverage(d, c.cfg);
            e.patientsAheadAtJoin = ahead; e.avgDurationAtJoin = avg;
            e.predictedWaitingTime = QueueOrdering.baselineWait(ahead, avg);
            ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(c.now), clock.getZone());
            e.hourAtJoin = z.getHour(); e.dayOfWeekAtJoin = z.getDayOfWeek().getValue() % 7; // Sun=0

            c.tx.put(e);
            c.emit(EventType.QUEUE_JOINED, e, e.source.name());
            if (e.status == QueueStatus.CHECKED_IN) c.emit(EventType.PATIENT_CHECKED_IN, e, "walk-in");
            return e;
        });
    }

    // ------------------------------------------------------------------ patient-side transitions

    public QueueEntry checkIn(String id) {
        return inTx(c -> {
            QueueEntry e = load(c, id);
            if (move(e, QueueStatus.CHECKED_IN)) {
                e.checkedInAt = c.now; c.tx.put(e); c.emit(EventType.PATIENT_CHECKED_IN, e, null);
            }
            return e;
        });
    }

    public QueueEntry cancelQueueEntry(String id) {
        return inTx(c -> {
            QueueEntry e = load(c, id);
            if (e.status == QueueStatus.CANCELLED) return e;
            if (!c.cfg.cancellableStatuses.contains(e.status)) {
                throw new QueueException("CANCEL_NOT_ALLOWED", e.status.isInFlight()
                    ? "You cannot cancel after the patient has been called or consultation has started."
                    : "This queue entry can no longer be cancelled.");
            }
            move(e, QueueStatus.CANCELLED);
            e.cancelledAt = c.now; c.tx.put(e);
            // give the slot back
            Department dep = c.tx.department(e.departmentId).orElse(null);
            if (dep != null) {
                DailyUsage u = c.tx.usage(e.departmentId, dayKey(e.joinedAt));
                if (e.emergency && u.emergencyUsed > 0) u.emergencyUsed--; else if (u.normalUsed > 0) u.normalUsed--;
                c.tx.put(u);
            }
            c.emit(EventType.PATIENT_CANCELLED, e, null);
            c.emit(EventType.QUEUE_REORDERED, e, "recalculated after cancellation");
            return e;
        });
    }

    // ------------------------------------------------------------------ doctor-side transitions

    /** Marks CALLED entries whose grace period expired as NO_SHOW. Mutates `active` in place. */
    private void sweepExpired(Ctx c, List<QueueEntry> active) {
        long graceMs = c.cfg.graceMinutes * 60_000L;
        Iterator<QueueEntry> it = active.iterator();
        while (it.hasNext()) {
            QueueEntry e = it.next();
            if (e.status == QueueStatus.CALLED && e.calledAt != null && c.now > e.calledAt + graceMs) {
                move(e, QueueStatus.NO_SHOW); e.noShowAt = c.now; c.tx.put(e);
                c.emit(EventType.PATIENT_NO_SHOW, e, "grace period expired");
                it.remove();
            }
        }
    }

    private Optional<QueueEntry> callNextInternal(Ctx c, List<QueueEntry> active) {
        Optional<QueueEntry> next = QueueOrdering.nextToCall(QueueOrdering.ordered(active, c.now, c.cfg));
        next.ifPresent(e -> {
            move(e, QueueStatus.CALLED); e.calledAt = c.now; c.tx.put(e);
            c.emit(EventType.PATIENT_CALLED, e, null);
        });
        return next;
    }

    public Optional<QueueEntry> callNextPatient(String doctorId) {
        return inTx(c -> {
            Doctor d = c.tx.doctor(doctorId).orElseThrow(() -> new QueueException("NOT_FOUND", "Doctor not found."));
            if (d.status != DoctorStatus.ONLINE) throw new QueueException("DOCTOR_UNAVAILABLE", "Doctor is not online.");
            List<QueueEntry> active = c.tx.activeEntriesForDoctor(doctorId);
            sweepExpired(c, active);
            if (active.stream().anyMatch(e -> e.status.isInFlight()))
                throw new QueueException("PATIENT_ALREADY_CALLED", "A patient has already been called or is in consultation.");
            return callNextInternal(c, active);
        });
    }

    public QueueEntry startConsultation(String id) {
        return inTx(c -> {
            Loaded l = loadWithLine(c, id);
            sweepExpired(c, l.active());
            QueueEntry e = l.entry();
            if (e.status == QueueStatus.IN_CONSULTATION) return e;   // idempotent
            if (l.active().stream().anyMatch(o -> o.status == QueueStatus.IN_CONSULTATION && !o.queueEntryId.equals(id)))
                throw new QueueException("DOCTOR_BUSY", "The doctor is already in a consultation.");
            move(e, QueueStatus.IN_CONSULTATION);
            e.consultationStartedAt = c.now; c.tx.put(e);
            c.emit(EventType.CONSULTATION_STARTED, e, null);
            return e;
        });
    }

    /**
     * Completes a consultation and, in the same transaction: records the duration, updates the doctor's
     * rolling average, writes the training row, and (if configured) calls the next checked-in patient.
     */
    public QueueEntry completeConsultation(String id) {
        return inTx(c -> {
            Loaded l = loadWithLine(c, id);
            QueueEntry e = l.entry();
            if (e.status == QueueStatus.COMPLETED) return e;          // idempotent
            move(e, QueueStatus.COMPLETED);
            e.completedAt = c.now; c.tx.put(e);
            l.active().removeIf(o -> o.queueEntryId.equals(id));

            double duration = Math.max(0.0, (c.now - e.consultationStartedAt) / 60000.0);
            Doctor d = c.tx.doctor(e.doctorId).orElseThrow();
            d.recentConsultationDurations.add(duration);
            while (d.recentConsultationDurations.size() > 20) d.recentConsultationDurations.remove(0);
            d.averageConsultationDuration = d.recentConsultationDurations.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            d.consultationsCompleted++; d.lastCompletedAt = c.now;
            c.tx.put(d);

            Consultation k = new Consultation();
            k.consultationId = e.queueEntryId; k.queueEntryId = e.queueEntryId; k.doctorId = e.doctorId;
            k.departmentId = e.departmentId; k.durationMinutes = duration;
            k.waitedMinutes = (e.consultationStartedAt - e.joinedAt) / 60000.0;
            k.patientsAheadAtJoin = e.patientsAheadAtJoin; k.avgDurationAtJoin = e.avgDurationAtJoin;
            k.hour = e.hourAtJoin; k.dayOfWeek = e.dayOfWeekAtJoin; k.source = e.source; k.completedAt = c.now;
            c.tx.put(k);

            c.emit(EventType.CONSULTATION_COMPLETED, e, String.format("%.1f min", duration));
            sweepExpired(c, l.active());
            if (c.cfg.autoCallNext && d.status == DoctorStatus.ONLINE) callNextInternal(c, l.active());
            c.emit(EventType.QUEUE_REORDERED, e, "queue advanced");
            return e;
        });
    }

    public QueueEntry markNoShow(String id) {
        return inTx(c -> {
            QueueEntry e = load(c, id);
            if (move(e, QueueStatus.NO_SHOW)) {
                e.noShowAt = c.now; c.tx.put(e);
                c.emit(EventType.PATIENT_NO_SHOW, e, "marked by staff");
                c.emit(EventType.QUEUE_REORDERED, e, "queue advanced");
            }
            return e;
        });
    }

    /**
     * Patient was called but arrives late.
     *  - within grace period: status LATE; if lateKeepsPosition the entry keeps its original joinedAt
     *    (same rank) and returns to the callable pool after check-in; otherwise it is RESCHEDULED
     *    (must rejoin with a new token).
     *  - after grace period: NO_SHOW.
     */
    public QueueEntry handleLateArrival(String id) {
        return inTx(c -> {
            QueueEntry e = load(c, id);
            if (e.status == QueueStatus.LATE || e.status == QueueStatus.RESCHEDULED || e.status == QueueStatus.NO_SHOW) return e;
            long deadline = (e.calledAt != null ? e.calledAt : e.joinedAt) + c.cfg.graceMinutes * 60_000L;
            if (c.now > deadline) {
                move(e, QueueStatus.NO_SHOW); e.noShowAt = c.now;
                c.tx.put(e); c.emit(EventType.PATIENT_NO_SHOW, e, "arrived after grace period");
                return e;
            }
            move(e, QueueStatus.LATE); e.lateAt = c.now;
            c.emit(EventType.PATIENT_LATE, e, null);
            if (!c.cfg.lateKeepsPosition) {
                move(e, QueueStatus.RESCHEDULED);
                c.emit(EventType.PATIENT_RESCHEDULED, e, "position not retained by policy");
            }
            c.tx.put(e);
            return e;
        });
    }

    /**
     * Doctor availability. Policy: existing entries stay in the doctor's line (they can be served when the
     * doctor returns, or staff may cancel/no-show them); only NEW joins are blocked unless ONLINE.
     */
    public Doctor handleDoctorAvailabilityChange(String doctorId, DoctorStatus status) {
        return inTx(c -> {
            Doctor d = c.tx.doctor(doctorId).orElseThrow(() -> new QueueException("NOT_FOUND", "Doctor not found."));
            if (d.status == status) return d;
            d.status = status; c.tx.put(d);
            QueueEvent ev = new QueueEvent(
                status == DoctorStatus.ONLINE ? EventType.DOCTOR_ONLINE : status == DoctorStatus.OFFLINE ? EventType.DOCTOR_OFFLINE : EventType.DOCTOR_ON_BREAK,
                null, d.doctorId, d.departmentIds.isEmpty() ? null : d.departmentIds.get(0), c.now, null);
            ev.eventId = UUID.randomUUID().toString();
            c.events.add(ev); c.tx.put(ev);
            return d;
        });
    }

    /** Called by a scheduler: expires grace periods for every doctor. */
    public int expireNoShows() {
        return inTx(c -> {
            int n = 0;
            for (Doctor d : c.tx.allDoctors()) {
                List<QueueEntry> active = c.tx.activeEntriesForDoctor(d.doctorId);
                int before = active.size(); sweepExpired(c, active); n += before - active.size();
            }
            return n;
        });
    }

    // ------------------------------------------------------------------ read models (derived, never stored)

    public QueueView view(String entryId) {
        return inTx(c -> {
            QueueEntry e = load(c, entryId);
            if (!e.status.isActive())
                return new QueueView(e.queueEntryId, e.token, e.status, 0, 0, 0, e.doctorId, e.departmentId);
            List<QueueEntry> line = QueueOrdering.ordered(c.tx.activeEntriesForDoctor(e.doctorId), c.now, c.cfg);
            int ahead = Math.max(0, QueueOrdering.patientsAhead(entryId, line));
            double avg = effectiveAverage(c.tx.doctor(e.doctorId).orElseThrow(), c.cfg);
            return new QueueView(e.queueEntryId, e.token, e.status, ahead + 1, ahead,
                QueueOrdering.baselineWait(ahead, avg), e.doctorId, e.departmentId);
        });
    }

    public Optional<QueueEntry> activeEntryForPatient(String patientId) { return inTx(c -> c.tx.activeEntryForPatient(patientId)); }

    public QueueEntry getEntry(String id) { return inTx(c -> load(c, id)); }

    public List<QueueView> doctorQueue(String doctorId) {
        return inTx(c -> {
            Doctor d = c.tx.doctor(doctorId).orElseThrow(() -> new QueueException("NOT_FOUND", "Doctor not found."));
            double avg = effectiveAverage(d, c.cfg);
            List<QueueEntry> line = QueueOrdering.ordered(c.tx.activeEntriesForDoctor(doctorId), c.now, c.cfg);
            List<QueueView> out = new ArrayList<>();
            for (int i = 0; i < line.size(); i++) {
                QueueEntry e = line.get(i);
                out.add(new QueueView(e.queueEntryId, e.token, e.status, i + 1, i, QueueOrdering.baselineWait(i, avg), doctorId, e.departmentId));
            }
            return out;
        });
    }

    /** What a NEW normal-priority patient would face with each doctor of the department (input to the recommender). */
    public List<DoctorLoad> doctorLoads(String departmentId) {
        return inTx(c -> {
            List<DoctorLoad> out = new ArrayList<>();
            for (Doctor d : c.tx.doctorsInDepartment(departmentId)) {
                List<QueueEntry> active = c.tx.activeEntriesForDoctor(d.doctorId);
                double avg = effectiveAverage(d, c.cfg);
                double maxWait = active.stream().mapToDouble(e -> (c.now - e.joinedAt) / 60000.0).max().orElse(0);
                out.add(new DoctorLoad(d.doctorId, d.name, d.status, active.size(), active.size(), avg, maxWait,
                    active.isEmpty(), QueueOrdering.baselineWait(active.size(), avg), List.copyOf(d.recentConsultationDurations)));
            }
            return out;
        });
    }

    public CapacitySummary capacity(String departmentId) {
        return inTx(c -> {
            Department dep = c.tx.department(departmentId).orElseThrow(() -> new QueueException("NOT_FOUND", "Department not found."));
            DailyUsage u = c.tx.usage(departmentId, dayKey(c.now));
            return new CapacitySummary(departmentId, dep.dailyCapacity, dep.normalCapacity(), dep.emergencyReserve,
                u.normalUsed, u.emergencyUsed, Math.max(0, dep.normalCapacity() - u.normalUsed),
                Math.max(0, dep.emergencyReserve - u.emergencyUsed));
        });
    }
}
