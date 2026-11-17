package com.tr4nce.rabbitquest.weekly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyEventTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final UUID PLAYER = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static final ResourceLocation ZOMBIE = ResourceLocation.parse("minecraft:zombie");
    private static final ResourceLocation SKELETON = ResourceLocation.parse("minecraft:skeleton");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");

    @Test
    void fractionalDamageAccumulatesAndEachQualifyingObjectiveMatchesIndependently() {
        WeeklyTaskDefinition task = task("rabbitquest:fractional", null,
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:zombie_main"), TargetKind.DAMAGE,
                        ZOMBIE, 2, OVERWORLD, null),
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:zombie_second"), TargetKind.DAMAGE,
                        ZOMBIE, 1, OVERWORLD, null),
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:skeleton"), TargetKind.DAMAGE,
                        SKELETON, 1, OVERWORLD, null),
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:zombie_nether"), TargetKind.DAMAGE,
                        ZOMBIE, 1, NETHER, null));
        WeeklyDefinitions definitions = definitions(task, null);

        WeeklySavedData data = new WeeklySavedData();
        for (int hit = 0; hit < 4; hit++) {
            record(data, definitions, TargetKind.DAMAGE, ZOMBIE, 0.25, OVERWORLD);
        }

        assertEquals(1.0, progress(data, task, "rabbitquest:zombie_main"), 1.0E-9);
        assertEquals(1.0, progress(data, task, "rabbitquest:zombie_second"), 1.0E-9);
        assertEquals(0.0, progress(data, task, "rabbitquest:skeleton"), 1.0E-9);
        assertEquals(0.0, progress(data, task, "rabbitquest:zombie_nether"), 1.0E-9);
        assertFalse(taskTag(data, task).getBoolean("completed"));

        record(data, definitions, TargetKind.DAMAGE, ZOMBIE, 2.0, OVERWORLD);
        assertEquals(2.0, progress(data, task, "rabbitquest:zombie_main"), 1.0E-9);
        assertEquals(1.0, progress(data, task, "rabbitquest:zombie_second"), 1.0E-9);

        for (int hit = 0; hit < 10; hit++) {
            record(data, definitions, TargetKind.DAMAGE, SKELETON, 0.1, OVERWORLD);
        }
        assertEquals(1.0, progress(data, task, "rabbitquest:skeleton"), 1.0E-9);
        assertFalse(taskTag(data, task).getBoolean("completed"));

        record(data, definitions, TargetKind.DAMAGE, ZOMBIE, 1.0, NETHER);
        assertTrue(taskTag(data, task).getBoolean("completed"));
    }

    @Test
    void arrivalCountsOnlyOnceAndOnlyForTheMatchingDimensionAndRegion() {
        WeeklyTarget.Region region = new WeeklyTarget.Region(0, 60, 0, 10, 70, 10);
        WeeklyTaskDefinition task = task("rabbitquest:arrival", null,
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:scout"), TargetKind.ARRIVAL,
                        null, 1, OVERWORLD, region));
        WeeklyDefinitions definitions = definitions(task, null);
        WeeklySavedData data = new WeeklySavedData();

        assertEquals(0, WeeklyEngine.recordArrival(data, PLAYER, definitions, WEEK, OVERWORLD,
                new Vec3(20, 65, 20), 1L));
        assertEquals(1, WeeklyEngine.recordArrival(data, PLAYER, definitions, WEEK, OVERWORLD,
                new Vec3(5, 65, 5), 2L));
        assertEquals(0, WeeklyEngine.recordArrival(data, PLAYER, definitions, WEEK, OVERWORLD,
                new Vec3(5, 65, 5), 3L));
        assertEquals(0, WeeklyEngine.recordArrival(data, PLAYER, definitions, WEEK, NETHER,
                new Vec3(5, 65, 5), 4L));

        assertEquals(1.0, progress(data, task, "rabbitquest:scout"), 1.0E-9);
        assertTrue(taskTag(data, task).getBoolean("completed"));
    }

    @Test
    void enabledOrdinaryTasksAndRotatingOrderRunInParallelWithOwnTokens() {
        WeeklyReward ordinaryReward = fixed("minecraft:emerald");
        WeeklyTaskDefinition ordinary = task("rabbitquest:ordinary", ordinaryReward,
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:mine_stone"), TargetKind.HARVEST,
                        STONE, 2, null, null));
        WeeklyTaskDefinition engineering = task("rabbitquest:engineering", null,
                new WeeklyTarget(ResourceLocation.parse("rabbitquest:mine_dirt"), TargetKind.HARVEST,
                        DIRT, 1, null, null));
        EngineeringOrder order = new EngineeringOrder(ResourceLocation.parse("rabbitquest:order"),
                "Order", "", "", "", ResourceLocation.parse("minecraft:chest"), 1, true,
                engineering.id(), fixed("minecraft:diamond"));
        WeeklyDefinitions definitions = new WeeklyDefinitions(WeeklyCycle.DEFAULT,
                List.of(ordinary, engineering), List.of(order));

        List<WeeklyEngine.ActiveTask> active = WeeklyEngine.activeTasks(definitions, new WeeklySavedData(), WEEK);
        assertEquals(2, active.size());

        WeeklySavedData data = new WeeklySavedData();
        assertEquals(1, record(data, definitions, TargetKind.HARVEST, STONE, 1.0, OVERWORLD));
        assertEquals(1, progress(data, ordinary, "rabbitquest:mine_stone"));
        assertTrue(taskTag(data, engineering).isEmpty());

        assertEquals(1, record(data, definitions, TargetKind.HARVEST, DIRT, 1.0, OVERWORLD));
        assertTrue(taskTag(data, engineering).getBoolean("completed"));
        assertEquals(order.id().toString(), taskTag(data, engineering).getString("order"));
        assertEquals("FIXED", taskTag(data, engineering).getCompound("reward").getString("mode"));
        assertEquals("minecraft:diamond", taskTag(data, engineering).getCompound("reward")
                .getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getString("item"));
        assertFalse(taskTag(data, ordinary).getBoolean("completed"));

        assertEquals(1, record(data, definitions, TargetKind.HARVEST, STONE, 1.0, OVERWORLD));
        assertTrue(taskTag(data, ordinary).getBoolean("completed"));
        assertEquals(ordinary.id().toString(), taskTag(data, ordinary).getString("order"));
        assertEquals("minecraft:emerald", taskTag(data, ordinary).getCompound("reward")
                .getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getString("item"));
    }

    private static int record(WeeklySavedData data, WeeklyDefinitions definitions, TargetKind kind,
                              ResourceLocation resource, double amount, ResourceLocation dimension) {
        return WeeklyEngine.record(data, PLAYER, definitions, WEEK, kind, resource, amount, dimension,
                new Vec3(0, 64, 0), null, 1L);
    }

    private static double progress(WeeklySavedData data, WeeklyTaskDefinition task, String target) {
        return taskTag(data, task).getCompound("progress").getDouble(target);
    }

    private static CompoundTag taskTag(WeeklySavedData data, WeeklyTaskDefinition task) {
        return data.task(PLAYER, WEEK.toString(), task.id());
    }

    private static WeeklyDefinitions definitions(WeeklyTaskDefinition task, WeeklyReward taskReward) {
        EngineeringOrder order = new EngineeringOrder(ResourceLocation.parse("rabbitquest:order"),
                "Order", "", "", "", ResourceLocation.parse("minecraft:chest"), 1, true, task.id(),
                fixed("minecraft:diamond"));
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task), List.of(order));
    }

    private static WeeklyTaskDefinition task(String id, WeeklyReward reward, WeeklyTarget... targets) {
        return new WeeklyTaskDefinition(ResourceLocation.parse(id), id, "", "", "",
                ResourceLocation.parse("minecraft:stone"), 1, true, List.of(targets), reward);
    }

    private static WeeklyReward fixed(String item) {
        return new WeeklyReward(WeeklyReward.Mode.FIXED,
                List.of(new WeeklyReward.RewardItem(ResourceLocation.parse(item), 1)), List.of());
    }
}
