package com.tr4nce.rabbitquest.weekly;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyCycleTest {
    @Test
    void defaultCycleChangesAtMondayFourInHongKong() {
        assertEquals(LocalDate.parse("2026-11-09"),
                WeeklyCycle.startDate(Instant.parse("2026-11-15T19:59:59Z")));
        assertEquals(LocalDate.parse("2026-11-16"),
                WeeklyCycle.startDate(Instant.parse("2026-11-15T20:00:00Z")));
    }

    @Test
    void defaultCycleHandlesSundayBoundary() {
        assertEquals(LocalDate.parse("2026-11-16"),
                WeeklyCycle.startDate(Instant.parse("2026-11-22T19:59:59Z")));
        assertEquals(LocalDate.parse("2026-11-23"),
                WeeklyCycle.startDate(Instant.parse("2026-11-22T20:00:00Z")));
    }

    @Test
    void nonDefaultZoneDayAndTimeAreHonoured() {
        WeeklyCycle cycle = new WeeklyCycle(ZoneId.of("America/New_York"),
                DayOfWeek.SUNDAY, LocalTime.of(12, 30));
        // 2026-11-15 is a Sunday. The reset is 17:30Z (EST).
        assertEquals(LocalDate.parse("2026-11-08"),
                cycle.start(Instant.parse("2026-11-15T17:29:59Z")));
        assertEquals(LocalDate.parse("2026-11-15"),
                cycle.start(Instant.parse("2026-11-15T17:30:00Z")));
        assertEquals(LocalDate.parse("2026-11-22"),
                cycle.end(Instant.parse("2026-11-15T17:30:00Z")));
    }

    @Test
    void activeCycleCanBeConfiguredAndReset() {
        try {
            WeeklyCycle configured = new WeeklyCycle(ZoneId.of("America/New_York"),
                    DayOfWeek.SUNDAY, LocalTime.of(12, 30));
            WeeklyCycle.activate(configured);
            assertEquals(LocalDate.parse("2026-11-15"),
                    WeeklyCycle.startDate(Instant.parse("2026-11-15T17:30:00Z")));
        } finally {
            WeeklyCycle.reset();
        }
        assertEquals(WeeklyCycle.DEFAULT, WeeklyCycle.active());
    }

    @Test
    void cycleEndIsTheNextReset() {
        assertEquals(LocalDate.parse("2026-11-23"),
                WeeklyCycle.DEFAULT.end(Instant.parse("2026-11-16T00:00:00Z")));
    }
}
