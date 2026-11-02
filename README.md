<<<<<<< HEAD
# Hospital OPD Queue Optimization System

Dynamic OPD queue management + doctor recommendation + waiting-time prediction + fairness + concurrency-safe
real-time synchronisation. **Decision support, not auto-assignment: the patient always picks the doctor.**

## Build status (v2)
| Part | Status |
|---|---|
| Queue engine (state machine, tokens, ordering, fairness, emergency reserve, late/no-show, idempotency) | Done. 17 unit tests pass |
| Spring Boot API: auth, queue, recommendation, analytics, history, admin CRUD, staff logins, demo simulator | Written. Compiled and run by the project owner for v1 parts; v2 additions are syntax-checked only |
| React website: landing, about, patient / doctor / receptionist / admin portals with charts, public TV display | Built (production build verified) |
| Python prediction service | **Optional.** Off by default (`ML_ENABLED=false`). The site uses patients ahead x average consultation time |

## Architecture
```
React (next stage) --REST/STOMP--> Spring Boot --transactions--> Firestore  (+ Firebase Auth for tokens)
                                       |--HTTP (1.5 s timeout, baseline fallback)--> FastAPI ML service
```
`QueueEngine` depends only on the `QueueStore` interface: `FirestoreQueueStore` in production, `InMemoryQueueStore` in tests.

## Technical decisions
- **Firestore**: native realtime listeners and multi-document transactions (token counter + entry + capacity in one commit).
- **Spring Boot**: strongly typed domain logic, mature security/validation, shared by web and the future Android app.
- **Python**: scikit-learn ecosystem; isolated so the model can be retrained without touching queue logic.
- **RandomForestRegressor**: tabular data, non-linear interactions, no scaling, robust on small data, explainable.
  It is used only if it beats the baseline MAE on a chronological hold-out; otherwise the baseline answers. With fewer
  than `MIN_TRAIN_SAMPLES` (100) rows the service reports "Insufficient historical data".
- **Ordering/fairness/concurrency/recommendation**: see `docs/queue-algorithm.md`. API: `docs/api.md`. Android: `docs/future-android.md`.

## Measured prediction results (demo data only)
Trained on 1,500 **synthetic** consultations (`ml-service/scripts/generate_synthetic_data.py`, 80/20 chronological split):
baseline MAE 9.67 / RMSE 14.49 min, RF MAE 6.73 / RMSE 9.12 min. These numbers describe the simulator, not a hospital.
The service labels such runs `dataSource: "synthetic-demo"`; real figures appear only after training on real consultations.

## Setup

**Backend** (Java 17, Maven)
1. Firebase console: create a project, enable Email/Password auth and Firestore, download a service-account key
   to a path outside the repo. Never commit it.
2. `cp .env.example .env`, set `FIREBASE_CREDENTIALS_PATH`. Deploy `firestore.indexes.json`.
3. Seed once: set `OPD_SEED_ENABLED=true` and the `SEED_*_PASSWORD` variables, start, then set it back to false.
   Roles are stored as Firebase custom claims; the backend trusts nothing else.
4. `cd backend && mvn test && mvn spring-boot:run`

**Tests without Maven**: the engine tests use standard JUnit 5 (`backend/src/test/...`).

## Demo accounts
Created by the seeder from the emails `admin@opd.local`, `reception@opd.local`, `sharma@opd.local` (DOC001), `patient@opd.local`
with passwords you supply in environment variables. None are hard-coded.

## Frontend
```
cd frontend && copy .env.example .env   # fill in the Firebase web config
npm install && npm run dev               # http://localhost:5173
```
Live updates: pages subscribe to STOMP topics (`/topic/doctor/{id}`, `/topic/department/{id}`) and refetch when the backend publishes
after a committed transaction, with polling (5-10 s) as a fallback. The browser never computes positions or waits.
Known gaps: walk-in phone/age are not stored; the patient's own page polls until its entry is known.

## Running without the ML service (default)
Only two things need to run: the backend and the frontend. Set `ML_ENABLED=true` and start `ml-service` only if you want model-based estimates.
=======
# Hospital-OPD-queue-optimization-system
Long and unpredictable waiting times in the outpatient departments (OPDs) of hospitals reduce patient satisfaction, waste clinicians’ time . Many hospitals still rely on paper tokens and manual calling, so patients cannot see their position, so we developed Hospital OPD Queue Optimization system
>>>>>>> 5b09121c7c0b52518508bf3775771cd14c522de8
