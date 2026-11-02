package com.hospital.opd.security;

public record AppUser(String uid, String role, String doctorId) {
    public boolean isStaff() { return role.equals("ADMIN") || role.equals("RECEPTIONIST"); }
}
