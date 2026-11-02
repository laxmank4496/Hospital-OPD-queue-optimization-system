package com.hospital.opd.model;

/** Per department, per day capacity counters (document id = departmentId_yyyy-MM-dd). */
public class DailyUsage {
    public String departmentId;
    public String day;
    public int normalUsed;
    public int emergencyUsed;
    public DailyUsage() {}
    public DailyUsage(String d, String day) { this.departmentId = d; this.day = day; }
    public DailyUsage copy() { DailyUsage u = new DailyUsage(departmentId, day); u.normalUsed = normalUsed; u.emergencyUsed = emergencyUsed; return u; }
}
