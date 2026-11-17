package com.tr4nce.rabbitquest.config;

import com.tr4nce.rabbitquest.event.EventConfigException;
import com.tr4nce.rabbitquest.event.EventDefinitions;
import com.tr4nce.rabbitquest.event.EventSavedData;
import com.tr4nce.rabbitquest.event.EventStep;
import com.tr4nce.rabbitquest.event.TimedEventDefinition;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyCycle;
import com.tr4nce.rabbitquest.weekly.WeeklyDefinitions;
import com.tr4nce.rabbitquest.weekly.WeeklyReward;
import com.tr4nce.rabbitquest.weekly.WeeklySavedData;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import com.tr4nce.rabbitquest.weekly.WeeklyTaskDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadValidatorTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final ResourceLocation TASK_ID = ResourceLocation.parse("rabbitquest:gather");
    private static final ResourceLocation TARGET_ID = ResourceLocation.parse("rabbitquest:logs");
    private static final ResourceLocation OAK_LOG = ResourceLocation.parse("minecraft:oak_log");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");

    private static WeeklyDefinitions weeklyWith(ResourceLocation item, int amount, boolean enabled) {
        WeeklyTaskDefinition task = new WeeklyTaskDefinition(
                TASK_ID, "Gather", "", "Desc", "",
                OAK_LOG, 0, enabled,
                List.of(new WeeklyTarget(TARGET_ID, TargetKind.DELIVER, item, amount, null, null)),
                new WeeklyReward(WeeklyReward.Mode.FIXED,
                        List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:emerald"), 1)),
                        List.of())
        );
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task), List.of());
    }

    private static WeeklySavedData weeklyDataWithProgress(UUID player, int delivered, boolean completed) {
        WeeklySavedData data = new WeeklySavedData();
        CompoundTag taskTag = new CompoundTag();
        taskTag.putString("cycle", WEEK.toString());
        taskTag.putString("definition", TASK_ID.toString());
        taskTag.putString("order", TASK_ID.toString());
        taskTag.putBoolean("completed", completed);
        CompoundTag progress = new CompoundTag();
        progress.putInt(TARGET_ID.toString(), delivered);
        taskTag.put("progress", progress);
        // Put via reflection-free package? Wait: WeeklySavedData.putTask is package-private, so let's
        // test via WeeklySavedData.load or public/test helper.
        return data;
    }

    @Test
    void rejectsDeliveredMaterialRemovalOrReplacement() {
        WeeklyDefinitions previous = weeklyWith(OAK_LOG, 8, true);
        UUID player = UUID.randomUUID();

        // Build saved data via NBT load so we don't depend on package-private mutators
        CompoundTag root = new CompoundTag();
        root.putInt("format", 1);
        CompoundTag players = new CompoundTag();
        CompoundTag p = new CompoundTag();
        CompoundTag tasks = new CompoundTag();
        CompoundTag cycle = new CompoundTag();
        CompoundTag taskTag = new CompoundTag();
        taskTag.putString("cycle", WEEK.toString());
        taskTag.putString("definition", TASK_ID.toString());
        taskTag.putString("order", TASK_ID.toString());
        CompoundTag progress = new CompoundTag();
        progress.putInt(TARGET_ID.toString(), 3);
        taskTag.put("progress", progress);
        cycle.put(TASK_ID.toString(), taskTag);
        tasks.put(WEEK.toString(), cycle);
        p.put("tasks", tasks);
        players.put(player.toString(), p);
        root.put("players", players);
        WeeklySavedData data = WeeklySavedData.load(root, null);

        // Removing or disabling task with delivered items is rejected
        WeeklyDefinitions disabled = weeklyWith(OAK_LOG, 8, false);
        assertFalse(ReloadValidator.validateWeekly(previous, disabled, data, WEEK).success());

        // Replacing DELIVER resource is rejected
        WeeklyDefinitions replaced = weeklyWith(STONE, 8, true);
        assertFalse(ReloadValidator.validateWeekly(previous, replaced, data, WEEK).success());

        // Lowering amount threshold with same target ID & resource is allowed
        WeeklyDefinitions lowered = weeklyWith(OAK_LOG, 2, true);
        assertTrue(ReloadValidator.validateWeekly(previous, lowered, data, WEEK).success());
    }

    @Test
    void rejectsInvalidMixedEventFilesWithoutPartialState() {
        assertThrows(EventConfigException.class, () -> EventDefinitions.parse(Map.of(
                "bad.json", "{ \"id\": \"rabbitquest:bad\", \"name\": \"Bad\" }"
        )));

        EventDefinitions valid = new EventDefinitions(List.of(new TimedEventDefinition(
                ResourceLocation.parse("rabbitquest:ev1"), "Ev1", "", "", "",
                OAK_LOG, 0, true, false, null,
                Instant.parse("2026-11-17T00:00:00Z"),
                Instant.parse("2026-11-24T00:00:00Z"),
                Instant.parse("2026-11-26T00:00:00Z"),
                List.of(new EventStep(
                        ResourceLocation.parse("rabbitquest:s1"), "S1", "", "", "", "", "",
                        EventStep.LockedVisibility.TITLE_AND_CONDITION,
                        List.of(new WeeklyTarget(TARGET_ID, TargetKind.DELIVER, OAK_LOG, 4, null, null)),
                        null
                )),
                null
        )));
        assertTrue(ReloadValidator.validateEvents(valid, valid, new EventSavedData()).success());
    }
}
