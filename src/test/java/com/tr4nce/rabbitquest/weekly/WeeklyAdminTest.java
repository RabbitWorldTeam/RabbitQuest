package com.tr4nce.rabbitquest.weekly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyAdminTest {
    private static final LocalDate WEEK = LocalDate.parse("2026-11-16");
    private static final UUID PLAYER = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");

    @Test
    void resetRemovesOnlyRequestedTaskAndAuditsPreviousState() {
        WeeklyReward reward = fixed("minecraft:diamond");
        WeeklyTaskDefinition first = task("rabbitquest:first", STONE, 1, reward);
        WeeklyTaskDefinition second = task("rabbitquest:second", DIRT, 1, reward);
        WeeklyDefinitions definitions = new WeeklyDefinitions(WeeklyCycle.DEFAULT,
                List.of(first, second), List.of());
        WeeklySavedData data = new WeeklySavedData();
        assertEquals(1, record(data, definitions, STONE));
        assertEquals(1, record(data, definitions, DIRT));
        data.putReceipt(PLAYER, "kept-receipt", "ok");
        data.selectOrder(WEEK.toString(), "rabbitquest:kept_order");
        CompoundTag otherWeek = new CompoundTag();
        otherWeek.putString("cycle", WEEK.plusWeeks(1).toString());
        data.putTask(PLAYER, WEEK.plusWeeks(1).toString(), first.id(), otherWeek);

        var missing = WeeklyEngine.resetTask(data, PLAYER, WEEK,
                ResourceLocation.parse("rabbitquest:missing"), "Admin");
        assertEquals("missing_task", missing.outcome());
        assertTrue(data.audit().isEmpty());

        var result = WeeklyEngine.resetTask(data, PLAYER, WEEK, first.id(), "Admin");

        assertEquals("ok", result.outcome());
        assertTrue(data.task(PLAYER, WEEK.toString(), first.id()).isEmpty());
        CompoundTag preserved = data.task(PLAYER, WEEK.toString(), second.id());
        assertFalse(preserved.isEmpty());
        assertTrue(preserved.getBoolean("completed"));
        assertEquals(1, preserved.getCompound("progress").getInt("rabbitquest:mine"));
        assertEquals("ok", data.receipt(PLAYER, "kept-receipt"));
        assertEquals("rabbitquest:kept_order", data.selectedOrder(WEEK.toString()));
        assertFalse(data.task(PLAYER, WEEK.plusWeeks(1).toString(), first.id()).isEmpty());

        assertEquals(1, data.audit().size());
        CompoundTag audit = (CompoundTag) data.audit().get(0);
        assertEquals("reset", audit.getString("action"));
        assertEquals("Admin", audit.getString("actor"));
        assertEquals(PLAYER.toString(), audit.getString("player"));
        assertEquals(first.id().toString(), audit.getString("task"));
        assertEquals(WEEK.toString(), audit.getString("week"));
        CompoundTag previous = audit.getCompound("previous");
        assertTrue(previous.getBoolean("completed"));
        assertEquals(1, previous.getCompound("progress").getInt("rabbitquest:mine"));
        assertTrue(previous.contains("reward", Tag.TAG_COMPOUND));
        assertEquals("minecraft:diamond", previous.getCompound("reward")
                .getList("items", Tag.TAG_COMPOUND).getCompound(0).getString("item"));
    }

    @Test
    void reissueRestoresExactlyOneEntitlementPreservingProgressOrderAndFrozenReward() {
        WeeklyReward reward = weighted("minecraft:diamond", "minecraft:emerald");
        WeeklyTaskDefinition task = task("rabbitquest:task", STONE, 1, null);
        EngineeringOrder order = order(task.id(), reward);
        WeeklyDefinitions definitions = definitions(task, order);
        WeeklySavedData data = complete(definitions, task);
        data.selectOrder(WEEK.toString(), order.id().toString());

        String firstReceipt = UUID.randomUUID().toString();
        var claimed = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, firstReceipt,
                WEEK.toString(), task.id(), order.id(), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 10L);
        assertEquals("ok", claimed.outcome());

        CompoundTag before = taskTag(data, task).copy();
        int group = before.getInt("rewardGroup");
        long completedAt = before.getLong("completedAt");
        int progress = before.getCompound("progress").getInt("rabbitquest:mine");

        var reissued = WeeklyEngine.reissueTask(data, PLAYER, WEEK, task.id(), WEEK, "Admin");

        assertEquals("ok", reissued.outcome());
        CompoundTag after = taskTag(data, task);
        assertTrue(after.getBoolean("completed"));
        assertFalse(after.getBoolean("claimed"));
        assertFalse(after.contains("claimedAt"));
        assertFalse(after.contains("selection"));
        assertFalse(after.contains("selectionSet"));
        assertEquals(progress, after.getCompound("progress").getInt("rabbitquest:mine"));
        assertEquals(group, after.getInt("rewardGroup"));
        assertEquals(before.getInt("rewardRoll"), after.getInt("rewardRoll"));
        assertEquals(completedAt, after.getLong("completedAt"));
        assertEquals(before.getCompound("reward").toString(), after.getCompound("reward").toString());
        assertEquals(order.id().toString(), after.getString("order"));
        assertEquals(order.id().toString(), data.selectedOrder(WEEK.toString()));
        assertEquals("ok", data.receipt(PLAYER, firstReceipt));

        assertEquals(1, data.audit().size());
        CompoundTag audit = (CompoundTag) data.audit().get(0);
        assertEquals("reissue", audit.getString("action"));
        assertEquals("Admin", audit.getString("actor"));
        assertEquals(task.id().toString(), audit.getString("task"));
        assertEquals(WEEK.toString(), audit.getString("week"));
        assertTrue(audit.getCompound("previous").getBoolean("claimed"));

        var restored = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 20L);
        assertEquals("ok", restored.outcome());
        var duplicate = WeeklyEngine.claim(data, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 30L);
        assertEquals("claimed", duplicate.outcome());

        var previousCycle = WeeklyEngine.reissueTask(data, PLAYER, WEEK, task.id(), WEEK.plusWeeks(1), "Admin");
        assertEquals("ok", previousCycle.outcome());
        assertEquals(2, data.audit().size());
    }

    @Test
    void reissueRejectsUnclaimedIncompleteExpiredAndOldCyclesWithoutMutation() {
        WeeklyReward reward = fixed("minecraft:diamond");
        WeeklyTaskDefinition task = task("rabbitquest:task", STONE, 1, null);
        EngineeringOrder order = order(task.id(), reward);
        WeeklyDefinitions definitions = definitions(task, order);

        WeeklySavedData unclaimed = complete(definitions, task);
        var unclaimedResult = WeeklyEngine.reissueTask(unclaimed, PLAYER, WEEK, task.id(), WEEK, "Admin");
        assertEquals("unclaimed", unclaimedResult.outcome());
        assertFalse(taskTag(unclaimed, task).getBoolean("claimed"));
        assertEquals(1, taskTag(unclaimed, task).getCompound("progress").getInt("rabbitquest:mine"));
        assertTrue(unclaimed.audit().isEmpty());

        WeeklyTaskDefinition longer = task("rabbitquest:longer", STONE, 2, null);
        EngineeringOrder longerOrder = order(longer.id(), reward);
        WeeklyDefinitions longerDefinitions = definitions(longer, longerOrder);
        WeeklySavedData incomplete = new WeeklySavedData();
        assertEquals(1, record(incomplete, longerDefinitions, STONE));
        var incompleteResult = WeeklyEngine.reissueTask(incomplete, PLAYER, WEEK, longer.id(), WEEK, "Admin");
        assertEquals("incomplete", incompleteResult.outcome());
        assertFalse(taskTag(incomplete, longer).getBoolean("completed"));
        assertEquals(1, taskTag(incomplete, longer).getCompound("progress").getInt("rabbitquest:mine"));
        assertTrue(incomplete.audit().isEmpty());

        WeeklySavedData expired = complete(definitions, task);
        var claimed = WeeklyEngine.claim(expired, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 10L);
        assertEquals("ok", claimed.outcome());
        CompoundTag expiredTag = taskTag(expired, task);
        expiredTag.putBoolean("expired", true);
        expired.putTask(PLAYER, WEEK.toString(), task.id(), expiredTag);
        var expiredResult = WeeklyEngine.reissueTask(expired, PLAYER, WEEK, task.id(), WEEK, "Admin");
        assertEquals("expired", expiredResult.outcome());
        assertTrue(taskTag(expired, task).getBoolean("claimed"));
        assertTrue(taskTag(expired, task).getBoolean("expired"));
        assertTrue(expired.audit().isEmpty());

        WeeklySavedData old = complete(definitions, task);
        var oldClaim = WeeklyEngine.claim(old, PLAYER, definitions, WEEK, UUID.randomUUID().toString(),
                WEEK.toString(), task.id(), order.id(), 0,
                new ArrayList<>(List.of(ItemStack.EMPTY)), 10L);
        assertEquals("ok", oldClaim.outcome());
        var oldResult = WeeklyEngine.reissueTask(old, PLAYER, WEEK, task.id(), WEEK.plusWeeks(2), "Admin");
        assertEquals("ineligible", oldResult.outcome());
        assertTrue(taskTag(old, task).getBoolean("claimed"));
        assertTrue(old.audit().isEmpty());
    }

    private static WeeklySavedData complete(WeeklyDefinitions definitions, WeeklyTaskDefinition task) {
        WeeklySavedData data = new WeeklySavedData();
        assertEquals(1, record(data, definitions, task.targets().getFirst().resource()));
        assertTrue(taskTag(data, task).getBoolean("completed"));
        return data;
    }

    private static int record(WeeklySavedData data, WeeklyDefinitions definitions, ResourceLocation resource) {
        return WeeklyEngine.record(data, PLAYER, definitions, WEEK, TargetKind.HARVEST, resource, 1,
                OVERWORLD, new Vec3(0, 64, 0), new ArrayList<>(), 1L);
    }

    private static WeeklyTaskDefinition task(String id, ResourceLocation resource, int amount, WeeklyReward reward) {
        return new WeeklyTaskDefinition(ResourceLocation.parse(id), id, "", "", "",
                ResourceLocation.parse("minecraft:stone"), 1, true,
                List.of(new WeeklyTarget(ResourceLocation.parse("rabbitquest:mine"), TargetKind.HARVEST,
                        resource, amount, null, null)), reward);
    }

    private static WeeklyDefinitions definitions(WeeklyTaskDefinition task, EngineeringOrder order) {
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task), List.of(order));
    }

    private static EngineeringOrder order(ResourceLocation task, WeeklyReward reward) {
        return new EngineeringOrder(ResourceLocation.parse("rabbitquest:order"), "Order", "", "", "",
                ResourceLocation.parse("minecraft:chest"), 1, true, task, reward);
    }

    private static WeeklyReward fixed(String item) {
        return new WeeklyReward(WeeklyReward.Mode.FIXED,
                List.of(new WeeklyReward.RewardItem(ResourceLocation.parse(item), 1)), List.of());
    }

    private static WeeklyReward weighted(String first, String second) {
        return new WeeklyReward(WeeklyReward.Mode.WEIGHTED, List.of(), List.of(
                new WeeklyReward.Group(1, List.of(new WeeklyReward.RewardItem(
                        ResourceLocation.parse(first), 1))),
                new WeeklyReward.Group(1, List.of(new WeeklyReward.RewardItem(
                        ResourceLocation.parse(second), 1)))));
    }

    private static CompoundTag taskTag(WeeklySavedData data, WeeklyTaskDefinition task) {
        return data.task(PLAYER, WEEK.toString(), task.id());
    }
}
