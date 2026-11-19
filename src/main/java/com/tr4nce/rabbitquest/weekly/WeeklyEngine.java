package com.tr4nce.rabbitquest.weekly;

import com.tr4nce.rabbitquest.shop.TradeInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure weekly model used by the server service and by tests. All methods run on the server thread;
 * callers own inventory mutation visibility flags.
 */
final class WeeklyEngine {
    private WeeklyEngine() {}

    record ClaimResult(String outcome, List<ItemStack> granted) {
        static ClaimResult failed(String outcome) {
            return new ClaimResult(outcome, List.of());
        }
    }

    record DeliverResult(String outcome, int deducted) {
        static DeliverResult failed(String outcome) {
            return new DeliverResult(outcome, 0);
        }
    }

    record AdminResult(String outcome, CompoundTag previous) {
        static AdminResult failed(String outcome) {
            return new AdminResult(outcome, new CompoundTag());
        }
    }

    private enum AdminOperation { RESET, REISSUE }

    /**
     * One task currently running for a player. Engineering orders use the order id as the persisted
     * token; ordinary tasks use their own id. Progress remains keyed by cycle and task definition.
     */
    record ActiveTask(WeeklyTaskDefinition task, ResourceLocation token, WeeklyReward reward,
                      EngineeringOrder order) {
        boolean ordinary() {
            return order == null;
        }
    }

    static String materializeOrder(WeeklyDefinitions definitions, WeeklySavedData data, LocalDate week) {
        String stored = data.selectedOrder(week.toString());
        if (stored != null) {
            return stored;
        }
        List<EngineeringOrder> enabled = definitions.enabledOrders();
        if (enabled.isEmpty()) {
            return null;
        }
        String anchor = data.anchor();
        int index;
        if (anchor == null) {
            data.ensureAnchor(week.toString());
            index = 0;
        } else {
            LocalDate start;
            try {
                start = LocalDate.parse(anchor);
            } catch (DateTimeParseException exception) {
                data.ensureAnchor(week.toString());
                start = week;
            }
            index = (int) Math.floorMod(ChronoUnit.WEEKS.between(start, week), enabled.size());
        }
        String selected = enabled.get(index).id().toString();
        data.selectOrder(week.toString(), selected);
        return selected;
    }

