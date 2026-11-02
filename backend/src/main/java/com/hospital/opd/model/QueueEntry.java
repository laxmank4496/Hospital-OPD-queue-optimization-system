package com.hospital.opd.model;

/** Public mutable fields keep this usable by Firestore (toObject), Jackson and tests. Times are epoch millis. */
public class QueueEntry {
    public String queueEntryId;
    public String token;            // e.g. GM-026 (display only; never used for ordering)
    public int tokenNumber;
    public String patientId;
    public String patientName;      // never exposed on the public display
    public String doctorId;
    public String departmentId;
    public Source source;
    public QueueStatus status;
    public int hospitalPriority;    // set only by staff/admin
    public boolean emergency;       // consumes emergency reserve
    public String idempotencyKey;

    public Long joinedAt, checkedInAt, calledAt, consultationStartedAt, completedAt,
                noShowAt, cancelledAt, lateAt;
    public double predictedWaitingTime;     // baseline estimate stored at join (minutes)

    // Snapshot at join time -> training features for the ML model
    public int patientsAheadAtJoin;
    public double avgDurationAtJoin;
    public int hourAtJoin, dayOfWeekAtJoin;

    public QueueEntry() {}

    public QueueEntry copy() {
        QueueEntry c = new QueueEntry();
        c.queueEntryId = queueEntryId; c.token = token; c.tokenNumber = tokenNumber;
        c.patientId = patientId; c.patientName = patientName; c.doctorId = doctorId;
        c.departmentId = departmentId; c.source = source; c.status = status;
        c.hospitalPriority = hospitalPriority; c.emergency = emergency; c.idempotencyKey = idempotencyKey;
        c.joinedAt = joinedAt; c.checkedInAt = checkedInAt; c.calledAt = calledAt;
        c.consultationStartedAt = consultationStartedAt; c.completedAt = completedAt;
        c.noShowAt = noShowAt; c.cancelledAt = cancelledAt; c.lateAt = lateAt;
        c.predictedWaitingTime = predictedWaitingTime; c.patientsAheadAtJoin = patientsAheadAtJoin;
        c.avgDurationAtJoin = avgDurationAtJoin; c.hourAtJoin = hourAtJoin; c.dayOfWeekAtJoin = dayOfWeekAtJoin;
        return c;
    }
}
