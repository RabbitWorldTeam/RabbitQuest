package com.tr4nce.rabbitquest.weekly;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

class WeeklyClaimTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");

    @Test
    void fixedRewardIsFrozenAtCompletion() {
        WeeklyTaskDefinition task = task();
        EngineeringOrder original = order("rabbitquest:order", task.id(), fixed("minecraft:diamond"));
        WeeklyDefinitions definitions = definitions(task, original);
        WeeklySavedData data = complete(definitions, task);
        assertEquals("FIXED", taskTag(data, task).getCompound("reward").getString("mode"));

        EngineeringOrder edited = order("rabbitquest:order", task.id(), fixed("minecraft:gold_ingot"));
        WeeklyDefinitions changed = definitions(task, edited);
        List<ItemStack> inventory = new ArrayList<>(List.of(ItemStack.EMPTY));
        var result = WeeklyEngine.claim(data, PLAYER, changed, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), original.id(), 0, inventory, 1L);
        assertEquals("ok", result.outcome());
        assertEquals(Items.DIAMOND, result.granted().getFirst().getItem());
        assertTrue(taskTag(data, task).getBoolean("claimed"));
    }

    @Test
    void weightedRollSurvivesInventoryFullRetryAndRestart() {
        WeeklyTaskDefinition task = task();
        WeeklyReward reward = new WeeklyReward(WeeklyReward.Mode.WEIGHTED, List.of(), List.of(
                new WeeklyReward.Group(1, List.of(new WeeklyReward.RewardItem(
                        ResourceLocation.parse("minecraft:diamond"), 1))),
                new WeeklyReward.Group(1, List.of(new WeeklyReward.RewardItem(
                        ResourceLocation.parse("minecraft:emerald"), 1)))));
        EngineeringOrder order = order("rabbitquest:order", task.id(), reward);
        WeeklyDefinitions definitions = definitions(task, order);
        WeeklySavedData data = complete(definitions, task);
        int group = taskTag(data, task).getInt("rewardGroup");
        assertTrue(group == 0 || group == 1);

        List<ItemStack> full = new ArrayList<>(List.of(new ItemStack(Items.STONE)));
        var rejected = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0, full, 1L);
        assertEquals("inventory_full", rejected.outcome());
        assertFalse(taskTag(data, task).getBoolean("claimed"));
        assertEquals(group, taskTag(data, task).getInt("rewardGroup"));

        WeeklySavedData reloaded = WeeklySavedData.load(data.save(new CompoundTag(), registries()), registries());
        List<ItemStack> inventory = new ArrayList<>(List.of(ItemStack.EMPTY));
        var result = WeeklyEngine.claim(reloaded, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0, inventory, 2L);
        assertEquals("ok", result.outcome());
        assertEquals(group, taskTag(reloaded, task).getInt("rewardGroup"));
        assertEquals(group == 0 ? Items.DIAMOND : Items.EMERALD, result.granted().getFirst().getItem());

        var duplicate = WeeklyEngine.claim(reloaded, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0, new ArrayList<>(List.of(ItemStack.EMPTY)), 3L);
        assertEquals("claimed", duplicate.outcome());
    }

    @Test
    void choiceMustBeValidatedBeforeMutation() {
        WeeklyTaskDefinition task = task();
        WeeklyReward reward = new WeeklyReward(WeeklyReward.Mode.CHOICE, List.of(), List.of(
                new WeeklyReward.Group(1, List.of(new WeeklyReward.RewardItem(
                        ResourceLocation.parse("minecraft:diamond"), 1))),
                new WeeklyReward.Group(1, List.of(new WeeklyReward.RewardItem(
                        ResourceLocation.parse("minecraft:emerald"), 1)))));
        EngineeringOrder order = order("rabbitquest:order", task.id(), reward);
        WeeklyDefinitions definitions = definitions(task, order);
        WeeklySavedData data = complete(definitions, task);
        List<ItemStack> inventory = new ArrayList<>(List.of(ItemStack.EMPTY));

        var invalid = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 9, inventory, 1L);
        assertEquals("selection", invalid.outcome());
        assertTrue(inventory.getFirst().isEmpty());
        assertFalse(taskTag(data, task).getBoolean("claimed"));

        var result = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 1, inventory, 2L);
        assertEquals("ok", result.outcome());
        assertEquals(Items.EMERALD, result.granted().getFirst().getItem());
    }

    @Test
    void completedUnclaimedSurvivesFollowingWeekThenExpires() {
        WeeklyTaskDefinition task = task();
        WeeklyDefinitions definitions = definitions(task, order("rabbitquest:order", task.id(),
                fixed("minecraft:diamond")));
        WeeklySavedData data = complete(definitions, task);

        var tooLate = WeeklyEngine.claim(data, PLAYER, definitions, WEEK.plusWeeks(2),
                UUID.randomUUID().toString(), WEEK.toString(), task.id(),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 1L);
        assertEquals("expired", tooLate.outcome());

        WeeklySavedData nextWeek = complete(definitions, task);
        var followingWeek = WeeklyEngine.claim(nextWeek, PLAYER, definitions, WEEK.plusWeeks(1),
                UUID.randomUUID().toString(), WEEK.toString(), task.id(),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 2L);
        assertEquals("ok", followingWeek.outcome());
    }

    @Test
    void expiredClaimPreservesProgressFrozenRewardAndHistory() {
        WeeklyTaskDefinition task = task();
        WeeklyDefinitions definitions = definitions(task, order("rabbitquest:order", task.id(),
                fixed("minecraft:diamond")));
        WeeklySavedData data = complete(definitions, task);

        var expired = WeeklyEngine.claim(data, PLAYER, definitions, WEEK.plusWeeks(2),
                UUID.randomUUID().toString(), WEEK.toString(), task.id(),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 1L);
        assertEquals("expired", expired.outcome());

        CompoundTag preserved = taskTag(data, task);
        assertFalse(preserved.isEmpty());
        assertTrue(preserved.getBoolean("expired"));
        assertTrue(preserved.getBoolean("completed"));
        assertTrue(preserved.contains("reward", Tag.TAG_COMPOUND));
        assertEquals(1, preserved.getCompound("progress")
                .getInt(ResourceLocation.parse("rabbitquest:mine").toString()));

        var retry = WeeklyEngine.claim(data, PLAYER, definitions, WEEK.plusWeeks(2),
                UUID.randomUUID().toString(), WEEK.toString(), task.id(),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 2L);
        assertEquals("expired", retry.outcome());
    }

    @Test
    void arbitraryOldIdentifiersDoNotMutateTheStoredCycle() {
        WeeklyTaskDefinition task = task();
        WeeklyDefinitions definitions = definitions(task, order("rabbitquest:order", task.id(),
                fixed("minecraft:diamond")));
        WeeklySavedData data = complete(definitions, task);

        var mismatch = WeeklyEngine.claim(data, PLAYER, definitions, WEEK.plusWeeks(2),
                UUID.randomUUID().toString(), WEEK.toString(), ResourceLocation.parse("rabbitquest:other"),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 1L);
        assertEquals("expired", mismatch.outcome());

        CompoundTag preserved = taskTag(data, task);
        assertFalse(preserved.isEmpty());
        assertFalse(preserved.getBoolean("expired"));
        assertFalse(preserved.getBoolean("claimed"));
        assertEquals(1, preserved.getCompound("progress")
                .getInt(ResourceLocation.parse("rabbitquest:mine").toString()));

        var notACycle = WeeklyEngine.claim(data, PLAYER, definitions, WEEK,
                UUID.randomUUID().toString(), WEEK.minusDays(3).toString(), task.id(),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 2L);
        assertEquals("invalid", notACycle.outcome());
        assertFalse(taskTag(data, task).getBoolean("expired"));
        assertFalse(taskTag(data, task).getBoolean("claimed"));
    }

    @Test
    void previousWeekCompletedRewardSurvivesReconcile() {
        WeeklyTaskDefinition task = task();
        WeeklyDefinitions definitions = definitions(task, order("rabbitquest:order", task.id(),
                fixed("minecraft:diamond")));
        WeeklySavedData data = complete(definitions, task);

        WeeklyEngine.reconcile(data, PLAYER, WEEK.plusWeeks(1));
        CompoundTag previous = taskTag(data, task);
        assertFalse(previous.isEmpty());
        assertFalse(previous.getBoolean("expired"));
        assertTrue(WeeklyEngine.isClaimable(previous, WEEK.plusWeeks(1)));

        var claimed = WeeklyEngine.claim(data, PLAYER, definitions, WEEK.plusWeeks(1),
                UUID.randomUUID().toString(), WEEK.toString(), task.id(),
                ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 2L);
        assertEquals("ok", claimed.outcome());
    }

    @Test
    void incompleteClaimsAreRejectedAndProgressSurvives() {
        WeeklyTaskDefinition task = task();
        WeeklyDefinitions definitions = definitions(task, order("rabbitquest:order", task.id(),
                fixed("minecraft:diamond")));
        WeeklySavedData data = new WeeklySavedData();
        assertEquals(0, WeeklyEngine.record(data, PLAYER, definitions, WEEK, TargetKind.HARVEST, STONE, 0,
                OVERWORLD, new Vec3(0, 64, 0), new ArrayList<>(), 1L));

        var result = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), ResourceLocation.parse("rabbitquest:order"), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 1L);
        assertEquals("incomplete", result.outcome());
        assertTrue(taskTag(data, task).isEmpty() || !taskTag(data, task).getBoolean("claimed"));
    }

    private static WeeklySavedData complete(WeeklyDefinitions definitions, WeeklyTaskDefinition task) {
        WeeklySavedData data = new WeeklySavedData();
        assertEquals(1, WeeklyEngine.record(data, PLAYER, definitions, WEEK, TargetKind.HARVEST, STONE, 1,
                OVERWORLD, new Vec3(0, 64, 0), new ArrayList<>(), 1L));
        assertTrue(taskTag(data, task).getBoolean("completed"));
        return data;
    }

    private static WeeklyTaskDefinition task() {
        return new WeeklyTaskDefinition(ResourceLocation.parse("rabbitquest:task"), "Task", "", "", "",
                ResourceLocation.parse("minecraft:stone"), 1, true,
                List.of(new WeeklyTarget(ResourceLocation.parse("rabbitquest:mine"), TargetKind.HARVEST,
                        STONE, 1, null, null)));
    }

    private static WeeklyDefinitions definitions(WeeklyTaskDefinition task, EngineeringOrder order) {
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task), List.of(order));
    }

    private static EngineeringOrder order(String id, ResourceLocation task, WeeklyReward reward) {
        return new EngineeringOrder(ResourceLocation.parse(id), id, "", "", "",
                ResourceLocation.parse("minecraft:chest"), 1, true, task, reward);
    }

    private static WeeklyReward fixed(String item) {
        return new WeeklyReward(WeeklyReward.Mode.FIXED,
                List.of(new WeeklyReward.RewardItem(ResourceLocation.parse(item), 1)), List.of());
    }

    private static CompoundTag taskTag(WeeklySavedData data, WeeklyTaskDefinition task) {
        return data.task(PLAYER, WEEK.toString(), task.id());
    }

    private static HolderLookup.Provider registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
