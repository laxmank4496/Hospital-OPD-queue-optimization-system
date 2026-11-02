package com.hospital.opd.service;

import com.hospital.opd.queue.QueueEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Schedulers {
    private final QueueEngine engine;
    public Schedulers(QueueEngine e) { engine = e; }
    /** Called patients who did not show within the grace period become NO_SHOW even if nobody clicks anything. */
    @Scheduled(fixedDelay = 30_000) public void expireNoShows() { engine.expireNoShows(); }
}
