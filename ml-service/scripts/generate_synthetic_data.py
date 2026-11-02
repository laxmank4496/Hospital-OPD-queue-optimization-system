"""
DEMO ONLY. Simulates consultation history so the training pipeline can be demonstrated before a hospital has
real data. Ground truth wait = sum of the durations of the patients ahead (+ noise), with doctors of different
speed and a morning-peak slowdown. Models trained on this are labelled dataSource="synthetic-demo".
"""
import json
import sys
import numpy as np

rng = np.random.default_rng(7)
DOCS = {"DOC001": ("GEN001", 7.0), "DOC002": ("GEN001", 6.0), "DOC003": ("DER001", 8.0), "DOC004": ("ORT001", 9.0)}


def generate(n=1500):
    recs, t = [], 1_760_000_000_000
    for _ in range(n):
        doc, (dep, avg) = list(DOCS.items())[rng.integers(len(DOCS))]
        hour, dow = int(rng.integers(8, 17)), int(rng.integers(0, 7))
        ahead = int(rng.integers(0, 14))
        peak = 1.25 if 10 <= hour <= 12 else 1.0
        durs = rng.lognormal(np.log(avg * peak), 0.35, size=max(ahead, 1))[:ahead]
        wait = float(durs.sum() + rng.normal(0, 1.5)) + (3.0 if dow == 1 else 0.0)  # Mondays are slower
        t += int(rng.integers(60_000, 600_000))
        recs.append({"doctorId": doc, "departmentId": dep, "patientsAheadAtJoin": ahead, "avgDurationAtJoin": avg,
                     "hour": hour, "dayOfWeek": dow, "waitedMinutes": max(0.0, wait), "completedAt": t})
    return recs


if __name__ == "__main__":
    json.dump({"records": generate(int(sys.argv[1]) if len(sys.argv) > 1 else 1500), "dataSource": "synthetic-demo"}, sys.stdout)
