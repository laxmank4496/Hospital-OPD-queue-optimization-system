package com.hospital.opd.service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.hospital.opd.model.QueueConfig;
import org.springframework.stereotype.Service;

/** Admin-editable rules in systemConfig/queueRules, cached for 15 s so hot paths don't read Firestore every call. */
@Service
public class ConfigService {
    private final Firestore db;
    private volatile QueueConfig cached = new QueueConfig();
    private volatile long loadedAt = 0;
    public ConfigService(Firestore db) { this.db = db; }

    public QueueConfig current() {
        if (System.currentTimeMillis() - loadedAt > 15_000) {
            try {
                DocumentSnapshot s = db.collection("systemConfig").document("queueRules").get().get();
                cached = s.exists() ? s.toObject(QueueConfig.class) : new QueueConfig();
            } catch (Exception ignored) { /* keep last known good config */ }
            loadedAt = System.currentTimeMillis();
        }
        return cached;
    }
    public QueueConfig save(QueueConfig c) {
        try { db.collection("systemConfig").document("queueRules").set(c).get(); }
        catch (Exception e) { throw new IllegalStateException(e); }
        cached = c; loadedAt = System.currentTimeMillis(); return c;
    }
}
