package com.tr4nce.rabbitquest.weekly;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public final class WeeklyCycle {
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private WeeklyCycle() {}

    public static LocalDate startDate(Instant time) {
        var local = time.atZone(HONG_KONG);
        LocalDate monday = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return local.toLocalDate().equals(monday) && local.toLocalTime().isBefore(LocalTime.of(4, 0))
                ? monday.minusWeeks(1) : monday;
    }
}
