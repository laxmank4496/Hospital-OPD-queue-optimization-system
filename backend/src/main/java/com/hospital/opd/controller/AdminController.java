package com.hospital.opd.controller;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.hospital.opd.model.*;
import com.hospital.opd.queue.QueueEngine;
import com.hospital.opd.queue.QueueEngine.JoinRequest;
import com.hospital.opd.queue.QueueException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin-only management: departments, doctors, staff logins and the demo simulator. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private static final List<String> ACTIVE = QueueStatus.ACTIVE.stream().map(Enum::name).collect(Collectors.toList());
    private final Firestore db; private final QueueEngine engine; private final Clock clock;

    public AdminController(Firestore db, QueueEngine engine, Clock clock) { this.db = db; this.engine = engine; this.clock = clock; }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 6).toUpperCase(); }
    private long startOfToday() { return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant().toEpochMilli(); }

    // ------------------------------------------------------------------ departments
    public record DepartmentBody(@NotBlank String name, @NotBlank @Size(min = 2, max = 4) String code, int dailyCapacity, int emergencyReserve) {}

    @PostMapping("/departments")
    public Department createDepartment(@Valid @RequestBody DepartmentBody b) throws Exception {
        if (b.dailyCapacity() < 1 || b.emergencyReserve() < 0 || b.emergencyReserve() > b.dailyCapacity())
            throw new QueueException("VALIDATION", "Daily capacity must be at least 1 and the emergency reserve cannot exceed it.");
        String code = b.code().trim().toUpperCase();
        if (!db.collection("departments").whereEqualTo("code", code).get().get().isEmpty())
            throw new QueueException("VALIDATION", "A department with code " + code + " already exists.");
        Department d = new Department(code + "-" + shortId(), b.name().trim(), code, b.dailyCapacity(), b.emergencyReserve());
        db.collection("departments").document(d.departmentId).set(d).get();
        return d;
    }

    @DeleteMapping("/departments/{id}")
    public Map<String, String> deleteDepartment(@PathVariable String id) throws Exception {
        if (!db.collection("doctors").whereArrayContains("departmentIds", id).get().get().isEmpty())
            throw new QueueException("CONFLICT", "Remove or reassign the doctors in this department first.");
        db.collection("departments").document(id).delete().get();
        return Map.of("deleted", id);
    }

    // ------------------------------------------------------------------ doctors
    public record DoctorBody(@NotBlank String name, @NotEmpty List<String> departmentIds, String email, String password) {}

    @PostMapping("/doctors")
    public Doctor createDoctor(@Valid @RequestBody DoctorBody b) throws Exception {
        for (String dep : b.departmentIds())
            if (!db.collection("departments").document(dep).get().get().exists()) throw new QueueException("VALIDATION", "Unknown department: " + dep);
        Doctor d = new Doctor("DOC" + shortId(), b.name().trim(), b.departmentIds(), DoctorStatus.OFFLINE, 0);
        boolean login = b.email() != null && !b.email().isBlank();
        if (login && (b.password() == null || b.password().length() < 8)) throw new QueueException("VALIDATION", "Password must be at least 8 characters.");
        db.collection("doctors").document(d.doctorId).set(d).get();
        if (login) {
            try { createLogin(b.name(), b.email().trim(), b.password(), "DOCTOR", d.doctorId); }
            catch (Exception e) { db.collection("doctors").document(d.doctorId).delete().get(); throw e; }   // no orphan doctor without login
        }
        return d;
    }

    public record DoctorUpdate(@NotBlank String name, @NotEmpty List<String> departmentIds) {}

    @PutMapping("/doctors/{id}")
    public Map<String, String> updateDoctor(@PathVariable String id, @Valid @RequestBody DoctorUpdate b) throws Exception {
        DocumentReference ref = db.collection("doctors").document(id);
        if (!ref.get().get().exists()) throw new QueueException("NOT_FOUND", "Doctor not found.");
        ref.update("name", b.name().trim(), "departmentIds", b.departmentIds()).get();
        return Map.of("updated", id);
    }

    @DeleteMapping("/doctors/{id}")
    public Map<String, String> deleteDoctor(@PathVariable String id) throws Exception {
        if (!db.collection("queueEntries").whereEqualTo("doctorId", id).whereIn("status", ACTIVE).limit(1).get().get().isEmpty())
            throw new QueueException("CONFLICT", "This doctor still has patients in the queue.");
        db.collection("doctors").document(id).delete().get();
        return Map.of("deleted", id);
    }

    // ------------------------------------------------------------------ staff logins
    public record StaffBody(@NotBlank String name, @NotBlank @Email String email, @Size(min = 8) String password, @NotBlank String role, String doctorId) {}

    @PostMapping("/admin/staff")
    public Map<String, Object> createStaff(@Valid @RequestBody StaffBody b) throws Exception {
        if (!Set.of("ADMIN", "RECEPTIONIST", "DOCTOR").contains(b.role()))
            throw new QueueException("VALIDATION", "Role must be ADMIN, RECEPTIONIST or DOCTOR.");
        if (b.role().equals("DOCTOR")) {
            if (b.doctorId() == null || !db.collection("doctors").document(b.doctorId()).get().get().exists())
                throw new QueueException("VALIDATION", "Choose an existing doctor profile for a DOCTOR login.");
        }
        createLogin(b.name(), b.email().trim(), b.password(), b.role(), b.role().equals("DOCTOR") ? b.doctorId() : null);
        return Map.of("created", b.email().trim(), "role", b.role());
    }

    @GetMapping("/admin/staff")
    public List<Map<String, Object>> listStaff() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (QueryDocumentSnapshot s : db.collection("users").get().get().getDocuments()) out.add(new LinkedHashMap<>(s.getData()));
        return out;
    }

    /** Role lives ONLY in a Firebase custom claim written here with the Admin SDK. */
    private void createLogin(String name, String email, String password, String role, String doctorId) throws Exception {
        UserRecord u;
        try {
            u = FirebaseAuth.getInstance().createUser(new UserRecord.CreateRequest().setEmail(email).setPassword(password).setDisplayName(name));
        } catch (FirebaseAuthException e) {
            throw new QueueException("VALIDATION", "Could not create the login: " + e.getMessage());
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        if (doctorId != null) claims.put("doctorId", doctorId);
        FirebaseAuth.getInstance().setCustomUserClaims(u.getUid(), claims);
        Map<String, Object> doc = new HashMap<>();
        doc.put("uid", u.getUid()); doc.put("name", name); doc.put("email", email); doc.put("role", role);
        doc.put("doctorId", doctorId); doc.put("createdAt", System.currentTimeMillis());
        db.collection("users").document(u.getUid()).set(doc).get();
    }

    // ------------------------------------------------------------------ demo simulator (development/demo only)
    public record SimBody(@NotBlank String departmentId, int count, int cancelPercent, int noShowPercent) {}

    /** Creates demo patients through the REAL queue engine (same rules, capacity, tokens). */
    @PostMapping("/admin/simulate/patients")
    public Map<String, Object> simulatePatients(@Valid @RequestBody SimBody b) throws Exception {
        int n = Math.min(Math.max(b.count(), 1), 30);
        List<Doctor> online = db.collection("doctors").whereArrayContains("departmentIds", b.departmentId()).get().get().toObjects(Doctor.class)
            .stream().filter(d -> d.status == DoctorStatus.ONLINE).collect(Collectors.toList());
        if (online.isEmpty()) throw new QueueException("DOCTOR_UNAVAILABLE", "No doctor is online in this department. Set a doctor ONLINE first.");
        Random rnd = new Random();
        int created = 0, cancelled = 0, noShows = 0, rejected = 0; String lastError = null;
        for (int i = 0; i < n; i++) {
            Doctor d = online.get(i % online.size());
            boolean walkIn = rnd.nextInt(100) >= 30;
            try {
                QueueEntry e = engine.joinQueue(new JoinRequest("demo-" + UUID.randomUUID(), "Demo Patient " + (i + 1), d.doctorId, b.departmentId(),
                    walkIn ? Source.WALK_IN : Source.ONLINE, 0, false, null));
                created++;
                if (!walkIn && rnd.nextInt(100) < b.cancelPercent()) { engine.cancelQueueEntry(e.queueEntryId); cancelled++; }
                else if (walkIn && rnd.nextInt(100) < b.noShowPercent()) { engine.markNoShow(e.queueEntryId); noShows++; }
            } catch (QueueException ex) { rejected++; lastError = ex.getMessage(); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created); out.put("cancelled", cancelled); out.put("noShows", noShows); out.put("rejected", rejected); out.put("lastError", lastError);
        return out;
    }

    /** Writes simulated finished consultations (variable durations/waits) so charts have something to show. */
    @PostMapping("/admin/simulate/history")
    public Map<String, Object> simulateHistory(@Valid @RequestBody SimBody b) throws Exception {
        int n = Math.min(Math.max(b.count(), 1), 200);
        List<Doctor> docs = db.collection("doctors").whereArrayContains("departmentIds", b.departmentId()).get().get().toObjects(Doctor.class);
        if (docs.isEmpty()) throw new QueueException("VALIDATION", "This department has no doctors.");
        Random rnd = new Random();
        long lo = startOfToday() + 8L * 3_600_000L, hi = Math.max(lo + 1, clock.millis());
        WriteBatch batch = db.batch();
        for (int i = 0; i < n; i++) {
            Doctor d = docs.get(rnd.nextInt(docs.size()));
            Consultation c = new Consultation();
            c.consultationId = "sim-" + UUID.randomUUID(); c.queueEntryId = c.consultationId; c.doctorId = d.doctorId; c.departmentId = b.departmentId();
            c.durationMinutes = Math.round((4 + rnd.nextDouble() * 9) * 10) / 10.0;
            c.waitedMinutes = Math.round(rnd.nextDouble() * 45 * 10) / 10.0;
            c.completedAt = lo + (long) (rnd.nextDouble() * (hi - lo));
            c.patientsAheadAtJoin = rnd.nextInt(8); c.avgDurationAtJoin = 7; c.hour = 9 + rnd.nextInt(8); c.dayOfWeek = LocalDate.now(clock).getDayOfWeek().getValue() % 7;
            c.source = rnd.nextBoolean() ? Source.ONLINE : Source.WALK_IN; c.simulated = true;
            batch.set(db.collection("consultations").document(c.consultationId), c);
        }
        batch.commit().get();
        return Map.of("created", n);
    }

    /** Removes everything the simulator created and gives the capacity slots back. */
    @PostMapping("/admin/simulate/reset")
    public Map<String, Object> resetDemo() throws Exception {
        List<QueryDocumentSnapshot> sims = db.collection("consultations").whereEqualTo("simulated", true).get().get().getDocuments();
        List<QueryDocumentSnapshot> entries = db.collection("queueEntries").whereGreaterThanOrEqualTo("patientId", "demo-").whereLessThan("patientId", "demo.").get().get().getDocuments();
        long start = startOfToday(); String day = LocalDate.now(clock).toString();
        Map<String, int[]> give = new HashMap<>();   // departmentId -> {normal, emergency}
        for (QueryDocumentSnapshot s : entries) {
            QueueEntry e = s.toObject(QueueEntry.class);
            if (e.joinedAt == null || e.joinedAt < start || e.status == QueueStatus.CANCELLED) continue;   // cancelled entries already refunded
            give.computeIfAbsent(e.departmentId, k -> new int[2])[e.emergency ? 1 : 0]++;
        }
        List<DocumentReference> refs = new ArrayList<>();
        sims.forEach(s -> refs.add(s.getReference())); entries.forEach(s -> refs.add(s.getReference()));
        for (int i = 0; i < refs.size(); i += 400) {
            WriteBatch b = db.batch();
            refs.subList(i, Math.min(refs.size(), i + 400)).forEach(b::delete);
            b.commit().get();
        }
        for (Map.Entry<String, int[]> g : give.entrySet()) {
            DocumentReference ref = db.collection("dailyUsage").document(g.getKey() + "_" + day);
            DocumentSnapshot snap = ref.get().get();
            if (!snap.exists()) continue;
            DailyUsage u = snap.toObject(DailyUsage.class);
            u.normalUsed = Math.max(0, u.normalUsed - g.getValue()[0]); u.emergencyUsed = Math.max(0, u.emergencyUsed - g.getValue()[1]);
            ref.set(u).get();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removedConsultations", sims.size()); out.put("removedQueueEntries", entries.size());
        return out;
    }
}
