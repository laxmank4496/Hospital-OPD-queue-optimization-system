package com.hospital.opd.queue;

import com.hospital.opd.model.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Persistence port for the queue engine. The engine only talks to this interface, so the same
 * logic runs against Firestore in production (FirestoreQueueStore) and in memory in unit tests.
 *
 * Contract: transact() runs `work` atomically and may re-run it on contention (Firestore does).
 * Therefore `work` must be free of side effects other than Tx calls. Reads inside a transaction
 * do NOT observe that transaction's own buffered writes (Firestore semantics), so the engine
 * loads once, mutates objects in memory, then put()s the results.
 */
public interface QueueStore {
    <T> T transact(Function<Tx, T> work);

    interface Tx {
        Optional<Department> department(String id);
        Optional<Doctor> doctor(String id);
        List<Doctor> doctorsInDepartment(String departmentId);
        List<Doctor> allDoctors();
        Optional<QueueEntry> entry(String id);
        Optional<QueueEntry> entryByIdempotencyKey(String key);
        List<QueueEntry> activeEntriesForDoctor(String doctorId);
        Optional<QueueEntry> activeEntryForPatient(String patientId);
        DailyUsage usage(String departmentId, String day);
        /** Atomically increments and returns the next token number for (department, day). */
        int nextTokenNumber(String departmentId, String day);

        void put(Doctor d);
        void put(QueueEntry e);
        void put(DailyUsage u);
        void put(Consultation c);
        void put(QueueEvent e);
    }
}
