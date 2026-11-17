package com.tr4nce.rabbitquest.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDefinitionsTest {
    private static final String SAMPLE = """
            {
              "id": "rabbitquest:autumn_s1",
              "name": "Autumn Festival",
              "icon": "minecraft:pumpkin",
              "order": 1,
              "enabled": true,
              "enrollmentRequired": true,
              "previewAt": "2026-11-16T00:00:00+08:00",
              "startAt": "2026-11-17T00:00:00+08:00",
              "endAt": "2026-11-24T00:00:00+08:00",
              "claimEndAt": "2026-11-26T00:00:00+08:00",
              "steps": [
                {
                  "id": "rabbitquest:step_1",
                  "name": "Step 1",
                  "lockedVisibility": "TITLE_AND_CONDITION",
                  "targets": [
                    { "id": "rabbitquest:t1", "kind": "DELIVER", "resource": "minecraft:oak_log", "amount": 4 }
                  ],
                  "reward": {
                    "mode": "FIXED",
                    "items": [{ "item": "minecraft:emerald", "count": 1 }]
                  }
                },
                {
                  "id": "rabbitquest:step_2",
                  "name": "Step 2",
                  "lockedVisibility": "HIDDEN",
                  "targets": [
                    { "id": "rabbitquest:t2", "kind": "KILL", "resource": "minecraft:zombie", "amount": 2 }
                  ]
                }
              ],
              "completionReward": {
                "mode": "FIXED",
                "items": [{ "item": "minecraft:diamond", "count": 1 }]
              }
            }
            """;

    @Test
    void parsesEventAndEvaluatesPhases() {
        EventDefinitions definitions = EventDefinitions.parse(Map.of("autumn.json", SAMPLE));
        assertEquals(1, definitions.enabledEvents().size());
        TimedEventDefinition event = definitions.enabledEvents().getFirst();
        assertTrue(event.enrollmentRequired());
        assertEquals(2, event.steps().size());
        assertEquals(EventStep.LockedVisibility.HIDDEN, event.steps().get(1).lockedVisibility());
        assertNotNull(event.completionReward());

        assertEquals(TimedEventDefinition.Phase.UPCOMING,
                event.phase(Instant.parse("2026-11-15T15:59:59Z")));
        assertEquals(TimedEventDefinition.Phase.PREVIEW,
                event.phase(Instant.parse("2026-11-15T16:00:00Z")));
        assertEquals(TimedEventDefinition.Phase.ACTIVE,
                event.phase(Instant.parse("2026-11-16T16:00:00Z")));
        assertEquals(TimedEventDefinition.Phase.CLAIM_ONLY,
                event.phase(Instant.parse("2026-11-23T16:00:00Z")));
        assertEquals(TimedEventDefinition.Phase.ENDED,
                event.phase(Instant.parse("2026-11-25T16:00:00Z")));
        assertFalse(event.visibleAt(Instant.parse("2026-11-25T16:00:00Z")));
    }

    @Test
    void rejectsDuplicateEventIdsAndInvalidWindows() {
        assertThrows(EventConfigException.class, () -> EventDefinitions.parse(Map.of(
                "a.json", SAMPLE,
                "b.json", SAMPLE
        )));

        String badWindow = SAMPLE.replace("2026-11-24T00:00:00+08:00", "2026-11-16T00:00:00+08:00");
        assertThrows(EventConfigException.class, () -> EventDefinitions.parse(Map.of("bad.json", badWindow)));
    }
}
