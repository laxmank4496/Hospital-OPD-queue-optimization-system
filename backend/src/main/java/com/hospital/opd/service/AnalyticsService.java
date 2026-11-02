package com.hospital.opd.service;

import com.google.cloud.firestore.Firestore;
import com.hospital.opd.model.*;
import com.hospital.opd.optimization.FairnessMetrics;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Read-only analytics computed from REAL stored rows (consultations + queueEntries + dailyUsage).
 * Nothing is estimated or fabricated; with no data the numbers are 0 / empty lists.
 * Demo rows from the simulator are flagged (Consultation.simulated) and counted in "demoRecords".
 * Doctor utilisation = busy minutes / minutes from that doctor's first consultation today until now (capped at 100%).
 */
@Service
public class AnalyticsService {
    private final Firestore db; private final Clock clock;
    public AnalyticsService(Firestore db, Clock clock) { this.db = db; this.clock = clock; }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }
    private static double avg(Collection<Double> xs) { return xs.isEmpty() ? 0 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    private static double r1(double v) { return Math.round(v * 10.0) / 10.0; }

    public Map<String, Object> dashboard(String doctorId, int days) throws Exception {
        days = Math.max(1, Math.min(days, 30));
        ZoneId z = clock.getZone(); LocalDate today = LocalDate.now(clock);
        long startToday = today.atStartOfDay(z).toInstant().toEpochMilli();
        long since = today.minusDays(days - 1L).atStartOfDay(z).toInstant().toEpochMilli();
        long now = clock.millis();

        List<Consultation> all = new ArrayList<>(db.collection("consultations").whereGreaterThanOrEqualTo("completedAt", since).get().get().toObjects(Consultation.class));
        List<QueueEntry> entries = new ArrayList<>(db.collection("queueEntries").whereGreaterThanOrEqualTo("joinedAt", startToday).get().get().toObjects(QueueEntry.class));
        Map<String, Department> deps = new HashMap<>();
        for (Department d : db.collection("departments").get().get().toObjects(Department.class)) deps.put(d.departmentId, d);
        Map<String, Doctor> docs = new HashMap<>();
        for (Doctor d : db.collection("doctors").get().get().toObjects(Doctor.class)) docs.put(d.doctorId, d);
        if (doctorId != null) {
            all.removeIf(c -> !doctorId.equals(c.doctorId));
            entries.removeIf(e -> !doctorId.equals(e.doctorId));
        }

        List<Consultation> todays = all.stream().filter(c -> c.completedAt >= startToday).collect(Collectors.toList());
        List<Double> waits = todays.stream().map(c -> c.waitedMinutes).collect(Collectors.toList());
        List<Double> durs = todays.stream().map(c -> c.durationMinutes).collect(Collectors.toList());

        long waiting = entries.stream().filter(e -> e.status == QueueStatus.WAITING || e.status == QueueStatus.CHECKED_IN || e.status == QueueStatus.LATE).count();
        long inService = entries.stream().filter(e -> e.status.isInFlight()).count();
        long cancelled = entries.stream().filter(e -> e.status == QueueStatus.CANCELLED).count();
        long noShow = entries.stream().filter(e -> e.status == QueueStatus.NO_SHOW).count();
        int registered = entries.size();

        Map<String, Long> perDep = entries.stream().collect(Collectors.groupingBy(e -> e.departmentId, Collectors.counting()));
        List<Map<String, Object>> byDepartment = new ArrayList<>();
        perDep.forEach((id, n) -> byDepartment.add(m("department", deps.containsKey(id) ? deps.get(id).name : id, "patients", n)));
        byDepartment.sort(Comparator.comparing(x -> String.valueOf(x.get("department"))));

        Map<Integer, List<Double>> byHour = new TreeMap<>();
        for (Consultation c : todays)
            byHour.computeIfAbsent(Instant.ofEpochMilli(c.completedAt).atZone(z).getHour(), k -> new ArrayList<>()).add(c.waitedMinutes);
        List<Map<String, Object>> waitByHour = new ArrayList<>();
        byHour.forEach((h, l) -> waitByHour.add(m("hour", String.format("%02d:00", h), "avgWait", r1(avg(l)), "patients", l.size())));

        Map<String, List<Consultation>> perDoc = todays.stream().collect(Collectors.groupingBy(c -> c.doctorId));
        List<Map<String, Object>> durationByDoctor = new ArrayList<>(), utilization = new ArrayList<>();
        List<Double> utils = new ArrayList<>();
        for (Map.Entry<String, List<Consultation>> en : perDoc.entrySet()) {
            List<Consultation> list = en.getValue();
            String name = docs.containsKey(en.getKey()) ? docs.get(en.getKey()).name : en.getKey();
            double busy = list.stream().mapToDouble(c -> c.durationMinutes).sum();
            long first = list.stream().mapToLong(c -> c.completedAt - (long) (c.durationMinutes * 60000)).min().orElse(now);
            double elapsed = Math.max(busy, (now - first) / 60000.0);
            double u = elapsed <= 0 ? 0 : Math.min(100.0, busy / elapsed * 100.0);
            utils.add(u);
            durationByDoctor.add(m("doctor", name, "avgDuration", r1(avg(list.stream().map(c -> c.durationMinutes).collect(Collectors.toList()))), "patients", list.size()));
            utilization.add(m("doctor", name, "utilization", r1(u), "busyMinutes", r1(busy)));
        }

        Map<LocalDate, List<Consultation>> perDay = all.stream().collect(Collectors.groupingBy(c -> Instant.ofEpochMilli(c.completedAt).atZone(z).toLocalDate()));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            List<Consultation> l = perDay.getOrDefault(d, new ArrayList<>());
            trend.add(m("date", d.toString(), "avgWait", r1(avg(l.stream().map(c -> c.waitedMinutes).collect(Collectors.toList()))),
                "avgDuration", r1(avg(l.stream().map(c -> c.durationMinutes).collect(Collectors.toList()))), "patients", l.size()));
        }

        FairnessMetrics.Report f = FairnessMetrics.compute(waits, 30);

        int reserve = 0, reserveUsed = 0;
        for (DailyUsage u : db.collection("dailyUsage").whereEqualTo("day", today.toString()).get().get().toObjects(DailyUsage.class)) reserveUsed += u.emergencyUsed;
        for (Department d : deps.values()) reserve += d.emergencyReserve;

        long online = docs.values().stream().filter(d -> d.status == DoctorStatus.ONLINE).count();
        long demo = all.stream().filter(c -> c.simulated).count();

        Map<String, Object> todayMap = m("registered", registered, "served", todays.size(), "waitingNow", waiting, "inService", inService,
            "avgWait", r1(avg(waits)), "maxWait", r1(waits.stream().mapToDouble(Double::doubleValue).max().orElse(0)),
            "avgDuration", r1(avg(durs)), "cancelled", cancelled, "noShow", noShow,
            "cancellationRate", registered == 0 ? 0 : r1(cancelled * 100.0 / registered),
            "noShowRate", registered == 0 ? 0 : r1(noShow * 100.0 / registered),
            "utilization", r1(avg(utils)));
        Map<String, Object> fair = m("patientsServed", f.patientsServed(), "averageWait", r1(f.averageWait()), "maxWait", r1(f.maxWait()),
            "variance", r1(f.variance()), "jainIndex", Math.round(f.jainIndex() * 1000) / 1000.0, "patientsDelayed", f.patientsDelayed(),
            "delayedThresholdMinutes", 30);
        List<Map<String, Object>> outcomes = new ArrayList<>();
        outcomes.add(m("name", "Completed", "value", todays.size()));
        outcomes.add(m("name", "Cancelled", "value", cancelled));
        outcomes.add(m("name", "No-show", "value", noShow));

        return m("generatedAt", now, "days", days, "today", todayMap,
            "doctors", m("online", online, "total", docs.size()), "emergency", m("used", reserveUsed, "reserve", reserve),
            "byDepartment", byDepartment, "waitByHour", waitByHour, "durationByDoctor", durationByDoctor,
            "utilization", utilization, "trend", trend, "outcomes", outcomes, "fairness", fair, "demoRecords", demo);
    }
}
