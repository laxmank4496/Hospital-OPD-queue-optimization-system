package com.hospital.opd.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.hospital.opd.queue.*;
import com.hospital.opd.service.ConfigService;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Configuration
public class AppConfig {

    @Value("${opd.firebase.credentials-path:}") private String credPath;
    @Value("${opd.timezone}") private String tz;

    @Bean FirebaseApp firebaseApp() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) return FirebaseApp.getInstance();
        GoogleCredentials creds = credPath.isBlank() ? GoogleCredentials.getApplicationDefault()
            : GoogleCredentials.fromStream(new FileInputStream(credPath));
        return FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(creds).build());
    }
    @Bean Firestore firestore(FirebaseApp app) { return FirestoreClient.getFirestore(app); }
    @Bean Clock clock() { return Clock.system(ZoneId.of(tz)); }
    @Bean QueueStore queueStore(Firestore db) { return new FirestoreQueueStore(db); }

    /** Events are published AFTER the transaction commits, to WebSocket subscribers. */
    @Bean QueueEngine.EventSink eventSink(SimpMessagingTemplate ws) {
        return events -> events.forEach(e -> {
            if (e.doctorId != null) ws.convertAndSend("/topic/doctor/" + e.doctorId, e);
            if (e.departmentId != null) ws.convertAndSend("/topic/department/" + e.departmentId, e);
            if (e.queueEntryId != null) ws.convertAndSend("/topic/entry/" + e.queueEntryId, e);
        });
    }
    @Bean QueueEngine queueEngine(QueueStore store, ConfigService cfg, Clock clock, QueueEngine.EventSink sink) {
        return new QueueEngine(store, cfg::current, clock, sink);
    }
}
