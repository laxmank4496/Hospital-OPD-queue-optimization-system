import numpy as np


def fairness_report(waits, delayed_threshold: float = 30.0) -> dict:
    """Same definitions as the Java FairnessMetrics (Jain's index)."""
    if not waits:
        return {"patientsServed": 0, "averageWait": 0, "maxWait": 0, "variance": 0, "jainIndex": 1.0, "patientsDelayed": 0}
    x = np.asarray(waits, dtype=float)
    ss = float((x ** 2).sum())
    return {"patientsServed": int(len(x)), "averageWait": float(x.mean()), "maxWait": float(x.max()),
            "variance": float(x.var()), "jainIndex": 1.0 if ss == 0 else float(x.sum() ** 2 / (len(x) * ss)),
            "patientsDelayed": int((x > delayed_threshold).sum())}
