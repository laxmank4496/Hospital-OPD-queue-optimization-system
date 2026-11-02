package com.hospital.opd.service;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Thin client for the Python service. Never throws: on any failure callers fall back to the baseline estimate. */
@Component
public class MlClient {
    public record Prediction(double minutes, String source, String modelVersion) {}
    private final RestClient http;

    private final boolean enabled;

    public MlClient(@Value("${opd.ml-service-url}") String url, @Value("${opd.ml-enabled:false}") boolean enabled) {
        this.enabled = enabled;
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000); f.setReadTimeout(1500);      // the queue must never wait on ML
        this.http = RestClient.builder().baseUrl(url).requestFactory(f).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Prediction> predictBatch(Map<String, Map<String, Object>> requestsByDoctor) {
        if (!enabled || requestsByDoctor.isEmpty()) return Map.of();   // ML is optional: baseline estimate is used
        try {
            List<String> ids = new ArrayList<>(requestsByDoctor.keySet());
            Map<String, Object> body = Map.of("items", ids.stream().map(requestsByDoctor::get).toList());
            Map<String, Object> resp = http.post().uri("/predict/batch").body(body).retrieve().body(Map.class);
            List<Map<String, Object>> preds = (List<Map<String, Object>>) resp.get("predictions");
            Map<String, Prediction> out = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> p = preds.get(i);
                out.put(ids.get(i), new Prediction(((Number) p.get("predictedWaitingTimeMinutes")).doubleValue(),
                    "ml".equals(p.get("usedModel")) ? "ML" : "BASELINE", String.valueOf(p.get("modelVersion"))));
            }
            return out;
        } catch (Exception e) { return Map.of(); }
    }
}
