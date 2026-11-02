package com.hospital.opd.model;

import java.util.ArrayList;
import java.util.List;

/** Admin-configurable rules (persisted in systemConfig/queueRules). */
public class QueueConfig {
    public int graceMinutes = 5;                       // called -> must arrive within this
    public boolean lateKeepsPosition = true;
    public boolean autoCallNext = true;                // advance queue when a consultation completes
    // List, not Set: Firestore cannot (de)serialize java.util.Set
    public List<QueueStatus> cancellableStatuses = new ArrayList<>(List.of(QueueStatus.WAITING));
    public int maxActivePerDoctor = 60;                // "queue is full"
    public double defaultConsultationMinutes = 7.0;    // used until a doctor has history
    // Fairness: effective priority = hospitalPriority + emergencyBonus + min(maxWaitBoost, waited * boostPerMinute)
    public double emergencyPriority = 1000;
    public double waitBoostPerMinute = 1.0;
    public double maxWaitBoost = 120;
    // Recommendation objective weights (see DoctorRecommender)
    public double wWait = 0.6, wQueue = 0.2, wAvailability = 0.1, wFairness = 0.05, wIdle = 0.05;
}
