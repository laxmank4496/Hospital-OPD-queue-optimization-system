"""
Waiting-time prediction.

Model choice: RandomForestRegressor.
 - Small tabular data with non-linear interactions (queue length x doctor speed x time of day).
 - No feature scaling needed, robust to outliers, few hyper-parameters, hard to badly overfit.
 - Fast inference; feature importances are explainable in a viva.
Baseline: waiting = patientsAhead * averageConsultationDuration.
Safety rule: the ML model is only used if it BEATS the baseline MAE on a chronological hold-out set.
Until then (or with too little data) the service answers with the baseline and says so.
"""
import json
import os
import threading
from pathlib import Path
from typing import Dict, List, Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

CATEGORICAL = ["doctorId", "departmentId"]
NUMERIC = ["patientsAhead", "queueLength", "avgDuration", "recentMean", "recentStd", "hour", "dayOfWeek", "baseline"]
MIN_SAMPLES = int(os.getenv("MIN_TRAIN_SAMPLES", "100"))
MODEL_DIR = Path(os.getenv("MODEL_DIR", "model_store"))


def _features(rows: List[dict]) -> pd.DataFrame:
    out = []
    for r in rows:
        avg = float(r["avgDuration"])
        recent = r.get("recent") or []
        rm = float(np.mean(recent)) if recent else float(r.get("recentMean") or avg)
        rs = float(np.std(recent)) if len(recent) > 1 else float(r.get("recentStd") or 0.0)
        ahead = int(r["patientsAhead"])
        out.append({
            "doctorId": r["doctorId"], "departmentId": r["departmentId"],
            "patientsAhead": ahead, "queueLength": int(r.get("queueLength") if r.get("queueLength") is not None else ahead),
            "avgDuration": avg, "recentMean": rm, "recentStd": rs,
            "hour": int(r["hour"]), "dayOfWeek": int(r["dayOfWeek"]), "baseline": ahead * avg,
        })
    return pd.DataFrame(out, columns=CATEGORICAL + NUMERIC)


def _rmse(y, p) -> float:
    return float(np.sqrt(mean_squared_error(y, p)))


class ModelManager:
    def __init__(self):
        self._lock = threading.Lock()
        self.pipeline: Optional[Pipeline] = None
        self.version = "baseline"
        self.use_ml = False
        self.metrics: Dict = {"status": "Insufficient historical data", "minSamples": MIN_SAMPLES}
        self._load()

    # ---------------------------------------------------------------- inference
    @staticmethod
    def baseline(req: dict) -> float:
        return float(req["patientsAhead"]) * float(req["avgDuration"])

    def predict(self, req: dict) -> dict:
        base = self.baseline(req)
        with self._lock:
            pipe, use_ml, version = self.pipeline, self.use_ml, self.version
        if pipe is not None and use_ml:
            pred = float(pipe.predict(_features([req]))[0])
            return {"ml": max(0.0, pred), "baseline": base, "version": version, "used": "ml"}
        return {"ml": base, "baseline": base, "version": "baseline", "used": "baseline"}

    # ---------------------------------------------------------------- training
    def train(self, records: List[dict], data_source: str) -> dict:
        n = len(records)
        if n < MIN_SAMPLES:
            with self._lock:
                self.metrics = {"status": "Insufficient historical data", "samples": n, "minSamples": MIN_SAMPLES,
                                "dataSource": data_source}
            return self.metrics
        rows = [{
            "doctorId": r["doctorId"], "departmentId": r["departmentId"], "patientsAhead": r["patientsAheadAtJoin"],
            "queueLength": r.get("queueLength"), "avgDuration": r["avgDurationAtJoin"], "recentMean": r.get("recentMean"),
            "recentStd": r.get("recentStd"), "hour": r["hour"], "dayOfWeek": r["dayOfWeek"],
        } for r in records]
        order = list(range(n))
        if all(r.get("completedAt") is not None for r in records):
            order.sort(key=lambda i: records[i]["completedAt"])      # chronological split: no future leakage
        X = _features([rows[i] for i in order])
        y = np.array([records[i]["waitedMinutes"] for i in order], dtype=float)
        cut = int(n * 0.8)
        Xtr, Xte, ytr, yte = X.iloc[:cut], X.iloc[cut:], y[:cut], y[cut:]

        pre = ColumnTransformer([("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL)], remainder="passthrough")
        pipe = Pipeline([("pre", pre), ("rf", RandomForestRegressor(n_estimators=200, min_samples_leaf=3, random_state=42, n_jobs=-1))])
        pipe.fit(Xtr, ytr)

        ml_pred = np.clip(pipe.predict(Xte), 0, None)
        base_pred = Xte["baseline"].to_numpy()
        m = {
            "status": "trained", "samples": n, "trainSamples": cut, "testSamples": n - cut, "dataSource": data_source,
            "baselineMAE": float(mean_absolute_error(yte, base_pred)), "mlMAE": float(mean_absolute_error(yte, ml_pred)),
            "baselineRMSE": _rmse(yte, base_pred), "mlRMSE": _rmse(yte, ml_pred),
        }
        use_ml = m["mlMAE"] < m["baselineMAE"]
        m["mlUsedForPredictions"] = bool(use_ml)
        # final model uses all data once evaluated
        pipe.fit(X, y)
        version = f"rf-{pd.Timestamp.utcnow().strftime('%Y%m%d%H%M%S')}"
        with self._lock:
            self.pipeline, self.use_ml, self.version, self.metrics = pipe, use_ml, version, {**m, "modelVersion": version}
        self._save()
        return self.metrics

    # ---------------------------------------------------------------- persistence
    def _save(self):
        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        joblib.dump({"pipeline": self.pipeline, "version": self.version, "use_ml": self.use_ml}, MODEL_DIR / "model.joblib")
        (MODEL_DIR / "metrics.json").write_text(json.dumps(self.metrics))

    def _load(self):
        p = MODEL_DIR / "model.joblib"
        if p.exists():
            d = joblib.load(p)
            self.pipeline, self.version, self.use_ml = d["pipeline"], d["version"], d["use_ml"]
            mp = MODEL_DIR / "metrics.json"
            if mp.exists():
                self.metrics = json.loads(mp.read_text())
