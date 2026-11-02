package com.hospital.opd.controller;

import com.google.cloud.firestore.Firestore;
import com.hospital.opd.model.*;
import com.hospital.opd.queue.QueueException;
import com.hospital.opd.security.AppUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HistoryController {
    private final Firestore db; private final Clock clock;
    public HistoryController(Firestore db, Clock clock) { this.db = db; this.clock = clock; }

    /** The signed-in patient's own entries, newest first. */
    @GetMapping("/history/mine")
    @PreAuthorize("hasRole('PATIENT')")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal AppUser u) throws Exception {
        List<QueueEntry> list = new ArrayList<>(db.collection("queueEntries").whereEqualTo("patientId", u.uid()).get().get().toObjects(QueueEntry.class));
        list.sort(Comparator.comparing((QueueEntry e) -> e.joinedAt).reversed());
        Map<String, String> docs = new HashMap<>(), deps = new HashMap<>();
        for (Doctor d : db.collection("doctors").get().get().toObjects(Doctor.class)) docs.put(d.doctorId, d.name);
        for (Department d : db.collection("departments").get().get().toObjects(Department.class)) deps.put(d.departmentId, d.name);
        return list.stream().limit(50).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("queueEntryId", e.queueEntryId); m.put("token", e.token); m.put("status", e.status);
            m.put("doctor", docs.getOrDefault(e.doctorId, e.doctorId)); m.put("department", deps.getOrDefault(e.departmentId, e.departmentId));
            m.put("joinedAt", e.joinedAt); m.put("completedAt", e.completedAt);
            m.put("waitedMinutes", e.consultationStartedAt == null ? null : Math.round((e.consultationStartedAt - e.joinedAt) / 6000.0) / 10.0);
            return m;
        }).collect(Collectors.toList());
    }

    /** Staff: find today's entry by token (e.g. GM-004) for desk check-in. */
    @GetMapping("/queues/lookup")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public Map<String, Object> lookup(@RequestParam String token) throws Exception {
        long start = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
        Optional<QueueEntry> hit = db.collection("queueEntries").whereEqualTo("token", token.trim().toUpperCase()).get().get()
            .toObjects(QueueEntry.class).stream().filter(e -> e.joinedAt >= start).max(Comparator.comparing(e -> e.joinedAt));
        if (hit.isEmpty()) throw new QueueException("NOT_FOUND", "No entry with that token today.");
        QueueEntry e = hit.get();
        String doctor = db.collection("doctors").document(e.doctorId).get().get().getString("name");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queueEntryId", e.queueEntryId); m.put("token", e.token); m.put("status", e.status); m.put("source", e.source);
        m.put("patientName", e.patientName); m.put("doctor", doctor); m.put("doctorId", e.doctorId);
        return m;
    }
}
