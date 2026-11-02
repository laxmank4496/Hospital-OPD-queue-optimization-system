package com.hospital.opd.service;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.*;
import com.hospital.opd.model.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Development seeding, enabled only with OPD_SEED_ENABLED=true. Passwords are NEVER in code: they are read from
 * environment variables. A user is created only if its password variable is set (min 8 chars):
 *   SEED_ADMIN_PASSWORD, SEED_RECEPTIONIST_PASSWORD, SEED_DOCTOR_PASSWORD (used for ALL six doctors), SEED_PATIENT_PASSWORD.
 * Roles are written as Firebase custom claims (the only place the backend trusts a role from).
 */
@Component
public class DevSeeder implements CommandLineRunner {
    private final Firestore db; @Value("${opd.seed-enabled}") boolean enabled;
    public DevSeeder(Firestore db) { this.db = db; }

    @Override public void run(String... args) throws Exception {
        if (!enabled) return;
        String[][] deps = {{"GEN001","General Medicine","GM"},{"PED001","Pediatrics","PD"},{"DER001","Dermatology","DE"},
            {"ORT001","Orthopedics","OR"},{"ENT001","ENT","EN"},{"CAR001","Cardiology","CA"}};
        for (String[] d : deps) db.collection("departments").document(d[0]).set(new Department(d[0], d[1], d[2], 30, 3)).get();

        // id, name, department, login email
        String[][] docs = {{"DOC001","Dr. Sharma","GEN001","sharma@opd.local"},{"DOC002","Dr. Patel","GEN001","patel@opd.local"},
            {"DOC003","Dr. Mehta","PED001","mehta@opd.local"},{"DOC004","Dr. Rao","DER001","rao@opd.local"},
            {"DOC005","Dr. Deshmukh","ORT001","deshmukh@opd.local"},{"DOC006","Dr. Kulkarni","CAR001","kulkarni@opd.local"}};
        for (String[] d : docs) db.collection("doctors").document(d[0]).set(new Doctor(d[0], d[1], List.of(d[2]), DoctorStatus.OFFLINE, 0)).get();
        db.collection("systemConfig").document("queueRules").set(new QueueConfig()).get();

        seedUser("ADMIN", "Administrator", "admin@opd.local", "SEED_ADMIN_PASSWORD", null);
        seedUser("RECEPTIONIST", "Receptionist", "reception@opd.local", "SEED_RECEPTIONIST_PASSWORD", null);
        for (String[] d : docs) seedUser("DOCTOR", d[1], d[3], "SEED_DOCTOR_PASSWORD", d[0]);
        seedUser("PATIENT", "Test Patient", "patient@opd.local", "SEED_PATIENT_PASSWORD", null);
    }

    private void seedUser(String role, String name, String email, String pwdEnv, String doctorId) throws Exception {
        String pwd = System.getenv(pwdEnv);
        if (pwd == null || pwd.length() < 8) return;
        UserRecord u;
        try { u = FirebaseAuth.getInstance().getUserByEmail(email); }
        catch (FirebaseAuthException e) {
            u = FirebaseAuth.getInstance().createUser(new UserRecord.CreateRequest().setEmail(email).setPassword(pwd).setDisplayName(name));
        }
        Map<String, Object> claims = new HashMap<>(); claims.put("role", role); if (doctorId != null) claims.put("doctorId", doctorId);
        FirebaseAuth.getInstance().setCustomUserClaims(u.getUid(), claims);
        Map<String, Object> doc = new HashMap<>();
        doc.put("uid", u.getUid()); doc.put("name", name); doc.put("email", email); doc.put("role", role);
        doc.put("doctorId", doctorId); doc.put("createdAt", System.currentTimeMillis());
        db.collection("users").document(u.getUid()).set(doc).get();
    }
}