# Queue, Recommendation, Optimization, Fairness

## State machine (`QueueStatus`)
```
WAITING -> CHECKED_IN -> CALLED -> IN_CONSULTATION -> COMPLETED
WAITING -> CANCELLED | NO_SHOW | LATE        CALLED -> LATE | NO_SHOW
LATE -> CHECKED_IN | RESCHEDULED | NO_SHOW | CANCELLED
```
Terminal: COMPLETED, CANCELLED, NO_SHOW, RESCHEDULED. Any other transition throws `InvalidTransitionException`.
Repeating a request that already took effect (check-in twice, complete twice) returns the current state: idempotent.

## Unified queue
One `queueEntries` collection, `source = ONLINE | WALK_IN`. Walk-ins enter as CHECKED_IN (they are present); online
patients enter as WAITING until they check in. Same `QueueEngine.joinQueue` for both.

## Ordering and position
```
effectivePriority = hospitalPriority + (emergency ? emergencyPriority : 0) + min(maxWaitBoost, minutesWaited * waitBoostPerMinute)
order: in-flight (IN_CONSULTATION, CALLED) first, then effectivePriority desc, joinedAt asc, tokenNumber asc
```
Position and patients-ahead are DERIVED from the live active set on every read. They are never stored.
The next patient to call is the highest-ranked entry that is CHECKED_IN.

## Fairness mechanism
`hospitalPriority` is in "waiting-minute equivalents": a priority-10 patient overtakes someone who has waited less than
10 minutes, never someone who waited longer. With all priorities 0 the order is exactly FCFS. The wait boost is capped
(`maxWaitBoost`) and only staff can set priority, so there is no starvation of non-emergency patients. Emergencies
(+1000) are deliberately dominant and limited by the emergency reserve.
Metrics (`FairnessMetrics`, same definitions in Python): average wait, max wait, variance, patients delayed
(> threshold), Jain's index `J = (sum x)^2 / (n * sum x^2)`; 1 = perfectly equal.

## Emergency capacity
Department: `dailyCapacity`, `emergencyReserve`; `normalCapacity = daily - reserve`. Counters per department per day
(`dailyUsage`). Normal join fails with CAPACITY_FULL once `normalUsed >= normalCapacity`. Emergency joins use the
reserve first, then spill into unused normal slots. Cancelling gives the slot back.

## Waiting-time estimate
Baseline = `patientsAhead * averageConsultationDuration` (doctor's rolling mean of last 20; default 7 min until data).
The Python RandomForest replaces it only when it beats the baseline MAE on a chronological hold-out. If the ML
service is down or slow (1.5 s timeout) the baseline is used. Estimates are shown as "~N minutes", never guaranteed.

## Recommendation = optimization
Hard constraints: doctor ONLINE and in the department. Minimise over eligible doctors:
```
score = wWait*wait/maxWait + wQueue*queueLen/maxQueue + wAvailability*penalty + wFairness*longestCurrentWait/max + wIdle*(busy?1:0)
```
Weights are admin-configurable (`QueueConfig`). Output is a ranking plus one flagged option; the patient chooses.
Wording is "Recommended based on predicted waiting time", never "best".

## Concurrency
Each operation is one Firestore transaction (reads first, buffered writes). Token numbers come from a counter document
`queues/{dept}_{day}` incremented in the same transaction that creates the entry, so two simultaneous registrations get
GM-030 and GM-031, never the same token. Events are published only after commit (a retried transaction can't double-publish).
`QueueEngineTest.concurrentJoinsGetUniqueTokens` runs 50 parallel joins against the same contract.