    static EngineeringOrder activeOrder(WeeklyDefinitions definitions, WeeklySavedData data, LocalDate week) {
        String selected = materializeOrder(definitions, data, week);
        if (selected == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(selected);
        return id == null ? null : definitions.order(id);
    }

    static WeeklyTaskDefinition activeTask(WeeklyDefinitions definitions, WeeklySavedData data, LocalDate week,
                                           EngineeringOrder order) {
        WeeklyTaskDefinition task = order == null ? null : definitions.task(order.task());
        return task == null || !task.enabled() ? null : task;
    }

    /** The rotating engineering order plus every enabled ordinary task, all progressed in parallel. */
    static List<ActiveTask> activeTasks(WeeklyDefinitions definitions, WeeklySavedData data, LocalDate week) {
        List<ActiveTask> active = new ArrayList<>();
        EngineeringOrder order = activeOrder(definitions, data, week);
        if (order != null) {
            WeeklyTaskDefinition task = definitions.task(order.task());
            if (task != null && task.enabled()) {
                active.add(new ActiveTask(task, order.id(), order.reward(), order));
            }
        }
        for (WeeklyTaskDefinition task : definitions.ordinaryTasks()) {
            boolean duplicate = false;
            for (ActiveTask entry : active) {
                if (entry.task().id().equals(task.id())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                active.add(new ActiveTask(task, task.id(), task.reward(), null));
            }
        }
        return List.copyOf(active);
    }

    /**
     * Records one individual gameplay event. Non-delivery objectives are matched independently, so
     * every qualifying target advances. DAMAGE objectives keep fractional double progress.
     */
    static int record(WeeklySavedData data, UUID player, WeeklyDefinitions definitions, LocalDate week,
                      TargetKind kind, ResourceLocation resource, double amount, ResourceLocation dimension,
                      Vec3 position, List<ItemStack> inventory, long now) {
        return record(data, player, definitions, week, kind, resource, amount, dimension, position, inventory, now, null);
    }

    static int record(WeeklySavedData data, UUID player, WeeklyDefinitions definitions, LocalDate week,
                      TargetKind kind, ResourceLocation resource, double amount, ResourceLocation dimension,
                      Vec3 position, List<ItemStack> inventory, long now, ResourceLocation biome) {
        if (!(amount > 0.0) || !Double.isFinite(amount) || kind == null
                || kind == TargetKind.DELIVER && inventory == null) {
            return 0;
        }
        int accepted = 0;
        for (ActiveTask active : activeTasks(definitions, data, week)) {
            CompoundTag taskTag = data.task(player, week.toString(), active.task().id());
            if (!usable(taskTag, week, active)) {
                continue;
            }
            CompoundTag progress = taskTag.getCompound("progress");
            List<WeeklyTarget> matches = matchingTargets(active.task(), kind, resource, dimension, position, biome, progress);
            if (matches.isEmpty()) {
                continue;
            }
            if (taskTag.isEmpty()) {
                taskTag = newTask(week, active);
                progress = taskTag.getCompound("progress");
            }

            boolean changed = false;
            if (kind == TargetKind.DELIVER) {
                // Legacy direct event path; the UI always delivers to an explicit target id.
                WeeklyTarget target = matches.getFirst();
                int remaining = remaining(target, progress);
                int added = target.takeDelivery(inventory,
                        Math.min((int) Math.floor(amount), remaining));
                if (added > 0) {
                    putProgress(progress, target, progress(target, progress) + added);
                    accepted += added;
                    changed = true;
                }
            } else {
                for (WeeklyTarget target : matches) {
                    double current = progress(target, progress);
                    double remaining = target.amount() - current;
                    if (remaining <= 0.0) {
                        continue;
                    }
                    double added;
                    if (target.fractional()) {
                        added = Math.min(amount, remaining);
                    } else {
                        int whole = (int) Math.floor(amount);
                        if (whole <= 0) {
                            continue;
                        }
                        added = Math.min(whole, remaining);
                    }
                    if (added <= 0.0) {
                        continue;
                    }
                    double next = Math.min(target.amount(), current + added);
                    putProgress(progress, target, next);
                    if (kind == TargetKind.TRAVEL) {
                        accepted += (int) Math.floor(next + 1.0E-9) - (int) Math.floor(current + 1.0E-9);
                    } else {
                        accepted += (int) Math.floor(next - current + 1.0E-9);
                    }
                    changed = true;
                }
            }

            if (changed) {
                taskTag.put("progress", progress);
                if (complete(active.task(), progress)) {
                    freezeReward(taskTag, active.reward(), player, week, active.task().id(), active.token(), now);
                }
                data.putTask(player, week.toString(), active.task().id(), taskTag);
            }
        }
        return accepted;
    }

    /** Samples movement and records at most one arrival per incomplete ARRIVAL objective. */
    static int recordArrival(WeeklySavedData data, UUID player, WeeklyDefinitions definitions, LocalDate week,
                             ResourceLocation dimension, Vec3 position, long now) {
        return record(data, player, definitions, week, TargetKind.ARRIVAL, null, 1.0, dimension, position,
                null, now);
    }

    /**
     * Authoritative delivery to an exact active objective. The request id makes retries idempotent;
     * only the target's matching item is debited, and never beyond its remaining need.
     */
    static DeliverResult deliver(WeeklySavedData data, UUID player, WeeklyDefinitions definitions, LocalDate week,
                                 String requestId, String cycleId, ResourceLocation taskId,
                                 ResourceLocation token, ResourceLocation targetId, int requested,
                                 ResourceLocation dimension, Vec3 position, List<ItemStack> inventory, long now) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return previous.equals("ok") ? new DeliverResult("ok", 0) : DeliverResult.failed(previous);
        }
        if (taskId == null || token == null || targetId == null || inventory == null || dimension == null
                || !week.toString().equals(cycleId)) {
            return DeliverResult.failed("invalid");
        }
        ActiveTask active = null;
        for (ActiveTask candidate : activeTasks(definitions, data, week)) {
            if (candidate.task().id().equals(taskId) && candidate.token().equals(token)) {
                active = candidate;
                break;
            }
        }
        if (active == null) {
            return DeliverResult.failed("invalid");
        }
        WeeklyTarget target = active.task().target(targetId);
        if (target == null || target.kind() != TargetKind.DELIVER || target.resource() == null
                || !target.matches(target.resource(), dimension, position)) {
            return DeliverResult.failed("invalid");
        }
        CompoundTag taskTag = data.task(player, week.toString(), taskId);
        if (!usable(taskTag, week, active)) {
            return DeliverResult.failed("invalid");
        }

        CompoundTag progress = taskTag.getCompound("progress");
        double current = progress(target, progress);
        int remaining = (int) Math.ceil(target.amount() - current);
        if (remaining <= 0) {
            return DeliverResult.failed("invalid");
        }
        if (requested < 0) {
            return DeliverResult.failed("invalid");
        }
        int requestedAmount = requested == 0 ? remaining : Math.min(requested, remaining);
        int removed = target.takeDelivery(inventory, requestedAmount);
        if (removed <= 0) {
            return DeliverResult.failed("missing_items");
        }

        if (taskTag.isEmpty()) {
            taskTag = newTask(week, active);
            progress = taskTag.getCompound("progress");
        }
        putProgress(progress, target, current + removed);
        taskTag.put("progress", progress);
        if (complete(active.task(), progress)) {
            freezeReward(taskTag, active.reward(), player, week, active.task().id(), active.token(), now);
        }
        data.putTask(player, week.toString(), taskId, taskTag);
        data.putReceipt(player, requestId, "ok");
        return new DeliverResult("ok", removed);
    }

