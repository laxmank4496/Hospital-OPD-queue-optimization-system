package com.hospital.opd.model;

public class Department {
    public String departmentId;
    public String name;
    public String code;               // token prefix, e.g. GM
    public int dailyCapacity = 30;    // total OPD slots per day
    public int emergencyReserve = 3;  // slots normal joins may never consume

    public Department() {}
    public Department(String id, String name, String code, int cap, int reserve) {
        this.departmentId = id; this.name = name; this.code = code;
        this.dailyCapacity = cap; this.emergencyReserve = reserve;
    }
    public int normalCapacity() { return dailyCapacity - emergencyReserve; }
}
