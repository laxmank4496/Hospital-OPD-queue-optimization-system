package com.hospital.opd.controller;

import com.hospital.opd.model.*;
import com.hospital.opd.queue.QueueEngine;
import com.hospital.opd.queue.QueueEngine.*;
import com.hospital.opd.queue.QueueException;
import com.hospital.opd.security.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Thin HTTP layer: authenticate -> authorize ownership -> delegate to QueueEngine. No queue logic lives here,
 * so an Android client calling these same endpoints gets identical behaviour.
 */
@RestController
@RequestMapping("/api/queues")
public class QueueController {
    public record JoinBody(@NotBlank String doctorId, @NotBlank String departmentId, String patientName, String patientId,
                           Integer hospitalPriority, Boolean emergency, String idempotencyKey) {}

    private final QueueEngine engine;
    public QueueController(QueueEngine engine) { this.engine = engine; }

    @PostMapping("/join")
    public QueueEntry join(@AuthenticationPrincipal AppUser u, @Valid @RequestBody JoinBody b) {
        if (u.role().equals("PATIENT")) {   // patients: always themselves, never priority/emergency
            return engine.joinQueue(new JoinRequest(u.uid(), b.patientName(), b.doctorId(), b.departmentId(),
                Source.ONLINE, 0, false, b.idempotencyKey()));
        }
        if (!u.isStaff()) throw new QueueException("FORBIDDEN", "Not allowed.");
        if (b.patientName() == null || b.patientName().isBlank()) throw new QueueException("VALIDATION", "Patient name is required.");
        String pid = b.patientId() != null ? b.patientId() : "walkin-" + java.util.UUID.randomUUID();
        return engine.joinQueue(new JoinRequest(pid, b.patientName(), b.doctorId(), b.departmentId(), Source.WALK_IN,
            b.hospitalPriority() == null ? 0 : b.hospitalPriority(), Boolean.TRUE.equals(b.emergency()), b.idempotencyKey()));
    }

    /** The signed-in patient's current active entry, or 204. */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('PATIENT')")
    public ResponseEntity<QueueEntry> mine(@AuthenticationPrincipal AppUser u) {
        return engine.activeEntryForPatient(u.uid()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public QueueEntry get(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        return owned(u, id);
    }

    @GetMapping("/{id}/position")
    public QueueView position(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        owned(u, id); return engine.view(id);
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAnyRole('PATIENT','RECEPTIONIST','ADMIN')")
    public QueueEntry checkIn(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        owned(u, id); return engine.checkIn(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('PATIENT','RECEPTIONIST','ADMIN')")
    public QueueEntry cancel(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        owned(u, id); return engine.cancelQueueEntry(id);
    }

    /** {id} here is the DOCTOR id: "call the next patient in this doctor's queue". 204 when nobody is checked in. */
    @PostMapping("/{id}/call-next")
    @PreAuthorize("hasAnyRole('DOCTOR','RECEPTIONIST','ADMIN')")
    public ResponseEntity<QueueEntry> callNext(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        ownDoctor(u, id);
        return engine.callNextPatient(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/start-consultation")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public QueueEntry start(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        ownDoctor(u, engine.getEntry(id).doctorId); return engine.startConsultation(id);
    }

    @PostMapping("/{id}/complete-consultation")
    @PreAuthorize("hasAnyRole('DOCTOR','ADMIN')")
    public QueueEntry complete(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        ownDoctor(u, engine.getEntry(id).doctorId); return engine.completeConsultation(id);
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('DOCTOR','RECEPTIONIST','ADMIN')")
    public QueueEntry noShow(@AuthenticationPrincipal AppUser u, @PathVariable String id) {
        if (u.role().equals("DOCTOR")) ownDoctor(u, engine.getEntry(id).doctorId);
        return engine.markNoShow(id);
    }

    @PostMapping("/{id}/late")
    @PreAuthorize("hasAnyRole('DOCTOR','RECEPTIONIST','ADMIN')")
    public QueueEntry late(@PathVariable String id) { return engine.handleLateArrival(id); }

    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('DOCTOR','RECEPTIONIST','ADMIN')")
    public List<QueueView> doctorQueue(@AuthenticationPrincipal AppUser u, @PathVariable String doctorId) {
        if (u.role().equals("DOCTOR")) ownDoctor(u, doctorId);
        return engine.doctorQueue(doctorId);
    }

    @GetMapping("/capacity/{departmentId}")
    @PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
    public CapacitySummary capacity(@PathVariable String departmentId) { return engine.capacity(departmentId); }

    // ---- authorization helpers (backend is the authority; the frontend only hides buttons)
    private QueueEntry owned(AppUser u, String id) {
        QueueEntry e = engine.getEntry(id);
        if (u.role().equals("PATIENT") && !u.uid().equals(e.patientId)) throw new QueueException("FORBIDDEN", "Not allowed.");
        if (u.role().equals("DOCTOR") && !e.doctorId.equals(u.doctorId())) throw new QueueException("FORBIDDEN", "Not allowed.");
        return e;
    }
    private void ownDoctor(AppUser u, String doctorId) {
        if (u.role().equals("DOCTOR") && !doctorId.equals(u.doctorId())) throw new QueueException("FORBIDDEN", "Not allowed.");
    }
}
