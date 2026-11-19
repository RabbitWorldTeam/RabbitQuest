package com.tr4nce.rabbitquest.weekly;

/** Event categories understood by the weekly service. */
public enum TargetKind {
    DELIVER,
    HARVEST,
    MATURE_HARVEST,
    CRAFT,
    FISH,
    TRAVEL,
    KILL,
    DAMAGE,
    ARRIVAL,
    CUSTOM;

    public boolean accepts(TargetKind action) {
        return this == action || this == HARVEST && action == MATURE_HARVEST;
    }

    public static TargetKind parse(String value) {
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new WeeklyConfigException("Unknown target kind: " + value);
        }
    }
}
