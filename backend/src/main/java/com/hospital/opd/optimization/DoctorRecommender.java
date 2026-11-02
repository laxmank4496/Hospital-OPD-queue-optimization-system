package com.hospital.opd.optimization;

import com.hospital.opd.model.DoctorStatus;
import com.hospital.opd.model.QueueConfig;
import com.hospital.opd.queue.QueueEngine.DoctorLoad;
import java.util.*;

/**
 * Doctor recommendation == the optimization step. It never assigns; it ranks.
 *
 * Hard constraints (candidate is dropped from ranking, still listed as unavailable):
 *   - doctor ONLINE            - doctor serves the department (guaranteed by the load query)
 * Objective, minimised over eligible doctors (each term normalised to [0,1] by dividing by the max
 * across candidates, so weights are comparable):
 *
 *   score = wWait         * predictedWait / max(predictedWait)
 *         + wQueue        * queueLength   / max(queueLength)
 *         + wAvailability * availabilityPenalty        (0 for ONLINE; ON_BREAK is not eligible so always 0 here)
 *         + wFairness     * longestCurrentWait / max   (don't pile new patients behind already-long waits)
 *         + wIdle         * (doctor idle ? 0 : 1)      (small bonus for putting work on idle doctors => utilisation)
 *
 * The weights are admin-configurable (QueueConfig). The patient still chooses.
 */
public final class DoctorRecommender {
    private DoctorRecommender() {}

    public record Option(String doctorId, String doctorName, DoctorStatus status, boolean available,
                         int patientsAhead, double predictedWaitMinutes, String predictionSource,
                         Double score, boolean recommended, String reason) {}

    /** @param predictedWait doctorId -> minutes from the ML service; missing => baseline from the load. */
    public static List<Option> rank(List<DoctorLoad> loads, Map<String, Double> predictedWait,
                                    Map<String, String> predictionSource, QueueConfig cfg) {
        List<DoctorLoad> eligible = loads.stream().filter(l -> l.status() == DoctorStatus.ONLINE).toList();
        double maxWait = 1e-9, maxQueue = 1e-9, maxLong = 1e-9;
        for (DoctorLoad l : eligible) {
            maxWait = Math.max(maxWait, wait(l, predictedWait));
            maxQueue = Math.max(maxQueue, l.queueLength());
            maxLong = Math.max(maxLong, l.maxCurrentWaitMinutes());
        }
        Map<String, Double> scores = new HashMap<>();
        for (DoctorLoad l : eligible) {
            double s = cfg.wWait * wait(l, predictedWait) / maxWait
                     + cfg.wQueue * l.queueLength() / maxQueue
                     + cfg.wAvailability * 0.0
                     + cfg.wFairness * l.maxCurrentWaitMinutes() / maxLong
                     + cfg.wIdle * (l.idle() ? 0 : 1);
            scores.put(l.doctorId(), s);
        }
        String best = eligible.stream()
            .min(Comparator.comparingDouble((DoctorLoad l) -> scores.get(l.doctorId()))
                .thenComparingDouble(l -> wait(l, predictedWait))
                .thenComparingInt(DoctorLoad::patientsAhead))
            .map(DoctorLoad::doctorId).orElse(null);

        List<Option> out = new ArrayList<>();
        for (DoctorLoad l : loads) {
            boolean ok = l.status() == DoctorStatus.ONLINE;
            boolean rec = ok && l.doctorId().equals(best);
            out.add(new Option(l.doctorId(), l.name(), l.status(), ok, l.patientsAhead(), wait(l, predictedWait),
                predictionSource.getOrDefault(l.doctorId(), "BASELINE"), ok ? scores.get(l.doctorId()) : null, rec,
                rec ? "Recommended based on predicted waiting time" : ok ? null : "Doctor is " + l.status().name().toLowerCase().replace('_', ' ')));
        }
        out.sort(Comparator.comparing((Option o) -> !o.available()).thenComparingDouble(o -> o.score() == null ? 1e9 : o.score()));
        return out;
    }

    private static double wait(DoctorLoad l, Map<String, Double> predicted) {
        return predicted.getOrDefault(l.doctorId(), l.baselineWaitMinutes());
    }
}
