package com.hospital.opd.service;

import com.hospital.opd.model.DoctorStatus;
import com.hospital.opd.optimization.DoctorRecommender;
import com.hospital.opd.queue.QueueEngine;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final QueueEngine engine; private final MlClient ml; private final ConfigService cfg; private final Clock clock;
    public RecommendationService(QueueEngine e, MlClient m, ConfigService c, Clock clock) { engine = e; ml = m; cfg = c; this.clock = clock; }

    /** One batched ML call per request for ONLINE doctors only; any failure => baseline (patientsAhead x avg). */
    public List<DoctorRecommender.Option> forDepartment(String departmentId) {
        List<QueueEngine.DoctorLoad> loads = engine.doctorLoads(departmentId);
        ZonedDateTime now = ZonedDateTime.now(clock);
        Map<String, Map<String, Object>> reqs = new LinkedHashMap<>();
        for (var l : loads) if (l.status() == DoctorStatus.ONLINE)
            reqs.put(l.doctorId(), Map.of("doctorId", l.doctorId(), "departmentId", departmentId,
                "patientsAhead", l.patientsAhead(), "queueLength", l.queueLength(),
                "averageConsultationDuration", Math.max(0.1, l.avgConsultationMinutes()),
                "recentConsultationDurations", l.recentDurations(), "hour", now.getHour(),
                "dayOfWeek", now.getDayOfWeek().getValue() % 7));
        Map<String, MlClient.Prediction> preds = ml.predictBatch(reqs);
        Map<String, Double> wait = new HashMap<>(); Map<String, String> src = new HashMap<>();
        preds.forEach((id, p) -> { wait.put(id, p.minutes()); src.put(id, p.source()); });
        return DoctorRecommender.rank(loads, wait, src, cfg.current());
    }
}
