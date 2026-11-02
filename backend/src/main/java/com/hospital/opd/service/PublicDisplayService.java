package com.hospital.opd.service;

import com.hospital.opd.model.QueueStatus;
import com.hospital.opd.queue.QueueEngine;
import java.util.*;
import org.springframework.stereotype.Service;

/** Token-only data for the lobby TV. Deliberately has no access to patient names. */
@Service
public class PublicDisplayService {
    public record Display(String departmentId, List<String> nowServing, List<String> next, int waiting) {}
    private final QueueEngine engine;
    public PublicDisplayService(QueueEngine e) { engine = e; }

    public Display forDepartment(String departmentId) {
        List<String> serving = new ArrayList<>(), next = new ArrayList<>(); int waiting = 0;
        for (var load : engine.doctorLoads(departmentId)) {
            for (var v : engine.doctorQueue(load.doctorId())) {
                if (v.status() == QueueStatus.IN_CONSULTATION || v.status() == QueueStatus.CALLED) serving.add(v.token());
                else { waiting++; if (v.status() == QueueStatus.CHECKED_IN && next.size() < 3) next.add(v.token()); }
            }
        }
        return new Display(departmentId, serving, next, waiting);
    }
}