    /** Removes exactly one stored task and records the prior state for inspection. */
    static AdminResult resetTask(WeeklySavedData data, UUID player, LocalDate week, ResourceLocation task,
                                 String actor) {
        return adminTask(data, player, week, task, null, AdminOperation.RESET, actor);
    }

    /**
     * Re-opens exactly one completed and claimed reward from the current or previous cycle without
     * deleting progress, the frozen reward or the selected engineering order.
     */
    static AdminResult reissueTask(WeeklySavedData data, UUID player, LocalDate week, ResourceLocation task,
                                   LocalDate current, String actor) {
        return adminTask(data, player, week, task, current, AdminOperation.REISSUE, actor);
    }

    private static AdminResult adminTask(WeeklySavedData data, UUID player, LocalDate week, ResourceLocation task,
                                         LocalDate current, AdminOperation operation, String actor) {
        CompoundTag stored = data.task(player, week.toString(), task);
        if (stored.isEmpty()) {
            return AdminResult.failed("missing_task");
        }
        if (operation == AdminOperation.RESET) {
            CompoundTag previous = stored.copy();
            data.removeTask(player, week.toString(), task);
            audit(data, actor, player, task, week, "reset", previous);
            return new AdminResult("ok", previous);
        }

        LocalDate cycle;
        try {
            cycle = LocalDate.parse(stored.getString("cycle"));
        } catch (DateTimeParseException exception) {
            return AdminResult.failed("invalid");
        }
        if (!cycle.equals(week) || !stored.getString("definition").equals(task.toString())) {
            return AdminResult.failed("invalid");
        }
        if (!stored.getBoolean("completed")) {
            return AdminResult.failed("incomplete");
        }
        if (!stored.getBoolean("claimed")) {
            return AdminResult.failed("unclaimed");
        }
        if (stored.getBoolean("expired")) {
            return AdminResult.failed("expired");
        }
        if (!cycle.equals(current) && !cycle.equals(current.minusWeeks(1))) {
            return AdminResult.failed("ineligible");
        }
        CompoundTag frozen = stored.contains("reward", Tag.TAG_COMPOUND)
                ? stored.getCompound("reward") : new CompoundTag();
        if (frozen.isEmpty()) {
            return AdminResult.failed("invalid");
        }
        WeeklyReward frozenReward;
        try {
            frozenReward = WeeklyReward.load(frozen);
        } catch (WeeklyConfigException exception) {
            return AdminResult.failed("invalid");
        }
        if (frozenReward.mode() == WeeklyReward.Mode.WEIGHTED) {
            int group = stored.getInt("rewardGroup");
            if (group < 0 || group >= frozenReward.groups().size()) {
                return AdminResult.failed("invalid");
            }
        }

        CompoundTag previous = stored.copy();
        stored.remove("claimed");
        stored.remove("claimedAt");
        stored.remove("selection");
        stored.remove("selectionSet");
        data.putTask(player, week.toString(), task, stored);
        audit(data, actor, player, task, week, "reissue", previous);
        return new AdminResult("ok", previous);
    }

