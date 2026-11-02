import os, tempfile
os.environ["MODEL_DIR"] = tempfile.mkdtemp()
os.environ["MIN_TRAIN_SAMPLES"] = "100"
from fastapi.testclient import TestClient
from app.main import app
from scripts.generate_synthetic_data import generate

c = TestClient(app)
REQ = {"doctorId": "DOC001", "departmentId": "GEN001", "patientsAhead": 5, "queueLength": 8,
       "averageConsultationDuration": 6.8, "recentConsultationDurations": [5, 7, 8, 6, 9], "hour": 11, "dayOfWeek": 2}


def test_baseline_used_without_data():
    r = c.post("/predict/waiting-time", json=REQ).json()
    assert r["usedModel"] == "baseline" and r["predictedWaitingTimeMinutes"] == 34.0
    assert c.get("/model/metrics").json()["status"] == "Insufficient historical data"


def test_insufficient_data_refuses_to_train():
    m = c.post("/model/train", json={"records": generate(20)}).json()
    assert m["status"] == "Insufficient historical data"


def test_train_reports_real_metrics_and_compares_to_baseline():
    m = c.post("/model/train", json={"records": generate(1500), "dataSource": "synthetic-demo"}).json()
    assert m["status"] == "trained" and m["dataSource"] == "synthetic-demo"
    for k in ("baselineMAE", "mlMAE", "baselineRMSE", "mlRMSE"):
        assert m[k] >= 0
    r = c.post("/predict/waiting-time", json=REQ).json()
    assert r["usedModel"] == ("ml" if m["mlUsedForPredictions"] else "baseline")


def test_batch_and_validation():
    assert len(c.post("/predict/batch", json={"items": [REQ, REQ]}).json()["predictions"]) == 2
    assert c.post("/predict/waiting-time", json={**REQ, "hour": 30}).status_code == 422


def test_fairness_endpoint():
    assert c.post("/evaluate/fairness", json={"waitsMinutes": [10, 10, 10]}).json()["jainIndex"] == 1.0
