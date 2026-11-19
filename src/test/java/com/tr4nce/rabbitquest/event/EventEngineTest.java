package com.tr4nce.rabbitquest.event;

import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyReward;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventEngineTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation LOG = ResourceLocation.fromNamespaceAndPath("minecraft", "oak_log");
    private static final ResourceLocation ZOMBIE = ResourceLocation.fromNamespaceAndPath("minecraft", "zombie");
    private static final Instant PREVIEW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Instant START = Instant.parse("2026-11-17T00:00:00Z");
    private static final Instant ACTIVE = Instant.parse("2026-11-18T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-24T00:00:00Z");
    private static final Instant CLAIM_ONLY = Instant.parse("2026-11-25T00:00:00Z");
    private static final Instant CLAIM_END = Instant.parse("2026-11-26T00:00:00Z");
    private static final Instant AFTER_END = Instant.parse("2026-11-27T00:00:00Z");

    private static TimedEventDefinition twoStepEvent(String id, boolean enrollmentRequired) {
        ResourceLocation eventId = ResourceLocation.parse(id);
        EventStep step1 = new EventStep(
                ResourceLocation.parse("rabbitquest:step_1"),
                "Step 1", "", "Deliver logs", "", "", "",
                EventStep.LockedVisibility.TITLE_AND_CONDITION,
                List.of(new WeeklyTarget(ResourceLocation.parse("rabbitquest:t1"),
                        TargetKind.DELIVER, LOG, 4, null, null)),
                new WeeklyReward(WeeklyReward.Mode.FIXED,
                        List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:emerald"), 2)),
                        List.of())
        );
        EventStep step2 = new EventStep(
                ResourceLocation.parse("rabbitquest:step_2"),
                "Step 2", "", "Slay zombies", "", "Complete step 1", "",
                EventStep.LockedVisibility.TITLE_AND_CONDITION,
                List.of(new WeeklyTarget(ResourceLocation.parse("rabbitquest:t2"),
                        TargetKind.KILL, ZOMBIE, 2, null, null)),
                new WeeklyReward(WeeklyReward.Mode.FIXED,
                        List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:iron_ingot"), 3)),
                        List.of())
        );
        return new TimedEventDefinition(
                eventId, "Festival", "", "Desc", "",
                ResourceLocation.parse("minecraft:pumpkin"), 0, true, enrollmentRequired,
                PREVIEW, START, END, CLAIM_END, List.of(step1, step2),
                new WeeklyReward(WeeklyReward.Mode.CHOICE, List.of(), List.of(
                        new WeeklyReward.Group(1, List.of(
                                new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:diamond"), 1))),
                        new WeeklyReward.Group(1, List.of(
                                new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:gold_ingot"), 4)))
                ))
        );
    }

    @Test
    void parallelStepsUseTheSameInitialUnlockStateAndClickRequiresAllBranches() {
        String json = """
                {
                  "id":"rabbitquest:test", "name":"Test", "icon":"minecraft:chest", "enabled":true,
                  "startAt":"2026-11-17T00:00:00Z", "endAt":"2026-11-24T00:00:00Z",
                  "claimEndAt":"2026-11-26T00:00:00Z",
                  "steps":[
                    {"id":"rabbitquest:intro","name":"Intro","completion":"CLICK","requires":[]},
                    {"id":"rabbitquest:a","name":"A","requires":["rabbitquest:intro"],
                     "targets":[{"id":"rabbitquest:a_kill","kind":"KILL","resource":"minecraft:zombie","amount":1}]},
                    {"id":"rabbitquest:b","name":"B","requires":["rabbitquest:intro"],
                     "targets":[{"id":"rabbitquest:b_kill","kind":"KILL","resource":"minecraft:zombie","amount":1}]},
                    {"id":"rabbitquest:c","name":"C","requires":["rabbitquest:a","rabbitquest:b"],
                     "targets":[{"id":"rabbitquest:c_kill","kind":"KILL","resource":"minecraft:zombie","amount":1}]},
                    {"id":"rabbitquest:finish","name":"Finish","completion":"CLICK","requires":["rabbitquest:c"]}
                  ],
                  "completionReward":{"mode":"FIXED","items":[{"item":"minecraft:emerald","count":1}]}
                }
                """;
        EventDefinitions definitions = EventDefinitions.parse(Map.of("test.json", json));
        ResourceLocation event = ResourceLocation.parse("rabbitquest:test");
        ResourceLocation intro = ResourceLocation.parse("rabbitquest:intro");
        ResourceLocation finish = ResourceLocation.parse("rabbitquest:finish");
        EventSavedData data = new EventSavedData();
        UUID player = UUID.randomUUID();
        assertEquals("locked", EventEngine.clickStep(data, player, definitions, ACTIVE,
                "premature", event, finish).outcome());
        assertEquals("ok", EventEngine.clickStep(data, player, definitions, ACTIVE, "intro", event, intro).outcome());
        assertEquals("ok", EventEngine.clickStep(data, player, definitions, ACTIVE, "intro", event, intro).outcome());
        assertEquals("already_completed", EventEngine.clickStep(data, player, definitions, ACTIVE,
                "different", event, intro).outcome());
        assertEquals(2, EventEngine.record(data, player, definitions, ACTIVE,
                TargetKind.KILL, ZOMBIE, 1, OVERWORLD, Vec3.ZERO, null));
        assertFalse(data.event(player, event).getCompound("steps").getCompound("rabbitquest:c")
                .getBoolean("completed"));
        assertEquals(1, EventEngine.record(data, player, definitions, ACTIVE,
                TargetKind.KILL, ZOMBIE, 1, OVERWORLD, Vec3.ZERO, null));
        assertEquals("not_active", EventEngine.clickStep(data, player, definitions, CLAIM_ONLY,
                "late", event, finish).outcome());
        assertEquals("ok", EventEngine.clickStep(data, player, definitions, ACTIVE,
                "finish", event, finish).outcome());
        assertTrue(data.event(player, event).getCompound("completion").getBoolean("completed"));

        String cyclic = json.replace("\"requires\":[]", "\"requires\":[\"rabbitquest:finish\"]");
        assertThrows(EventConfigException.class, () -> EventDefinitions.parse(Map.of("cyclic.json", cyclic)));
    }

    @Test
    void enforcesStartEnrollmentStepUnlockAndPostEndClaims() {
        EventSavedData data = new EventSavedData();
        UUID player = UUID.randomUUID();
        TimedEventDefinition event = twoStepEvent("rabbitquest:autumn_s1", true);
        EventDefinitions definitions = new EventDefinitions(List.of(event));
        NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
        inventory.set(0, new ItemStack(Items.OAK_LOG, 10));

        // Before startAt: cannot enroll or deliver
        assertEquals("not_active", EventEngine.enroll(data, player, definitions, event.id(),
                UUID.randomUUID().toString(), PREVIEW).outcome());
        assertEquals("not_active", EventEngine.deliver(data, player, definitions, PREVIEW,
                UUID.randomUUID().toString(), event.id(), event.steps().get(0).id(),
                event.steps().get(0).targets().get(0).id(), 4, OVERWORLD, Vec3.ZERO, inventory).outcome());

        // Active but not enrolled: cannot deliver or record
        assertEquals("not_enrolled", EventEngine.deliver(data, player, definitions, ACTIVE,
                UUID.randomUUID().toString(), event.id(), event.steps().get(0).id(),
                event.steps().get(0).targets().get(0).id(), 4, OVERWORLD, Vec3.ZERO, inventory).outcome());
        assertEquals(0, EventEngine.record(data, player, definitions, ACTIVE, TargetKind.KILL,
                ZOMBIE, 2, OVERWORLD, Vec3.ZERO, inventory));

        // Enroll and verify Step 2 is still locked while Step 1 is incomplete
        assertEquals("ok", EventEngine.enroll(data, player, definitions, event.id(),
                UUID.randomUUID().toString(), ACTIVE).outcome());
        assertEquals(0, EventEngine.record(data, player, definitions, ACTIVE, TargetKind.KILL,
                ZOMBIE, 5, OVERWORLD, Vec3.ZERO, inventory));
        assertFalse(EventEngine.isStepUnlocked(event, data.event(player, event.id()), 1, ACTIVE));

        // Complete Step 1 via delivery -> Step 2 unlocks
        assertEquals("ok", EventEngine.deliver(data, player, definitions, ACTIVE,
                UUID.randomUUID().toString(), event.id(), event.steps().get(0).id(),
                event.steps().get(0).targets().get(0).id(), 4, OVERWORLD, Vec3.ZERO, inventory).outcome());
        assertTrue(EventEngine.isStepUnlocked(event, data.event(player, event.id()), 1, ACTIVE));

        // Complete Step 2 during ACTIVE -> step 2 & completion become claimable
        assertEquals(2, EventEngine.record(data, player, definitions, ACTIVE, TargetKind.KILL,
                ZOMBIE, 2, OVERWORLD, Vec3.ZERO, inventory));

        // After endAt (CLAIM_ONLY): cannot record/deliver, but CAN claim step and completion rewards
        String stepClaimId = UUID.randomUUID().toString();
        assertEquals("ok", EventEngine.claimStep(data, player, definitions, CLAIM_ONLY,
                stepClaimId, event.id(), event.steps().get(0).id(), 0, inventory).outcome());
        // Idempotent retry returns ok without duplicating items
        assertEquals("ok", EventEngine.claimStep(data, player, definitions, CLAIM_ONLY,
                stepClaimId, event.id(), event.steps().get(0).id(), 0, inventory).outcome());
        // Duplicate claim with new requestId is rejected
        assertEquals("claimed", EventEngine.claimStep(data, player, definitions, CLAIM_ONLY,
                UUID.randomUUID().toString(), event.id(), event.steps().get(0).id(), 0, inventory).outcome());

        // Claim completion reward during CLAIM_ONLY (option 1 = 4 gold ingots)
        assertEquals("ok", EventEngine.claimCompletion(data, player, definitions, CLAIM_ONLY,
                UUID.randomUUID().toString(), event.id(), 1, inventory).outcome());
        assertEquals(4, inventory.stream().filter(s -> s.is(Items.GOLD_INGOT)).mapToInt(ItemStack::getCount).sum());

        // After claimEndAt (ENDED): unclaimed Step 2 reward expires
        assertEquals("expired", EventEngine.claimStep(data, player, definitions, AFTER_END,
                UUID.randomUUID().toString(), event.id(), event.steps().get(1).id(), 0, inventory).outcome());
    }

    @Test
    void supportsParallelEventsRerunIsolationAndAdminOps() {
        EventSavedData data = new EventSavedData();
        UUID player = UUID.randomUUID();
        TimedEventDefinition s1 = twoStepEvent("rabbitquest:autumn_s1", false);
        TimedEventDefinition s2 = twoStepEvent("rabbitquest:autumn_s2", false);
        EventDefinitions definitions = new EventDefinitions(List.of(s1, s2));
        NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
        inventory.set(0, new ItemStack(Items.OAK_LOG, 16));

        // Complete s1 step 1 only; s2 remains untouched
        assertEquals("ok", EventEngine.deliver(data, player, definitions, ACTIVE,
                UUID.randomUUID().toString(), s1.id(), s1.steps().get(0).id(),
                s1.steps().get(0).targets().get(0).id(), 4, OVERWORLD, Vec3.ZERO, inventory).outcome());
        assertTrue(data.event(player, s2.id()).isEmpty());

        // Admin reissue and reset
        assertEquals("ok", EventEngine.claimStep(data, player, definitions, ACTIVE,
                UUID.randomUUID().toString(), s1.id(), s1.steps().get(0).id(), 0, inventory).outcome());
        assertEquals("ok", EventEngine.reissueReward(data, player, s1,
                s1.steps().get(0).id().toString(), ACTIVE, "op").outcome());
        assertEquals("ok", EventEngine.claimStep(data, player, definitions, ACTIVE,
                UUID.randomUUID().toString(), s1.id(), s1.steps().get(0).id(), 0, inventory).outcome());
        assertEquals("ok", EventEngine.resetEvent(data, player, s1.id(), "op").outcome());
        assertTrue(data.event(player, s1.id()).isEmpty());
        assertEquals(2, data.audit().size());
    }
}