    private static void audit(WeeklySavedData data, String actor, UUID player, ResourceLocation task,
                              LocalDate week, String operation, CompoundTag previous) {
        CompoundTag entry = new CompoundTag();
        entry.putLong("time", System.currentTimeMillis());
        entry.putString("actor", actor == null ? "" : actor);
        entry.putString("action", operation);
        entry.putString("player", player.toString());
        entry.putString("task", task.toString());
        entry.putString("week", week.toString());
        entry.put("previous", previous.copy());
        data.addAudit(entry);
    }

    static void reconcile(WeeklySavedData data, UUID player, LocalDate current) {
        for (CompoundTag task : data.tasks(player)) {
            LocalDate cycle;
            try {
                cycle = LocalDate.parse(task.getString("cycle"));
            } catch (DateTimeParseException exception) {
                continue;
            }
            if (!cycle.isBefore(current)) {
                continue;
            }
            ResourceLocation definition = ResourceLocation.tryParse(task.getString("definition"));
            if (definition == null) {
                continue;
            }
            long age = ChronoUnit.WEEKS.between(cycle, current);
            if (!task.getBoolean("completed")) {
                if (!task.getBoolean("closed")) {
                    task.putBoolean("closed", true);
                    data.putTask(player, cycle.toString(), definition, task);
                }
            } else if (!task.getBoolean("claimed") && !task.getBoolean("expired") && age > 1) {
                // Preserve the history and frozen reward instead of deleting another cycle.
                task.putBoolean("expired", true);
                data.putTask(player, cycle.toString(), definition, task);
            }
        }
    }

