package com.tr4nce.rabbitquest.weekly;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyProgressTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation OAK_LOG = ResourceLocation.parse("minecraft:oak_log");

    @Test
    void deliveryIsSplitAcrossCallsAndNeverOverDebits() {
        WeeklyTaskDefinition task = task("rabbitquest:delivery",
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:logs"), TargetKind.DELIVER,
                        OAK_LOG, 5, null, null));
        WeeklyDefinitions definitions = definitions(task, fixed("minecraft:diamond"));
        WeeklySavedData data = new WeeklySavedData();
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.OAK_LOG, 10)));

        assertEquals(2, record(data, definitions, TargetKind.DELIVER, OAK_LOG, 2, inventory));
        assertEquals(2, progress(data, task));
        assertEquals(8, inventory.getFirst().getCount());

        assertEquals(3, record(data, definitions, TargetKind.DELIVER, OAK_LOG, 10, inventory));
        assertEquals(5, progress(data, task));
        assertEquals(5, inventory.getFirst().getCount());
        assertTrue(taskTag(data, task).getBoolean("completed"));

        assertEquals(0, record(data, definitions, TargetKind.DELIVER, OAK_LOG, 4, inventory));
        assertEquals(5, inventory.getFirst().getCount());
    }

    @Test
    void deliveryTargetsHaveIndependentCounters() {
        WeeklyTaskDefinition task = task("rabbitquest:mixed",
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:logs"), TargetKind.DELIVER,
                        OAK_LOG, 2, null, null),
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:stone"), TargetKind.HARVEST,
                        ResourceLocation.parse("minecraft:stone"), 3, null, null));
        WeeklyDefinitions definitions = definitions(task, fixed("minecraft:diamond"));
        WeeklySavedData data = new WeeklySavedData();
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.OAK_LOG, 2)));

        assertEquals(2, record(data, definitions, TargetKind.DELIVER, OAK_LOG, 2, inventory));
        assertEquals(2, progress(data, task, "rabbitquest:logs"));
        assertEquals(0, progress(data, task, "rabbitquest:stone"));
        assertFalse(taskTag(data, task).getBoolean("completed"));

        assertEquals(2, record(data, definitions, TargetKind.HARVEST, ResourceLocation.parse("minecraft:stone"),
                2, inventory));
        assertEquals(2, progress(data, task, "rabbitquest:stone"));
        assertFalse(taskTag(data, task).getBoolean("completed"));

        assertEquals(1, record(data, definitions, TargetKind.HARVEST, ResourceLocation.parse("minecraft:stone"),
                1, inventory));
        assertEquals(3, progress(data, task, "rabbitquest:stone"));
        assertTrue(taskTag(data, task).getBoolean("completed"));
    }

    @Test
    void inactiveTaskScopesAreSkipped() {
        WeeklyTaskDefinition first = task("rabbitquest:first",
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:first_target"), TargetKind.HARVEST,
                        ResourceLocation.parse("minecraft:stone"), 1, null, null));
        WeeklyTaskDefinition second = task("rabbitquest:second",
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:second_target"), TargetKind.HARVEST,
                        ResourceLocation.parse("minecraft:dirt"), 1, null, null));
        EngineeringOrder firstOrder = order("rabbitquest:first_order", 1, first.id(), fixed("minecraft:diamond"));
        EngineeringOrder secondOrder = order("rabbitquest:second_order", 2, second.id(), fixed("minecraft:emerald"));
        WeeklyDefinitions definitions = new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(first, second),
                List.of(firstOrder, secondOrder));
        WeeklySavedData data = new WeeklySavedData();

        assertEquals(0, record(data, definitions, TargetKind.HARVEST, ResourceLocation.parse("minecraft:dirt"),
                1, new ArrayList<>()));
        assertTrue(data.task(PLAYER, WEEK.toString(), second.id()).isEmpty());
        assertEquals(1, record(data, definitions, TargetKind.HARVEST, ResourceLocation.parse("minecraft:stone"),
                1, new ArrayList<>()));
        assertTrue(taskTag(data, first).getBoolean("completed"));
    }

    @Test
    void dimensionAndRegionConstraintsAreChecked() {
        WeeklyTarget.Region region = new WeeklyTarget.Region(0, 60, 0, 10, 70, 10);
        WeeklyTaskDefinition task = task("rabbitquest:region",
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:arrive"), TargetKind.ARRIVAL,
                        null, 1, OVERWORLD, region));
        WeeklyDefinitions definitions = definitions(task, fixed("minecraft:diamond"));
        WeeklySavedData data = new WeeklySavedData();

        assertEquals(0, recordAt(data, definitions, TargetKind.ARRIVAL, null, 1, OVERWORLD,
                new Vec3(20, 65, 5), new ArrayList<>()));
        assertEquals(1, recordAt(data, definitions, TargetKind.ARRIVAL, null, 1, OVERWORLD,
                new Vec3(5, 65, 5), new ArrayList<>()));
    }

    @Test
    void unfinishedOldCyclesCloseAndKeepDeliveredAmounts() {
        WeeklyTaskDefinition task = task("rabbitquest:delivery",
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:logs"), TargetKind.DELIVER,
                        OAK_LOG, 5, null, null));
        WeeklyDefinitions definitions = definitions(task, fixed("minecraft:diamond"));
        WeeklySavedData data = new WeeklySavedData();
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.OAK_LOG, 5)));

        assertEquals(2, record(data, definitions, TargetKind.DELIVER, OAK_LOG, 2, inventory));
        WeeklyEngine.reconcile(data, PLAYER, WEEK.plusWeeks(1));
        assertTrue(taskTag(data, task).getBoolean("closed"));
        assertEquals(2, progress(data, task));
        assertEquals(3, inventory.getFirst().getCount());
        assertEquals(0, record(data, definitions, TargetKind.DELIVER, OAK_LOG, 3, inventory));
        assertEquals(3, inventory.getFirst().getCount());

        WeeklyEngine.reconcile(data, PLAYER, WEEK.plusWeeks(2));
        assertEquals(2, progress(data, task));
        assertFalse(taskTag(data, task).isEmpty());
        assertFalse(taskTag(data, task).getBoolean("expired"));
    }

    private static int record(WeeklySavedData data, WeeklyDefinitions definitions, TargetKind kind,
                              ResourceLocation resource, int amount, List<ItemStack> inventory) {
        return WeeklyEngine.record(data, PLAYER, definitions, WEEK, kind, resource, amount, OVERWORLD,
                new Vec3(0, 64, 0), inventory, 1_700_000_000_000L);
    }

    private static int recordAt(WeeklySavedData data, WeeklyDefinitions definitions, TargetKind kind,
                                ResourceLocation resource, int amount, ResourceLocation dimension, Vec3 position,
                                List<ItemStack> inventory) {
        return WeeklyEngine.record(data, PLAYER, definitions, WEEK, kind, resource, amount, dimension,
                position, inventory, 1_700_000_000_000L);
    }

    private static int progress(WeeklySavedData data, WeeklyTaskDefinition task) {
        return taskTag(data, task).getCompound("progress")
                .getInt(ResourceLocation.parse("rabbitquest:logs").toString());
    }

    private static int progress(WeeklySavedData data, WeeklyTaskDefinition task, String target) {
        return taskTag(data, task).getCompound("progress").getInt(target);
    }

    private static net.minecraft.nbt.CompoundTag taskTag(WeeklySavedData data, WeeklyTaskDefinition task) {
        return data.task(PLAYER, WEEK.toString(), task.id());
    }

    private static WeeklyDefinitions definitions(WeeklyTaskDefinition task, WeeklyReward reward) {
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task),
                List.of(order("rabbitquest:order", 1, task.id(), reward)));
    }

    private static WeeklyTaskDefinition task(String id, WeeklyTarget... targets) {
        return new WeeklyTaskDefinition(ResourceLocation.parse(id), id, "", "", "",
                ResourceLocation.parse("minecraft:stone"), 1, true, List.of(targets));
    }

    private static EngineeringOrder order(String id, int order, ResourceLocation task, WeeklyReward reward) {
        return new EngineeringOrder(ResourceLocation.parse(id), id, "", "", "",
                ResourceLocation.parse("minecraft:chest"), order, true, task, reward);
    }

    private static WeeklyReward fixed(String item) {
        return new WeeklyReward(WeeklyReward.Mode.FIXED,
                List.of(new WeeklyReward.RewardItem(ResourceLocation.parse(item), 1)), List.of());
    }
}
