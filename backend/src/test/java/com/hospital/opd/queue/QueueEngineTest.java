package com.hospital.opd.queue;

import static org.junit.jupiter.api.Assertions.*;

import com.hospital.opd.model.*;
import com.hospital.opd.optimization.DoctorRecommender;
import com.hospital.opd.optimization.FairnessMetrics;
import com.hospital.opd.queue.QueueEngine.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueEngineTest {
    InMemoryQueueStore store; MutableClock clock; QueueEngine engine; QueueConfig cfg;
    static final String DEP = "GEN001", DOC = "DOC001";

    @BeforeEach void setUp() {
        store = new InMemoryQueueStore(); cfg = new QueueConfig();
        clock = new MutableClock("2026-10-05T10:00:00Z");
        store.seed(new Department(DEP, "General Medicine", "GM", 100, 3));
        store.seed(new Doctor(DOC, "Dr. Sharma", List.of(DEP), DoctorStatus.ONLINE, 0));
        engine = new QueueEngine(store, () -> cfg, clock, null);
    }

    QueueEntry join(String patient, Source s) { return join(patient, s, 0, false); }
    QueueEntry join(String patient, Source s, int prio, boolean emergency) {
        return engine.joinQueue(new JoinRequest(patient, patient, DOC, DEP, s, prio, emergency, null));
    }

    // Test 1
    @Test void joinCreatesWaitingEntryWithTokenAndPosition() {
        QueueEntry e = join("p1", Source.ONLINE);
        assertEquals(QueueStatus.WAITING, e.status);
        assertEquals("GM-001", e.token);
        QueueView v = engine.view(e.queueEntryId);
        assertEquals(1, v.position()); assertEquals(0, v.patientsAhead());
    }

    // Test 2 (+ position is derived, not stored)
    @Test void cancelRecalculatesPositions() {
        QueueEntry a = join("a", Source.ONLINE), b = join("b", Source.ONLINE), c = join("c", Source.ONLINE);
        assertEquals(3, engine.view(c.queueEntryId).position());
        QueueEntry cancelled = engine.cancelQueueEntry(b.queueEntryId);
        assertEquals(QueueStatus.CANCELLED, cancelled.status);
        assertEquals(2, engine.view(c.queueEntryId).position());
        assertEquals(1, engine.view(c.queueEntryId).patientsAhead());
        assertEquals(QueueStatus.CANCELLED, engine.cancelQueueEntry(b.queueEntryId).status); // idempotent
    }

    // Test 3
    @Test void completeConsultationRecordsDurationAndAdvancesQueue() {
        QueueEntry a = join("a", Source.WALK_IN), b = join("b", Source.ONLINE);
        engine.checkIn(b.queueEntryId);
        assertEquals(a.queueEntryId, engine.callNextPatient(DOC).orElseThrow().queueEntryId);
        engine.startConsultation(a.queueEntryId);
        clock.advanceMinutes(8);
        engine.completeConsultation(a.queueEntryId);
        assertEquals(QueueStatus.COMPLETED, store.entrySnapshot(a.queueEntryId).status);
        assertEquals(8.0, store.doctorSnapshot(DOC).averageConsultationDuration, 1e-9);
        assertEquals(8.0, store.consultations.get(a.queueEntryId).durationMinutes, 1e-9);
        assertEquals(QueueStatus.CALLED, store.entrySnapshot(b.queueEntryId).status);   // auto-advanced
        assertEquals(1, engine.view(b.queueEntryId).position());
        engine.completeConsultation(a.queueEntryId);                                    // idempotent
        assertEquals(1, store.doctorSnapshot(DOC).consultationsCompleted);
    }

    // Test 4
    @Test void noShowAdvancesQueue() {
        QueueEntry a = join("a", Source.WALK_IN), b = join("b", Source.WALK_IN);
        engine.callNextPatient(DOC);
        assertEquals(QueueStatus.NO_SHOW, engine.markNoShow(a.queueEntryId).status);
        assertEquals(b.queueEntryId, engine.callNextPatient(DOC).orElseThrow().queueEntryId);
    }

    @Test void graceExpiryTurnsCalledIntoNoShow() {
        QueueEntry a = join("a", Source.WALK_IN), b = join("b", Source.WALK_IN);
        engine.callNextPatient(DOC);
        clock.advanceMinutes(cfg.graceMinutes + 1);
        assertEquals(b.queueEntryId, engine.callNextPatient(DOC).orElseThrow().queueEntryId);
        QueueEntry stored = store.entrySnapshot(a.queueEntryId);
        assertEquals(QueueStatus.NO_SHOW, stored.status); assertNotNull(stored.calledAt); assertNotNull(stored.noShowAt);
    }

    // Test 5
    @Test void walkInAndOnlineShareOneQueue() {
        QueueEntry o = join("o", Source.ONLINE), w = join("w", Source.WALK_IN);
        List<QueueView> q = engine.doctorQueue(DOC);
        assertEquals(List.of(o.queueEntryId, w.queueEntryId), q.stream().map(QueueView::queueEntryId).toList());
        assertEquals(QueueStatus.CHECKED_IN, store.entrySnapshot(w.queueEntryId).status);
        assertEquals(Source.WALK_IN, w.source);
    }

    // Test 6
    @Test void concurrentJoinsGetUniqueTokens() throws Exception {
        int n = 50; ExecutorService ex = Executors.newFixedThreadPool(16); CountDownLatch go = new CountDownLatch(1);
        List<Future<QueueEntry>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) { final int k = i;
            fs.add(ex.submit(() -> { go.await(); return join("p" + k, k % 2 == 0 ? Source.ONLINE : Source.WALK_IN); })); }
        go.countDown();
        Set<String> tokens = new HashSet<>();
        for (Future<QueueEntry> f : fs) tokens.add(f.get().token);
        ex.shutdown();
        assertEquals(n, tokens.size());
        assertTrue(tokens.contains("GM-001") && tokens.contains("GM-050"));
        assertEquals(n, new HashSet<>(engine.doctorQueue(DOC).stream().map(QueueView::position).toList()).size());
    }

    // Test 7
    @Test void offlineDoctorRejectsNewJoinsButKeepsExistingQueue() {
        QueueEntry a = join("a", Source.ONLINE);
        engine.handleDoctorAvailabilityChange(DOC, DoctorStatus.OFFLINE);
        QueueException ex = assertThrows(QueueException.class, () -> join("b", Source.ONLINE));
        assertEquals("DOCTOR_UNAVAILABLE", ex.code());
        assertEquals(QueueStatus.WAITING, store.entrySnapshot(a.queueEntryId).status);
        assertThrows(QueueException.class, () -> engine.callNextPatient(DOC));
    }

    // Test 8
    @Test void normalJoinsNeverConsumeEmergencyReserve() {
        store.seed(new Department("ENT1", "ENT", "EN", 5, 2));
        store.seed(new Doctor("DOC9", "Dr. Patel", List.of("ENT1"), DoctorStatus.ONLINE, 0));
        for (int i = 0; i < 3; i++) engine.joinQueue(new JoinRequest("n" + i, "n", "DOC9", "ENT1", Source.ONLINE, 0, false, null));
        QueueException full = assertThrows(QueueException.class,
            () -> engine.joinQueue(new JoinRequest("n3", "n", "DOC9", "ENT1", Source.ONLINE, 0, false, null)));
        assertEquals("CAPACITY_FULL", full.code());
        CapacitySummary cap = engine.capacity("ENT1");
        assertEquals(3, cap.normalCapacity()); assertEquals(2, cap.remainingReserve());
        engine.joinQueue(new JoinRequest("e1", "e", "DOC9", "ENT1", Source.WALK_IN, 0, true, null));
        engine.joinQueue(new JoinRequest("e2", "e", "DOC9", "ENT1", Source.WALK_IN, 0, true, null));
        assertEquals(0, engine.capacity("ENT1").remainingReserve());
        assertEquals(2, engine.capacity("ENT1").emergencyUsed());
    }

    // Test 9 - the worked example from the brief (A: 6x7, B: 3x6, C: 5x8)
    @Test void recommenderPicksLowestWaitButLeavesChoiceToPatient() {
        List<DoctorLoad> loads = List.of(
            new DoctorLoad("A", "Dr. A", DoctorStatus.ONLINE, 6, 6, 7, 30, false, 42, List.of()),
            new DoctorLoad("B", "Dr. B", DoctorStatus.ONLINE, 3, 3, 6, 15, false, 18, List.of()),
            new DoctorLoad("C", "Dr. C", DoctorStatus.ONLINE, 5, 5, 8, 25, false, 40, List.of()),
            new DoctorLoad("D", "Dr. D", DoctorStatus.OFFLINE, 0, 0, 7, 0, true, 0, List.of()));
        List<DoctorRecommender.Option> r = DoctorRecommender.rank(loads, Map.of(), Map.of(), cfg);
        assertEquals("B", r.stream().filter(DoctorRecommender.Option::recommended).findFirst().orElseThrow().doctorId());
        assertEquals(1, r.stream().filter(DoctorRecommender.Option::recommended).count());
        assertEquals(3, r.stream().filter(DoctorRecommender.Option::available).count());   // all online doctors selectable
        assertFalse(r.stream().filter(o -> o.doctorId().equals("D")).findFirst().orElseThrow().available());
    }

    // Test 10
    @Test void invalidTransitionsAreRejected() {
        QueueEntry a = join("a", Source.ONLINE);
        assertThrows(InvalidTransitionException.class, () -> engine.completeConsultation(a.queueEntryId));
        assertThrows(InvalidTransitionException.class, () -> engine.startConsultation(a.queueEntryId));
        engine.cancelQueueEntry(a.queueEntryId);
        assertThrows(InvalidTransitionException.class, () -> engine.checkIn(a.queueEntryId));
        assertFalse(QueueStatus.COMPLETED.canMoveTo(QueueStatus.WAITING));
        assertTrue(QueueStatus.COMPLETED.allowedNext().isEmpty());
    }

    @Test void cannotCancelOnceCalled() {
        QueueEntry a = join("a", Source.WALK_IN);
        engine.callNextPatient(DOC);
        assertEquals("CANCEL_NOT_ALLOWED", assertThrows(QueueException.class, () -> engine.cancelQueueEntry(a.queueEntryId)).code());
    }

    @Test void joinIsIdempotentAndOneActiveEntryPerPatient() {
        JoinRequest req = new JoinRequest("p", "p", DOC, DEP, Source.ONLINE, 0, false, "key-1");
        QueueEntry first = engine.joinQueue(req), again = engine.joinQueue(req);
        assertEquals(first.queueEntryId, again.queueEntryId);
        assertEquals(1, engine.doctorQueue(DOC).size());
        assertEquals("ALREADY_IN_QUEUE", assertThrows(QueueException.class, () -> join("p", Source.ONLINE)).code());
    }

    // Fairness: priority is bounded by waiting time; emergencies dominate
    @Test void waitingTimeBoostPreventsStarvationAndEmergencyJumpsAhead() {
        QueueEntry old = join("old", Source.ONLINE);
        clock.advanceMinutes(20);
        QueueEntry vip = join("vip", Source.ONLINE, 10, false);   // 10 "waiting-minute equivalents"
        assertEquals(old.queueEntryId, engine.doctorQueue(DOC).get(0).queueEntryId());   // 20 min waited > 10 priority
        QueueEntry emergency = join("em", Source.WALK_IN, 0, true);
        assertEquals(emergency.queueEntryId, engine.doctorQueue(DOC).get(0).queueEntryId());
    }

    @Test void lateArrivalKeepsPositionOrBecomesNoShowAfterGrace() {
        QueueEntry a = join("a", Source.WALK_IN), b = join("b", Source.WALK_IN);
        engine.callNextPatient(DOC);
        clock.advanceMinutes(2);
        assertEquals(QueueStatus.LATE, engine.handleLateArrival(a.queueEntryId).status);
        engine.checkIn(a.queueEntryId);                                         // LATE -> CHECKED_IN, keeps joinedAt
        assertEquals(1, engine.view(a.queueEntryId).position());
        assertEquals(a.queueEntryId, engine.callNextPatient(DOC).orElseThrow().queueEntryId);
        clock.advanceMinutes(cfg.graceMinutes + 1);
        assertEquals(QueueStatus.NO_SHOW, engine.handleLateArrival(a.queueEntryId).status);
    }

    @Test void waitingEstimateIsPatientsAheadTimesAverageDuration() {
        join("a", Source.ONLINE); join("b", Source.ONLINE);
        QueueEntry c = join("c", Source.ONLINE);
        assertEquals(2 * cfg.defaultConsultationMinutes, engine.view(c.queueEntryId).baselineWaitMinutes(), 1e-9);
    }

    @Test void fairnessMetricsAreComputedFromObservedWaits() {
        FairnessMetrics.Report r = FairnessMetrics.compute(List.of(10.0, 10.0, 10.0), 30);
        assertEquals(1.0, r.jainIndex(), 1e-9); assertEquals(0.0, r.variance(), 1e-9);
        FairnessMetrics.Report skew = FairnessMetrics.compute(List.of(1.0, 1.0, 40.0), 30);
        assertTrue(skew.jainIndex() < 0.6); assertEquals(1, skew.patientsDelayed()); assertEquals(40.0, skew.maxWait());
    }
}