    static ClaimResult claim(WeeklySavedData data, UUID player, WeeklyDefinitions definitions, LocalDate current,
                             String requestId, String cycleId, ResourceLocation taskId, ResourceLocation orderId,
                             int selection, List<ItemStack> inventory, long now) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return previous.equals("ok") ? new ClaimResult("ok", List.of()) : ClaimResult.failed(previous);
        }

        LocalDate cycle;
        try {
            cycle = LocalDate.parse(cycleId);
        } catch (DateTimeParseException exception) {
            return ClaimResult.failed("invalid");
        }
        LocalDate previousCycle = current.minusWeeks(1);
        if (cycle.isAfter(current)) {
            return ClaimResult.failed("invalid");
        }
        if (!cycle.equals(current) && !cycle.equals(previousCycle)) {
            if (!cycle.isBefore(previousCycle)) {
                return ClaimResult.failed("invalid");
            }
            CompoundTag oldTag = taskId == null || orderId == null
                    ? new CompoundTag() : data.task(player, cycleId, taskId);
            if (oldTag.isEmpty()) {
                return ClaimResult.failed("expired");
            }
            if (!identity(oldTag, cycleId, taskId, orderId)) {
                return ClaimResult.failed("invalid");
            }
            if (!oldTag.getBoolean("claimed") && !oldTag.getBoolean("expired")) {
                oldTag.putBoolean("expired", true);
                data.putTask(player, cycleId, taskId, oldTag);
            }
            return ClaimResult.failed("expired");
        }

        CompoundTag taskTag = taskId == null || orderId == null
                ? new CompoundTag() : data.task(player, cycleId, taskId);
        if (taskTag.isEmpty()) {
            return ClaimResult.failed(cycle.equals(previousCycle) ? "expired" : "incomplete");
        }
        if (!identity(taskTag, cycleId, taskId, orderId)) {
            return ClaimResult.failed("invalid");
        }
        if (taskTag.getBoolean("expired")) {
            return ClaimResult.failed("expired");
        }
        if (!taskTag.getBoolean("completed")) {
            return ClaimResult.failed("incomplete");
        }
        if (taskTag.getBoolean("claimed")) {
            return ClaimResult.failed("claimed");
        }

        CompoundTag frozen = taskTag.getCompound("reward");
        if (frozen.isEmpty()) {
            return ClaimResult.failed("invalid");
        }
        WeeklyReward reward;
        try {
            reward = WeeklyReward.load(frozen);
        } catch (WeeklyConfigException exception) {
            return ClaimResult.failed("invalid");
        }

        int group;
        if (reward.mode() == WeeklyReward.Mode.FIXED) {
            group = 0;
        } else if (reward.mode() == WeeklyReward.Mode.WEIGHTED) {
            group = taskTag.getInt("rewardGroup");
            if (group < 0 || group >= reward.groups().size()) {
                return ClaimResult.failed("invalid");
            }
        } else {
            group = selection;
            if (group < 0 || group >= reward.groups().size()) {
                return ClaimResult.failed("selection");
            }
        }

        List<ItemStack> rewards = new ArrayList<>();
        for (WeeklyReward.RewardItem entry : reward.selectedItems(group)) {
            if (!BuiltInRegistries.ITEM.containsKey(entry.item())) {
                return ClaimResult.failed("invalid");
            }
            rewards.add(new ItemStack(BuiltInRegistries.ITEM.get(entry.item()), entry.count()));
        }

        TradeInventory.Result plan = TradeInventory.plan(inventory, List.of(), rewards, 1);
        if (!plan.success()) {
            return ClaimResult.failed(plan.failure() == TradeInventory.Failure.INVENTORY_FULL
                    ? "inventory_full" : "invalid");
        }

        for (int slot = 0; slot < plan.slots().size(); slot++) {
            inventory.set(slot, plan.slots().get(slot));
        }
        taskTag.putBoolean("claimed", true);
        taskTag.putLong("claimedAt", now);
        if (reward.mode() == WeeklyReward.Mode.CHOICE) {
            taskTag.putInt("selection", group);
            taskTag.putBoolean("selectionSet", true);
        }
        data.putTask(player, cycleId, taskId, taskTag);
        data.putReceipt(player, requestId, "ok");
        return new ClaimResult("ok", plan.granted());
    }

    private static boolean usable(CompoundTag taskTag, LocalDate week, ActiveTask active) {
        if (taskTag.isEmpty()) {
            return true;
        }
        return taskTag.getString("cycle").equals(week.toString())
                && taskTag.getString("definition").equals(active.task().id().toString())
                && taskTag.getString("order").equals(active.token().toString())
                && !taskTag.getBoolean("completed")
                && !taskTag.getBoolean("closed")
                && !taskTag.getBoolean("expired");
    }

    private static boolean identity(CompoundTag taskTag, String cycle, ResourceLocation task,
                                    ResourceLocation order) {
        return taskTag.getString("cycle").equals(cycle)
                && taskTag.getString("definition").equals(task.toString())
                && taskTag.getString("order").equals(order.toString());
    }

    private static List<WeeklyTarget> matchingTargets(WeeklyTaskDefinition task, TargetKind kind,
                                                      ResourceLocation resource, ResourceLocation dimension,
                                                      Vec3 position, ResourceLocation biome, CompoundTag progress) {
        List<WeeklyTarget> result = new ArrayList<>();
        for (WeeklyTarget target : task.targets()) {
            if (target.kind().accepts(kind) && target.matches(resource, dimension, position, biome)
                    && progress(target, progress) + 1.0E-9 < target.amount()) {
                result.add(target);
            }
        }
        return result;
    }

    private static CompoundTag newTask(LocalDate week, ActiveTask active) {
        CompoundTag tag = new CompoundTag();
        tag.putString("cycle", week.toString());
        tag.putString("definition", active.task().id().toString());
        tag.putString("order", active.token().toString());
        tag.put("progress", new CompoundTag());
        return tag;
    }

    private static boolean complete(WeeklyTaskDefinition task, CompoundTag progress) {
        for (WeeklyTarget target : task.targets()) {
            if (progress(target, progress) + 1.0E-9 < target.amount()) {
                return false;
            }
        }
        return true;
    }

    private static double progress(WeeklyTarget target, CompoundTag progress) {
        Tag tag = progress.get(target.id().toString());
        return tag instanceof NumericTag number ? number.getAsDouble() : 0.0;
    }

    private static int remaining(WeeklyTarget target, CompoundTag progress) {
        return Math.max(0, (int) Math.ceil(target.amount() - progress(target, progress)));
    }

    private static void putProgress(CompoundTag progress, WeeklyTarget target, double value) {
        double clamped = Math.min(target.amount(), Math.max(0.0, value));
        if (target.fractional()) {
            if (clamped + 1.0E-9 >= target.amount()) {
                clamped = target.amount();
            }
            progress.putDouble(target.id().toString(), clamped);
        } else {
            progress.putInt(target.id().toString(), (int) Math.floor(clamped));
        }
    }

    private static void freezeReward(CompoundTag taskTag, WeeklyReward reward, UUID player, LocalDate week,
                                     ResourceLocation task, ResourceLocation order, long now) {
        taskTag.putBoolean("completed", true);
        taskTag.putLong("completedAt", now);
        taskTag.put("reward", reward.save());
        int group = -1;
        int roll = -1;
        if (reward.mode() == WeeklyReward.Mode.WEIGHTED) {
            roll = stableRoll(player + "|" + week + "|" + task + "|" + order, reward.totalWeight());
            group = reward.groupForRoll(roll);
        }
        taskTag.putInt("rewardRoll", roll);
        taskTag.putInt("rewardGroup", group);
        taskTag.putBoolean("selectionSet", false);
    }

    private static int stableRoll(String key, int bound) {
        if (bound <= 0) {
            return 0;
        }
        long hash = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        return (int) Math.floorMod(hash, (long) bound);
    }

    static void reconcileThresholds(WeeklyDefinitions definitions, WeeklySavedData data, LocalDate currentWeek,
                                    long now) {
        List<ActiveTask> active = activeTasks(definitions, data, currentWeek);
        for (UUID player : data.playerIds()) {
            for (ActiveTask candidate : active) {
                CompoundTag taskTag = data.task(player, currentWeek.toString(), candidate.task().id());
                if (taskTag.isEmpty() || !usable(taskTag, currentWeek, candidate)) {
                    continue;
                }
                CompoundTag progress = taskTag.getCompound("progress");
                if (complete(candidate.task(), progress)) {
                    freezeReward(taskTag, candidate.reward(), player, currentWeek,
                            candidate.task().id(), candidate.token(), now);
                    data.putTask(player, currentWeek.toString(), candidate.task().id(), taskTag);
                }
            }
        }
    }

    static boolean isClaimable(CompoundTag task, LocalDate current) {
        if (task.getBoolean("expired") || !task.getBoolean("completed") || task.getBoolean("claimed")) {
            return false;
        }
        try {
            LocalDate cycle = LocalDate.parse(task.getString("cycle"));
            return cycle.equals(current) || cycle.equals(current.minusWeeks(1));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
