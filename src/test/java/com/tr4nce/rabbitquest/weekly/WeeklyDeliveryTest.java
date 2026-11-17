package com.tr4nce.rabbitquest.weekly;

import net.minecraft.nbt.CompoundTag;
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

class WeeklyDeliveryTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation OAK_LOG = ResourceLocation.parse("minecraft:oak_log");

    @Test
    void deliveryToSecondSameItemTargetKeepsRowsDistinctAndNeverOverDebits() {
        WeeklyTaskDefinition task = task(
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:first_logs"), TargetKind.DELIVER,
                        OAK_LOG, 3, null, null),
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:second_logs"), TargetKind.DELIVER,
                        OAK_LOG, 4, null, null));
        WeeklyDefinitions definitions = definitions(task);
        WeeklySavedData data = new WeeklySavedData();
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.OAK_LOG, 10)));
        String request = UUID.randomUUID().toString();

        WeeklyEngine.DeliverResult second = WeeklyEngine.deliver(data, PLAYER, definitions, WEEK, request,
                WEEK.toString(), task.id(), ORDER, ResourceLocation.parse("rabbitquest:second_logs"), 0,
                OVERWORLD, new Vec3(0, 64, 0), inventory, 1L);
        assertEquals("ok", second.outcome());
        assertEquals(4, second.deducted());
        assertEquals(4, progress(data, task, "rabbitquest:second_logs"));
        assertEquals(0, progress(data, task, "rabbitquest:first_logs"));
        assertEquals(6, inventory.getFirst().getCount());

        WeeklyEngine.DeliverResult replay = WeeklyEngine.deliver(data, PLAYER, definitions, WEEK, request,
                WEEK.toString(), task.id(), ORDER, ResourceLocation.parse("rabbitquest:second_logs"), 9,
                OVERWORLD, new Vec3(0, 64, 0), inventory, 2L);
        assertEquals("ok", replay.outcome());
        assertEquals(0, replay.deducted());
        assertEquals(4, progress(data, task, "rabbitquest:second_logs"));
        assertEquals(6, inventory.getFirst().getCount());

        WeeklyEngine.DeliverResult first = WeeklyEngine.deliver(data, PLAYER, definitions, WEEK,
                UUID.randomUUID().toString(), WEEK.toString(), task.id(), ORDER,
                ResourceLocation.parse("rabbitquest:first_logs"), 10, OVERWORLD, new Vec3(0, 64, 0),
                inventory, 3L);
        assertEquals("ok", first.outcome());
        assertEquals(3, first.deducted());
        assertEquals(3, progress(data, task, "rabbitquest:first_logs"));
        assertEquals(3, inventory.getFirst().getCount());
        assertTrue(taskTag(data, task).getBoolean("completed"));

        WeeklyEngine.DeliverResult again = WeeklyEngine.deliver(data, PLAYER, definitions, WEEK,
                UUID.randomUUID().toString(), WEEK.toString(), task.id(), ORDER,
                ResourceLocation.parse("rabbitquest:first_logs"), 1, OVERWORLD, new Vec3(0, 64, 0),
                inventory, 4L);
        assertFalse(again.outcome().equals("ok"));
        assertEquals(3, inventory.getFirst().getCount());
    }

    @Test
    void deliveryRejectsWrongCycleTokenRegionAndForeignItems() {
        WeeklyTarget.Region region = new WeeklyTarget.Region(0, 60, 0, 10, 70, 10);
        WeeklyTaskDefinition task = task(new WeeklyTarget(ResourceLocation.parse("rabbitquest:logs"),
                TargetKind.DELIVER, OAK_LOG, 2, OVERWORLD, region));
        WeeklyDefinitions definitions = definitions(task);
        WeeklySavedData data = new WeeklySavedData();
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.OAK_LOG, 5),
                new ItemStack(Items.STONE, 5)));

        assertEquals("invalid", deliver(data, definitions, task, WEEK.plusWeeks(1).toString(), ORDER,
                "rabbitquest:logs", 1, OVERWORLD, new Vec3(5, 65, 5), inventory).outcome());
        assertEquals("invalid", deliver(data, definitions, task, WEEK.toString(),
                ResourceLocation.parse("rabbitquest:wrong_order"), "rabbitquest:logs", 1, OVERWORLD,
                new Vec3(5, 65, 5), inventory).outcome());
        assertEquals("invalid", deliver(data, definitions, task, WEEK.toString(), ORDER,
                "rabbitquest:logs", 1, OVERWORLD, new Vec3(20, 65, 5), inventory).outcome());
        assertEquals("missing_items", deliver(data, definitions, task, WEEK.toString(), ORDER,
                "rabbitquest:logs", 1, OVERWORLD, new Vec3(5, 65, 5), new ArrayList<>(
                        List.of(new ItemStack(Items.STONE, 5)))).outcome());
        assertEquals(5, inventory.get(0).getCount());
        assertEquals(5, inventory.get(1).getCount());
        assertTrue(taskTag(data, task).isEmpty());
    }

    private static WeeklyEngine.DeliverResult deliver(WeeklySavedData data, WeeklyDefinitions definitions,
                                                      WeeklyTaskDefinition task, String cycle,
                                                      ResourceLocation token, String target, int amount,
                                                      ResourceLocation dimension, Vec3 position,
                                                      List<ItemStack> inventory) {
        return WeeklyEngine.deliver(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(), cycle,
                task.id(), token, ResourceLocation.parse(target), amount, dimension, position, inventory, 1L);
    }

    private static final ResourceLocation ORDER = ResourceLocation.parse("rabbitquest:order");

    private static int progress(WeeklySavedData data, WeeklyTaskDefinition task, String target) {
        return taskTag(data, task).getCompound("progress").getInt(target);
    }

    private static CompoundTag taskTag(WeeklySavedData data, WeeklyTaskDefinition task) {
        return data.task(PLAYER, WEEK.toString(), task.id());
    }

    private static WeeklyDefinitions definitions(WeeklyTaskDefinition task) {
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task),
                List.of(order(task.id(), fixed("minecraft:diamond"))));
    }

    private static WeeklyTaskDefinition task(WeeklyTarget... targets) {
        return new WeeklyTaskDefinition(ResourceLocation.parse("rabbitquest:delivery"), "Delivery", "", "", "",
                ResourceLocation.parse("minecraft:chest"), 1, true, List.of(targets));
    }

    private static EngineeringOrder order(ResourceLocation task, WeeklyReward reward) {
        return new EngineeringOrder(ORDER, "Order", "", "", "", ResourceLocation.parse("minecraft:chest"),
                1, true, task, reward);
    }

    private static WeeklyReward fixed(String item) {
        return new WeeklyReward(WeeklyReward.Mode.FIXED,
                List.of(new WeeklyReward.RewardItem(ResourceLocation.parse(item), 1)), List.of());
    }
}
