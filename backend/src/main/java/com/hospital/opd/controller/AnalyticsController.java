package com.hospital.opd.controller;

import com.hospital.opd.queue.QueueException;
import com.hospital.opd.security.AppUser;
import com.hospital.opd.service.AnalyticsService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** ADMIN sees everything; a DOCTOR is always restricted to their own numbers (enforced here, not in the UI). */
@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR')")
public class AnalyticsController {
    private final AnalyticsService svc;
    public AnalyticsController(AnalyticsService s) { svc = s; }

    private Map<String, Object> load(AppUser u, int days) throws Exception {
        String doc = null;
        if (u.role().equals("DOCTOR")) {
            if (u.doctorId() == null) throw new QueueException("FORBIDDEN", "Not allowed.");
            doc = u.doctorId();
        }
        return svc.dashboard(doc, days);
    }
    private static Map<String, Object> pick(Map<String, Object> all, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, all.get(k));
        return out;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@AuthenticationPrincipal AppUser u, @RequestParam(defaultValue = "7") int days) throws Exception {
        return load(u, days);
    }
    @GetMapping("/waiting-time")
    public Map<String, Object> waiting(@AuthenticationPrincipal AppUser u, @RequestParam(defaultValue = "7") int days) throws Exception {
        return pick(load(u, days), "waitByHour", "trend", "today");
    }
    @GetMapping("/doctor-utilization")
    public Map<String, Object> utilization(@AuthenticationPrincipal AppUser u) throws Exception {
        return pick(load(u, 1), "utilization", "durationByDoctor");
    }
    @GetMapping("/fairness")
    public Map<String, Object> fairness(@AuthenticationPrincipal AppUser u) throws Exception {
        return pick(load(u, 1), "fairness");
    }
}
