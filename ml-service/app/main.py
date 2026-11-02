from fastapi import FastAPI
from app.optimization.fairness import fairness_report
from app.prediction.model import ModelManager
from app.schemas.schemas import (BatchRequest, BatchResponse, FairnessRequest, PredictRequest, PredictResponse,
                                 TrainRequest)

app = FastAPI(title="OPD Prediction Service", version="1.0")
manager = ModelManager()


def _to_row(r: PredictRequest) -> dict:
    return {"doctorId": r.doctorId, "departmentId": r.departmentId, "patientsAhead": r.patientsAhead,
            "queueLength": r.queueLength, "avgDuration": r.averageConsultationDuration,
            "recent": r.recentConsultationDurations, "hour": r.hour, "dayOfWeek": r.dayOfWeek}


def _predict(r: PredictRequest) -> PredictResponse:
    out = manager.predict(_to_row(r))
    return PredictResponse(predictedWaitingTimeMinutes=round(out["ml"], 1), baselineWaitingTimeMinutes=round(out["baseline"], 1),
                           modelVersion=out["version"], usedModel=out["used"])


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/predict/waiting-time", response_model=PredictResponse)
def predict_waiting_time(req: PredictRequest):
    return _predict(req)


@app.post("/predict/doctor-waiting-time", response_model=PredictResponse)
def predict_doctor_waiting_time(req: PredictRequest):
    return _predict(req)


@app.post("/predict/batch", response_model=BatchResponse)
def predict_batch(req: BatchRequest):
    return BatchResponse(predictions=[_predict(i) for i in req.items])


@app.get("/model/status")
def model_status():
    return {"modelVersion": manager.version, "usingMl": manager.use_ml,
            "status": manager.metrics.get("status"), "samples": manager.metrics.get("samples", 0),
            "minSamples": manager.metrics.get("minSamples", None) or manager.metrics.get("minSamples", 0)}


@app.get("/model/metrics")
def model_metrics():
    return manager.metrics   # only real computed values, or "Insufficient historical data"


@app.post("/model/train")
def train(req: TrainRequest):
    return manager.train([r.model_dump() for r in req.records], req.dataSource)


@app.post("/evaluate/fairness")
def evaluate_fairness(req: FairnessRequest):
    return fairness_report(req.waitsMinutes, req.delayedThresholdMinutes)
