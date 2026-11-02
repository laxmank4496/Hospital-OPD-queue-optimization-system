package com.hospital.opd.queue;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.hospital.opd.model.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Firestore implementation of QueueStore. Uses Firestore *transactions*: all reads happen first, writes are
 * buffered and applied at the end; on contention Firestore re-runs the whole function, which is why the engine's
 * transaction bodies are side-effect free. Token counters live in queues/{departmentId_day} and are incremented
 * inside the same transaction that creates the entry, so duplicate tokens are impossible.
 */
public class FirestoreQueueStore implements QueueStore {
    private static final List<String> ACTIVE =
        QueueStatus.ACTIVE.stream().map(Enum::name).collect(Collectors.toList());
    private final Firestore db;

    public FirestoreQueueStore(Firestore db) { this.db = db; }

    @Override
    public <T> T transact(Function<Tx, T> work) {
        try {
            return db.runTransaction(t -> {
                FsTx tx = new FsTx(t);
                T result = work.apply(tx);
                tx.flush();
                return result;
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;   // QueueException etc. pass through unchanged
            throw new IllegalStateException("Firestore transaction failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private final class FsTx implements Tx {
        private final Transaction t;
        private final List<Consumer<Transaction>> writes = new ArrayList<>();
        FsTx(Transaction t) { this.t = t; }
        void flush() { writes.forEach(w -> w.accept(t)); }

        private <X> Optional<X> getDoc(String col, String id, Class<X> cls) {
            try {
                DocumentSnapshot s = t.get(db.collection(col).document(id)).get();
                return s.exists() ? Optional.ofNullable(s.toObject(cls)) : Optional.empty();
            } catch (Exception e) { throw wrap(e); }
        }
        private <X> List<X> query(Query q, Class<X> cls) {
            try { return t.get(q).get().toObjects(cls); } catch (Exception e) { throw wrap(e); }
        }
        private RuntimeException wrap(Exception e) {
            return e instanceof RuntimeException r ? r : new IllegalStateException(e);
        }

        public Optional<Department> department(String id) { return getDoc("departments", id, Department.class); }
        public Optional<Doctor> doctor(String id) { return getDoc("doctors", id, Doctor.class); }
        public List<Doctor> doctorsInDepartment(String dep) {
            return query(db.collection("doctors").whereArrayContains("departmentIds", dep), Doctor.class);
        }
        public List<Doctor> allDoctors() { return query(db.collection("doctors"), Doctor.class); }
        public Optional<QueueEntry> entry(String id) { return getDoc("queueEntries", id, QueueEntry.class); }
        public Optional<QueueEntry> entryByIdempotencyKey(String k) {
            return query(db.collection("queueEntries").whereEqualTo("idempotencyKey", k).limit(1), QueueEntry.class).stream().findFirst();
        }
        public List<QueueEntry> activeEntriesForDoctor(String doc) {
            return query(db.collection("queueEntries").whereEqualTo("doctorId", doc).whereIn("status", ACTIVE), QueueEntry.class);
        }
        public Optional<QueueEntry> activeEntryForPatient(String pid) {
            return query(db.collection("queueEntries").whereEqualTo("patientId", pid).whereIn("status", ACTIVE).limit(1), QueueEntry.class).stream().findFirst();
        }
        public DailyUsage usage(String dep, String day) {
            return getDoc("dailyUsage", dep + "_" + day, DailyUsage.class).orElseGet(() -> new DailyUsage(dep, day));
        }
        public int nextTokenNumber(String dep, String day) {
            DocumentReference ref = db.collection("queues").document(dep + "_" + day);
            long last;
            try {
                DocumentSnapshot s = t.get(ref).get();
                last = s.exists() && s.getLong("lastToken") != null ? s.getLong("lastToken") : 0L;
            } catch (Exception e) { throw wrap(e); }
            long next = last + 1;
            writes.add(tx -> tx.set(ref, Map.of("departmentId", dep, "day", day, "lastToken", next)));
            return (int) next;
        }

        public void put(Doctor d) { writes.add(tx -> tx.set(db.collection("doctors").document(d.doctorId), d)); }
        public void put(QueueEntry e) { writes.add(tx -> tx.set(db.collection("queueEntries").document(e.queueEntryId), e)); }
        public void put(DailyUsage u) { writes.add(tx -> tx.set(db.collection("dailyUsage").document(u.departmentId + "_" + u.day), u)); }
        public void put(Consultation c) { writes.add(tx -> tx.set(db.collection("consultations").document(c.consultationId), c)); }
        public void put(QueueEvent e) { writes.add(tx -> tx.set(db.collection("queueEvents").document(e.eventId), e)); }
    }
}
