package com.hospital.opd.model;

public class QueueEvent {
    public String eventId;
    public EventType type;
    public String queueEntryId;
    public String doctorId;
    public String departmentId;
    public long at;
    public String detail;
    public QueueEvent() {}
    public QueueEvent(EventType t, String entryId, String doctorId, String deptId, long at, String detail) {
        this.type = t; this.queueEntryId = entryId; this.doctorId = doctorId;
        this.departmentId = deptId; this.at = at; this.detail = detail;
    }
}
