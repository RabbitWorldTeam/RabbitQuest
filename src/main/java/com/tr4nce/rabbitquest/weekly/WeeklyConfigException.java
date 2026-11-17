package com.tr4nce.rabbitquest.weekly;

/** Actionable definition error. Messages name the file and field when possible. */
public final class WeeklyConfigException extends RuntimeException {
    public WeeklyConfigException(String message) {
        super(message);
    }

    public WeeklyConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
