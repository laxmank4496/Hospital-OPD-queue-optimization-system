package com.hospital.opd.controller;

import com.google.cloud.firestore.Firestore;
import com.hospital.opd.model.*;
import com.hospital.opd.optimization.DoctorRecommender;
import com.hospital.opd.queue.QueueEngine;
import com.hospital.opd.queue.QueueException;
import com.hospital.opd.security.AppUser;
import com.hospital.opd.service.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final Firestore db; private final QueueEngine engine;
    private final RecommendationService recs; private final PublicDisplayService display; private final ConfigService cfg;

    public CatalogController(Firestore db, QueueEngine e, RecommendationService r, PublicDisplayService d, ConfigService c) {
        this.db = db; engine = e; recs = r; display = d; cfg = c;
    }

    @GetMapping("/users/me")
    public Map<String, String> me(@AuthenticationPrincipal AppUser u) {
        Map<String, String> m = new HashMap<>(); m.put("uid", u.uid()); m.put("role", u.role()); m.put("doctorId", u.doctorId()); return m;
    }

    @GetMapping("/departments")
    public List<Department> departments() throws Exception {
        return db.collection("departments").get().get().toObjects(Department.class);
    }

    /** Names only, for the lobby display and landing page. */
    @GetMapping("/public/departments")
    public List<Department> publicDepartments() throws Exception { return departments(); }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Department saveDepartment(@PathVariable String id, @RequestBody Department d) throws Exception {
        if (d.emergencyReserve < 0 || d.emergencyReserve > d.dailyCapacity) throw new QueueException("VALIDATION", "Emergency reserve must be between 0 and daily capacity.");
        d.departmentId = id; db.collection("departments").document(id).set(d).get(); return d;
    }

    @GetMapping("/doctors")
    public List<Doctor> doctors() throws Exception { return db.collection("doctors").get().get().toObjects(Doctor.class); }

    @GetMapping("/doctors/available")
    public List<Doctor> available(@RequestParam String departmentId) throws Exception {
        List<Doctor> all = db.collection("doctors").whereArrayContains("departmentIds", departmentId).get().get().toObjects(Doctor.class);
        return all.stream().filter(d -> d.status == DoctorStatus.ONLINE).collect(java.util.stream.Collectors.toList());
    }

    public record AvailabilityBody(DoctorStatus status) {}
    @PutMapping("/doctors/{id}/availability")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public Doctor availability(@AuthenticationPrincipal AppUser u, @PathVariable String id, @RequestBody AvailabilityBody b) {
        if (u.role().equals("DOCTOR") && !id.equals(u.doctorId())) throw new QueueException("FORBIDDEN", "Not allowed.");
        return engine.handleDoctorAvailabilityChange(id, b.status());
    }

    @GetMapping("/recommendations/doctors")
    public List<DoctorRecommender.Option> recommend(@RequestParam String departmentId) { return recs.forDepartment(departmentId); }

    @GetMapping("/public/queue-display/{departmentId}")
    public PublicDisplayService.Display publicDisplay(@PathVariable String departmentId) { return display.forDepartment(departmentId); }

    @GetMapping("/config/queue-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public QueueConfig rules() { return cfg.current(); }

    @PutMapping("/config/queue-rules")
    @PreAuthorize("hasRole('ADMIN')")
    public QueueConfig saveRules(@RequestBody QueueConfig c) { return cfg.save(c); }
}
