package com.hospital.opd.queue;

import com.hospital.opd.model.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Test/demo store. transact() is serialized and buffered (commit-or-discard), mirroring Firestore semantics. */
public class InMemoryQueueStore implements QueueStore {
    private final Map<String, QueueEntry> entries = new HashMap<>();
    private final Map<String, Doctor> doctors = new HashMap<>();
    private final Map<String, Department> departments = new HashMap<>();
    private final Map<String, DailyUsage> usages = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();
    public final Map<String, Consultation> consultations = new LinkedHashMap<>();
    public final List<QueueEvent> events = new ArrayList<>();

    public synchronized void seed(Department d) { departments.put(d.departmentId, d); }
    public synchronized void seed(Doctor d) { doctors.put(d.doctorId, d.copy()); }
    public synchronized Doctor doctorSnapshot(String id) { return doctors.get(id).copy(); }
    public synchronized QueueEntry entrySnapshot(String id) { return entries.get(id).copy(); }
    public synchronized List<QueueEntry> allEntries() { return entries.values().stream().map(QueueEntry::copy).collect(Collectors.toList()); }

    @Override
    public synchronized <T> T transact(Function<Tx, T> work) {
        Buffered tx = new Buffered();
        T result = work.apply(tx);     // exception => nothing committed
        tx.commit();
        return result;
    }

    private class Buffered implements Tx {
        final Map<String, QueueEntry> pe = new HashMap<>();
        final Map<String, Doctor> pd = new HashMap<>();
        final Map<String, DailyUsage> pu = new HashMap<>();
        final Map<String, Integer> pc = new HashMap<>();
        final Map<String, Consultation> pcons = new LinkedHashMap<>();
        final List<QueueEvent> pev = new ArrayList<>();

        public Optional<Department> department(String id) { return Optional.ofNullable(departments.get(id)); }
        public Optional<Doctor> doctor(String id) { return Optional.ofNullable(doctors.get(id)).map(Doctor::copy); }
        public List<Doctor> doctorsInDepartment(String dep) {
            return doctors.values().stream().filter(d -> d.departmentIds.contains(dep)).map(Doctor::copy).collect(Collectors.toList());
        }
        public List<Doctor> allDoctors() { return doctors.values().stream().map(Doctor::copy).collect(Collectors.toList()); }
        public Optional<QueueEntry> entry(String id) { return Optional.ofNullable(entries.get(id)).map(QueueEntry::copy); }
        public Optional<QueueEntry> entryByIdempotencyKey(String k) {
            return entries.values().stream().filter(e -> k.equals(e.idempotencyKey)).findFirst().map(QueueEntry::copy);
        }
        public List<QueueEntry> activeEntriesForDoctor(String doc) {
            return entries.values().stream().filter(e -> doc.equals(e.doctorId) && e.status.isActive())
                .map(QueueEntry::copy).collect(Collectors.toList());
        }
        public Optional<QueueEntry> activeEntryForPatient(String pid) {
            return entries.values().stream().filter(e -> pid.equals(e.patientId) && e.status.isActive()).findFirst().map(QueueEntry::copy);
        }
        public DailyUsage usage(String dep, String day) {
            DailyUsage u = usages.get(dep + "_" + day);
            return u == null ? new DailyUsage(dep, day) : u.copy();
        }
        public int nextTokenNumber(String dep, String day) {
            String k = dep + "_" + day;
            int cur = pc.getOrDefault(k, counters.getOrDefault(k, 0));
            pc.put(k, cur + 1);
            return cur + 1;
        }
        public void put(Doctor d) { pd.put(d.doctorId, d.copy()); }
        public void put(QueueEntry e) { pe.put(e.queueEntryId, e.copy()); }
        public void put(DailyUsage u) { pu.put(u.departmentId + "_" + u.day, u.copy()); }
        public void put(Consultation c) { pcons.put(c.consultationId, c); }
        public void put(QueueEvent e) { pev.add(e); }

        void commit() {
            entries.putAll(pe); doctors.putAll(pd); usages.putAll(pu); counters.putAll(pc);
            consultations.putAll(pcons); events.addAll(pev);
        }
    }
}
