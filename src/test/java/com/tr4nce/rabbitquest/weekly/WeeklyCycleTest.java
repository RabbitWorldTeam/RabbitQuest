package com.tr4nce.rabbitquest.weekly;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyCycleTest {
    @Test
    void defaultCycleChangesAtMondayFourInHongKong() {
        assertEquals(LocalDate.parse("2026-11-09"),
                WeeklyCycle.startDate(Instant.parse("2026-11-15T19:59:59Z")));
        assertEquals(LocalDate.parse("2026-11-16"),
                WeeklyCycle.startDate(Instant.parse("2026-11-15T20:00:00Z")));
    }
}
