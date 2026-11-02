package com.hospital.opd.optimization;

import java.util.List;

/**
 * Fairness / performance metrics computed from REAL observed waits only (minutes from join to start of consultation).
 * Jain's fairness index J = (sum x)^2 / (n * sum x^2): 1.0 = everyone waited equally, 1/n = one person bore all the wait.
 */
public final class FairnessMetrics {
    private FairnessMetrics() {}

    public record Report(int patientsServed, double averageWait, double maxWait, double variance,
                         double jainIndex, int patientsDelayed) {}

    public static Report compute(List<Double> waits, double delayedThresholdMinutes) {
        int n = waits.size();
        if (n == 0) return new Report(0, 0, 0, 0, 1.0, 0);
        double sum = 0, sumSq = 0, max = 0; int delayed = 0;
        for (double w : waits) { sum += w; sumSq += w * w; max = Math.max(max, w); if (w > delayedThresholdMinutes) delayed++; }
        double mean = sum / n;
        double var = sumSq / n - mean * mean;
        double jain = sumSq == 0 ? 1.0 : (sum * sum) / (n * sumSq);
        return new Report(n, mean, max, Math.max(0, var), jain, delayed);
    }
}
