from typing import List, Optional
from pydantic import BaseModel, Field


class PredictRequest(BaseModel):
    doctorId: str
    departmentId: str
    patientsAhead: int = Field(ge=0)
    queueLength: Optional[int] = Field(default=None, ge=0)
    averageConsultationDuration: float = Field(gt=0)
    recentConsultationDurations: List[float] = []
    hour: int = Field(ge=0, le=23)
    dayOfWeek: int = Field(ge=0, le=6)  # Sunday = 0


class PredictResponse(BaseModel):
    predictedWaitingTimeMinutes: float
    baselineWaitingTimeMinutes: float
    modelVersion: str
    usedModel: str  # "ml" or "baseline"


class BatchRequest(BaseModel):
    items: List[PredictRequest] = Field(max_length=200)


class BatchResponse(BaseModel):
    predictions: List[PredictResponse]


class TrainingRecord(BaseModel):
    doctorId: str
    departmentId: str
    patientsAheadAtJoin: int
    avgDurationAtJoin: float
    hour: int
    dayOfWeek: int
    waitedMinutes: float
    queueLength: Optional[int] = None
    recentMean: Optional[float] = None
    recentStd: Optional[float] = None
    completedAt: Optional[int] = None


class TrainRequest(BaseModel):
    records: List[TrainingRecord]
    dataSource: str = "production"  # use "synthetic-demo" for generated data


class FairnessRequest(BaseModel):
    waitsMinutes: List[float]
    delayedThresholdMinutes: float = 30.0
