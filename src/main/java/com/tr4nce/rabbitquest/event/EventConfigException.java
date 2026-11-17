package com.tr4nce.rabbitquest.event;

/** Thrown when timed event JSON definitions or reward payloads fail validation. */
public final class EventConfigException extends RuntimeException {
    public EventConfigException(String message) {
        super(message);
    }

    public EventConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
