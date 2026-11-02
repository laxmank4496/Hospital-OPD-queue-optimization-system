package com.hospital.opd.queue;

/** Business-rule violation with a stable machine code (mobile clients switch on `code`) and a user-safe message. */
public class QueueException extends RuntimeException {
    private final String code;
    public QueueException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
