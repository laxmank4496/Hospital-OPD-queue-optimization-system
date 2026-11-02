package com.hospital.opd.model;

import java.util.ArrayList;
import java.util.List;

public class Doctor {
    public String doctorId;
    public String name;
    public List<String> departmentIds = new ArrayList<>();
    public DoctorStatus status = DoctorStatus.OFFLINE;
    public double averageConsultationDuration;        // 0 = no data yet
    public List<Double> recentConsultationDurations = new ArrayList<>();  // last N, newest last
    public int consultationsCompleted;
    public Long lastCompletedAt;

    public Doctor() {}
    public Doctor(String id, String name, List<String> depts, DoctorStatus status, double avg) {
        this.doctorId = id; this.name = name; this.departmentIds = new ArrayList<>(depts);
        this.status = status; this.averageConsultationDuration = avg;
    }
    public Doctor copy() {
        Doctor d = new Doctor(doctorId, name, departmentIds, status, averageConsultationDuration);
        d.recentConsultationDurations = new ArrayList<>(recentConsultationDurations);
        d.consultationsCompleted = consultationsCompleted; d.lastCompletedAt = lastCompletedAt;
        return d;
    }
}
