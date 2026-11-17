package com.tr4nce.rabbitquest.weekly;

/** Event categories understood by the weekly service. */
public enum TargetKind {
    DELIVER,
    HARVEST,
    KILL,
    DAMAGE,
    ARRIVAL,
    CUSTOM;

    public static TargetKind parse(String value) {
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new WeeklyConfigException("Unknown target kind: " + value);
        }
    }
}
