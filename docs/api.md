# REST API (JSON, `Authorization: Bearer <Firebase ID token>`)
Errors: `{ "code": "...", "message": "..." }` (codes: NOT_FOUND, FORBIDDEN, VALIDATION, DOCTOR_UNAVAILABLE, ALREADY_IN_QUEUE,
QUEUE_FULL, CAPACITY_FULL, CANCEL_NOT_ALLOWED, INVALID_TRANSITION, PATIENT_ALREADY_CALLED, DOCTOR_BUSY).

| Method & path | Roles | Notes |
|---|---|---|
| GET /api/users/me | any | uid, role, doctorId from the verified token |
| GET /api/departments | any | |
| PUT /api/departments/{id} | ADMIN | dailyCapacity, emergencyReserve |
| GET /api/doctors, /api/doctors/available?departmentId= | any | |
| PUT /api/doctors/{id}/availability | DOCTOR(own), ADMIN | `{status: ONLINE|OFFLINE|ON_BREAK}` |
| GET /api/recommendations/doctors?departmentId= | any | ranked options, one `recommended` |
| POST /api/queues/join | PATIENT, RECEPTIONIST, ADMIN | patient => ONLINE self; staff => WALK_IN (+priority/emergency). `idempotencyKey` optional |
| GET /api/queues/{id}, /{id}/position | owner/staff | position, patientsAhead, baseline wait |
| POST /api/queues/{id}/check-in, /cancel | owner/staff | |
| POST /api/queues/{doctorId}/call-next | DOCTOR(own), staff | 204 if nobody is checked in |
| POST /api/queues/{id}/start-consultation, /complete-consultation | DOCTOR(own), ADMIN | |
| POST /api/queues/{id}/no-show, /late | DOCTOR, RECEPTIONIST, ADMIN | |
| GET /api/queues/doctor/{doctorId} | DOCTOR(own), staff | ordered live queue |
| GET /api/queues/capacity/{departmentId} | staff | |
| GET/PUT /api/config/queue-rules | ADMIN | grace, cancel policy, weights |
| GET /api/public/queue-display/{departmentId} | public | tokens only |
| WS /ws (STOMP) topics | public | /topic/doctor/{id}, /topic/department/{id}, /topic/entry/{id} |

ML service: POST /predict/waiting-time, /predict/doctor-waiting-time, /predict/batch, POST /model/train,
GET /model/status, GET /model/metrics, POST /evaluate/fairness.

## v2 additions
| Method & path | Roles | Notes |
|---|---|---|
| GET /api/analytics/dashboard?days=7 | ADMIN, DOCTOR(own data) | today's stats, per-department, wait by hour, utilization, trend, outcomes, fairness |
| GET /api/analytics/waiting-time, /doctor-utilization, /fairness | ADMIN, DOCTOR | slices of the dashboard |
| GET /api/history/mine | PATIENT | own queue history |
| GET /api/queues/lookup?token= | RECEPTIONIST, ADMIN | today's entry by token |
| POST /api/departments, DELETE /api/departments/{id} | ADMIN | |
| POST /api/doctors, PUT/DELETE /api/doctors/{id} | ADMIN | POST can also create the doctor's login |
| GET/POST /api/admin/staff | ADMIN | staff logins; role stored as a Firebase custom claim |
| POST /api/admin/simulate/patients, /history, /reset | ADMIN | demo data only |
