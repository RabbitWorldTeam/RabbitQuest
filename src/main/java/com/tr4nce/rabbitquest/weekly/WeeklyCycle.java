package com.tr4nce.rabbitquest.weekly;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * Immutable weekly reset calendar. The static {@link #startDate(Instant)} entry point keeps the
 * phase-one shop limit behaviour (Asia/Hong_Kong, Monday 04:00) while the active instance can be
 * replaced once per server from the weekly cycle definition.
 */
public record WeeklyCycle(ZoneId zone, DayOfWeek day, LocalTime time) {
    public static final WeeklyCycle DEFAULT =
            new WeeklyCycle(ZoneId.of("Asia/Hong_Kong"), DayOfWeek.MONDAY, LocalTime.of(4, 0));

    private static volatile WeeklyCycle active = DEFAULT;

    public WeeklyCycle {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(time, "time");
    }

    public static WeeklyCycle active() {
        return active;
    }

    /** Installs the server-wide cycle used by weekly tasks and phase-one shop limits. */
    public static void activate(WeeklyCycle cycle) {
        active = Objects.requireNonNull(cycle, "cycle");
    }

    /** Restores the documented default. Used when a world unloads and by phase-one tests. */
    public static void reset() {
        active = DEFAULT;
    }

    public static LocalDate startDate(Instant time) {
        return active.start(time);
    }

    public static LocalDate endDate(Instant time) {
        return active.end(time);
    }

    /** Start date of the cycle containing {@code time}. */
    public LocalDate start(Instant time) {
        ZonedDateTime local = time.atZone(zone);
        LocalDate anchor = local.toLocalDate().with(TemporalAdjusters.previousOrSame(day));
        return local.toLocalDateTime().isBefore(anchor.atTime(this.time)) ? anchor.minusWeeks(1) : anchor;
    }

    /** Exclusive end date, i.e. the start of the following cycle. */
    public LocalDate end(Instant time) {
        return start(time).plusWeeks(1);
    }

    public String cycleId(Instant time) {
        return start(time).toString();
    }
}
