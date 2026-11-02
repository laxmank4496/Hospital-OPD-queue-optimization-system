package com.hospital.opd.queue;

import com.hospital.opd.model.QueueStatus;

public class InvalidTransitionException extends QueueException {
    public InvalidTransitionException(QueueStatus from, QueueStatus to) {
        super("INVALID_TRANSITION", "Cannot change status from " + from + " to " + to + ".");
    }
}
