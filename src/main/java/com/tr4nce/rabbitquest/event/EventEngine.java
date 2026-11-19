package com.tr4nce.rabbitquest.event;

import com.tr4nce.rabbitquest.shop.TradeInventory;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyConfigException;
import com.tr4nce.rabbitquest.weekly.WeeklyReward;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure timed event state machine. All active events run in parallel; within an event, steps unlock
 * sequentially and locked steps never accumulate progress. Step rewards and completion rewards are
 * frozen when achieved and claimed independently until {@code claimEndAt}.
 */
final class EventEngine {
    private EventEngine() {}

    record ActionResult(String outcome, List<ItemStack> granted) {
        static ActionResult ok() {
            return new ActionResult("ok", List.of());
        }

        static ActionResult failed(String outcome) {
            return new ActionResult(outcome, List.of());
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

    static boolean isParticipating(TimedEventDefinition event, CompoundTag eventTag, Instant now) {
        TimedEventDefinition.Phase phase = event.phase(now);
        if (phase != TimedEventDefinition.Phase.ACTIVE && phase != TimedEventDefinition.Phase.CLAIM_ONLY) {
            return false;
        }
        return !event.enrollmentRequired() || eventTag.getBoolean("enrolled");
    }

    static ActionResult enroll(EventSavedData data, UUID player, EventDefinitions definitions,
                               ResourceLocation eventId, String requestId, Instant now) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return new ActionResult(previous, List.of());
        }
        TimedEventDefinition event = eventId == null ? null : definitions.event(eventId);
        if (event == null || !event.enabled()) {
            return ActionResult.failed("invalid");
        }
        if (event.phase(now) != TimedEventDefinition.Phase.ACTIVE) {
            return ActionResult.failed("not_active");
        }
        if (!event.enrollmentRequired()) {
            return ActionResult.failed("invalid");
        }
        CompoundTag eventTag = data.event(player, eventId);
        if (eventTag.getBoolean("enrolled")) {
            return ActionResult.failed("already_enrolled");
        }
        if (eventTag.isEmpty()) {
            eventTag = newEvent(eventId);
        }
        eventTag.putBoolean("enrolled", true);
        eventTag.putLong("enrolledAt", now.toEpochMilli());
        data.putEvent(player, eventId, eventTag);
        data.putReceipt(player, requestId, "ok");
        return ActionResult.ok();
    }

    static boolean isStepUnlocked(TimedEventDefinition event, CompoundTag eventTag, int stepIndex, Instant now) {
        if (stepIndex < 0 || stepIndex >= event.steps().size() || !isParticipating(event, eventTag, now)) {
            return false;
        }
        CompoundTag steps = eventTag.getCompound("steps");
        CompoundTag step = steps.getCompound(event.steps().get(stepIndex).id().toString());
        return step.getBoolean("carriedUnlock") || event.requirements(stepIndex).stream()
                .allMatch(required -> steps.getCompound(required.toString()).getBoolean("completed"));
    }

    /** Advances the steps that were unlocked when this gameplay event began. */

    static int record(EventSavedData data, UUID player, EventDefinitions definitions, Instant now,
                      TargetKind kind, ResourceLocation resource, double amount, ResourceLocation dimension,
                      Vec3 position, List<ItemStack> inventory) {
        if (!(amount > 0.0) || !Double.isFinite(amount) || kind == null
                || kind == TargetKind.DELIVER && inventory == null) {
            return 0;
        }
        int accepted = 0;
        for (TimedEventDefinition event : definitions.enabledEvents()) {
            if (event.phase(now) != TimedEventDefinition.Phase.ACTIVE) {
                continue;
            }
            CompoundTag eventTag = data.event(player, event.id());
            if (!isParticipating(event, eventTag, now)) {
                continue;
            }
            List<EventStep> unlocked = new ArrayList<>();
            CompoundTag existingSteps = eventTag.getCompound("steps");
            for (int index = 0; index < event.steps().size(); index++) {
                EventStep step = event.steps().get(index);
                if (!step.click() && !existingSteps.getCompound(step.id().toString()).getBoolean("completed")
                        && isStepUnlocked(event, eventTag, index, now)) {
                    unlocked.add(step);
                }
            }
            for (EventStep step : unlocked) {
                CompoundTag steps = eventTag.getCompound("steps");
                CompoundTag stepTag = steps.getCompound(step.id().toString());
                CompoundTag progress = stepTag.getCompound("progress");
                List<WeeklyTarget> matches = matchingTargets(step, kind, resource, dimension, position, progress);
                if (matches.isEmpty()) {
                    continue;
                }
                if (eventTag.isEmpty()) {
                    eventTag = newEvent(event.id());
                    steps = eventTag.getCompound("steps");
                }
                if (stepTag.isEmpty()) {
                    stepTag = newStep(step.id());
                    progress = stepTag.getCompound("progress");
                }

                boolean changed = false;
                if (kind == TargetKind.DELIVER) {
                    WeeklyTarget target = matches.getFirst();
                    int remaining = remaining(target, progress);
                    int added = removeItems(inventory, target.resource(),
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
                    stepTag.put("progress", progress);
                    if (complete(step, progress)) {
                        completeStep(event, step, stepTag, eventTag, player, now);
                    }
                    steps.put(step.id().toString(), stepTag);
                    eventTag.put("steps", steps);
                    data.putEvent(player, event.id(), eventTag);
                }
            }
        }
        return accepted;
    }

    static ActionResult clickStep(EventSavedData data, UUID player, EventDefinitions definitions, Instant now,
                                  String requestId, ResourceLocation eventId, ResourceLocation stepId) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return "ok".equals(previous) ? ActionResult.ok() : ActionResult.failed(previous);
        }
        TimedEventDefinition event = eventId == null ? null : definitions.event(eventId);
        int index = event == null || stepId == null ? -1 : event.stepIndex(stepId);
        if (index < 0 || !event.enabled() || !event.steps().get(index).click()) {
            return ActionResult.failed("invalid");
        }
        CompoundTag eventTag = data.event(player, eventId);
        if (eventTag.getCompound("steps").getCompound(stepId.toString()).getBoolean("completed")) {
            return ActionResult.failed("already_completed");
        }
        if (event.phase(now) != TimedEventDefinition.Phase.ACTIVE) {
            return ActionResult.failed("not_active");
        }
        if (!isParticipating(event, eventTag, now)) {
            return ActionResult.failed("not_enrolled");
        }
        if (!isStepUnlocked(event, eventTag, index, now)) {
            return ActionResult.failed("locked");
        }
        if (eventTag.isEmpty()) {
            eventTag = newEvent(eventId);
        }
        completeStep(event, event.steps().get(index), newStep(stepId), eventTag, player, now);
        data.putEvent(player, eventId, eventTag);
        data.putReceipt(player, requestId, "ok");
        return ActionResult.ok();
    }

    static int recordArrival(EventSavedData data, UUID player, EventDefinitions definitions, Instant now,
                             ResourceLocation dimension, Vec3 position) {
        return record(data, player, definitions, now, TargetKind.ARRIVAL, null, 1.0, dimension, position, null);
    }

    static DeliverResult deliver(EventSavedData data, UUID player, EventDefinitions definitions, Instant now,
                                 String requestId, ResourceLocation eventId, ResourceLocation stepId,
                                 ResourceLocation targetId, int requested, ResourceLocation dimension,
                                 Vec3 position, List<ItemStack> inventory) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return previous.equals("ok") ? new DeliverResult("ok", 0) : DeliverResult.failed(previous);
        }
        if (eventId == null || stepId == null || targetId == null || inventory == null || dimension == null) {
            return DeliverResult.failed("invalid");
        }
        TimedEventDefinition event = definitions.event(eventId);
        if (event == null || !event.enabled()) {
            return DeliverResult.failed("invalid");
        }
        if (event.phase(now) != TimedEventDefinition.Phase.ACTIVE) {
            return DeliverResult.failed("not_active");
        }
        CompoundTag eventTag = data.event(player, eventId);
        if (!isParticipating(event, eventTag, now)) {
            return DeliverResult.failed("not_enrolled");
        }
        int stepIndex = event.stepIndex(stepId);
        if (stepIndex < 0 || !isStepUnlocked(event, eventTag, stepIndex, now)) {
            return DeliverResult.failed("locked");
        }
        EventStep step = event.steps().get(stepIndex);
        WeeklyTarget target = step.target(targetId);
        if (target == null || target.kind() != TargetKind.DELIVER || target.resource() == null
                || !target.matches(target.resource(), dimension, position)) {
            return DeliverResult.failed("invalid");
        }

        CompoundTag steps = eventTag.getCompound("steps");
        CompoundTag stepTag = steps.getCompound(stepId.toString());
        if (stepTag.getBoolean("completed")) {
            return DeliverResult.failed("invalid");
        }
        CompoundTag progress = stepTag.getCompound("progress");
        double current = progress(target, progress);
        int remaining = (int) Math.ceil(target.amount() - current);
        if (remaining <= 0 || requested < 0) {
            return DeliverResult.failed("invalid");
        }
        int requestedAmount = requested == 0 ? remaining : Math.min(requested, remaining);
        int removed = removeItems(inventory, target.resource(), requestedAmount);
        if (removed <= 0) {
            return DeliverResult.failed("missing_items");
        }

        if (eventTag.isEmpty()) {
            eventTag = newEvent(eventId);
            steps = eventTag.getCompound("steps");
        }
        if (stepTag.isEmpty()) {
            stepTag = newStep(stepId);
            progress = stepTag.getCompound("progress");
        }
        putProgress(progress, target, current + removed);
        stepTag.put("progress", progress);
        if (complete(step, progress)) {
            completeStep(event, step, stepTag, eventTag, player, now);
        }
        steps.put(stepId.toString(), stepTag);
        eventTag.put("steps", steps);
        data.putEvent(player, eventId, eventTag);
        data.putReceipt(player, requestId, "ok");
        return new DeliverResult("ok", removed);
    }

    static ActionResult claimStep(EventSavedData data, UUID player, EventDefinitions definitions, Instant now,
                                  String requestId, ResourceLocation eventId, ResourceLocation stepId,
                                  int selection, List<ItemStack> inventory) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return previous.equals("ok") ? ActionResult.ok() : ActionResult.failed(previous);
        }
        if (eventId == null || stepId == null || inventory == null) {
            return ActionResult.failed("invalid");
        }
        TimedEventDefinition event = definitions.event(eventId);
        if (event == null || !event.enabled() || event.step(stepId) == null) {
            return ActionResult.failed("invalid");
        }
        TimedEventDefinition.Phase phase = event.phase(now);
        if (phase == TimedEventDefinition.Phase.UPCOMING || phase == TimedEventDefinition.Phase.PREVIEW) {
            return ActionResult.failed("not_active");
        }
        if (phase == TimedEventDefinition.Phase.ENDED) {
            return ActionResult.failed("expired");
        }
        CompoundTag eventTag = data.event(player, eventId);
        if (eventTag.isEmpty()) {
            return ActionResult.failed("incomplete");
        }
        CompoundTag steps = eventTag.getCompound("steps");
        CompoundTag stepTag = steps.getCompound(stepId.toString());
        if (stepTag.isEmpty() || !stepTag.getBoolean("completed")) {
            return ActionResult.failed("incomplete");
        }
        if (stepTag.getBoolean("claimed")) {
            return ActionResult.failed("claimed");
        }
        ActionResult grant = grantFrozenReward(stepTag, selection, inventory, now);
        if (!grant.outcome().equals("ok")) {
            return grant;
        }
        steps.put(stepId.toString(), stepTag);
        eventTag.put("steps", steps);
        data.putEvent(player, eventId, eventTag);
        data.putReceipt(player, requestId, "ok");
        return grant;
    }

    static ActionResult claimCompletion(EventSavedData data, UUID player, EventDefinitions definitions, Instant now,
                                        String requestId, ResourceLocation eventId, int selection,
                                        List<ItemStack> inventory) {
        String previous = data.receipt(player, requestId);
        if (previous != null) {
            return previous.equals("ok") ? ActionResult.ok() : ActionResult.failed(previous);
        }
        if (eventId == null || inventory == null) {
            return ActionResult.failed("invalid");
        }
        TimedEventDefinition event = definitions.event(eventId);
        if (event == null || !event.enabled() || event.completionReward() == null) {
            return ActionResult.failed("invalid");
        }
        TimedEventDefinition.Phase phase = event.phase(now);
        if (phase == TimedEventDefinition.Phase.UPCOMING || phase == TimedEventDefinition.Phase.PREVIEW) {
            return ActionResult.failed("not_active");
        }
        if (phase == TimedEventDefinition.Phase.ENDED) {
            return ActionResult.failed("expired");
        }
        CompoundTag eventTag = data.event(player, eventId);
        CompoundTag completionTag = eventTag.getCompound("completion");
        if (completionTag.isEmpty() || !completionTag.getBoolean("completed")) {
            return ActionResult.failed("incomplete");
        }
        if (completionTag.getBoolean("claimed")) {
            return ActionResult.failed("claimed");
        }
        ActionResult grant = grantFrozenReward(completionTag, selection, inventory, now);
        if (!grant.outcome().equals("ok")) {
            return grant;
        }
        eventTag.put("completion", completionTag);
        data.putEvent(player, eventId, eventTag);
        data.putReceipt(player, requestId, "ok");
        return grant;
    }

    static AdminResult resetEvent(EventSavedData data, UUID player, ResourceLocation eventId, String actor) {
        CompoundTag stored = data.event(player, eventId);
        if (stored.isEmpty()) {
            return AdminResult.failed("missing_event");
        }
        CompoundTag previous = stored.copy();
        data.removeEvent(player, eventId);
        audit(data, actor, player, eventId, "all", "reset", previous);
        return new AdminResult("ok", previous);
    }

    static AdminResult reissueReward(EventSavedData data, UUID player, TimedEventDefinition event,
                                     String targetKey, Instant now, String actor) {
        if (event == null || event.phase(now) == TimedEventDefinition.Phase.ENDED) {
            return AdminResult.failed("expired");
        }
        CompoundTag eventTag = data.event(player, event.id());
        if (eventTag.isEmpty()) {
            return AdminResult.failed("missing_event");
        }
        boolean isCompletion = "completion".equals(targetKey);
        CompoundTag rewardHolder;
        if (isCompletion) {
            rewardHolder = eventTag.getCompound("completion");
        } else {
            ResourceLocation stepId = ResourceLocation.tryParse(targetKey);
            if (stepId == null || event.step(stepId) == null) {
                return AdminResult.failed("invalid");
            }
            rewardHolder = eventTag.getCompound("steps").getCompound(stepId.toString());
        }
        if (rewardHolder.isEmpty() || !rewardHolder.getBoolean("completed")) {
            return AdminResult.failed("incomplete");
        }
        if (!rewardHolder.getBoolean("claimed")) {
            return AdminResult.failed("unclaimed");
        }
        CompoundTag previous = eventTag.copy();
        rewardHolder.remove("claimed");
        rewardHolder.remove("claimedAt");
        rewardHolder.remove("selection");
        rewardHolder.remove("selectionSet");
        if (isCompletion) {
            eventTag.put("completion", rewardHolder);
        } else {
            CompoundTag steps = eventTag.getCompound("steps");
            steps.put(targetKey, rewardHolder);
            eventTag.put("steps", steps);
        }
        data.putEvent(player, event.id(), eventTag);
        audit(data, actor, player, event.id(), targetKey, "reissue", previous);
        return new AdminResult("ok", previous);
    }

    private static ActionResult grantFrozenReward(CompoundTag holder, int selection, List<ItemStack> inventory,
                                                  Instant now) {
        CompoundTag frozen = holder.getCompound("reward");
        if (frozen.isEmpty()) {
            return ActionResult.failed("invalid");
        }
        WeeklyReward reward;
        try {
            reward = WeeklyReward.load(frozen);
        } catch (WeeklyConfigException exception) {
            return ActionResult.failed("invalid");
        }

        int group;
        if (reward.mode() == WeeklyReward.Mode.FIXED) {
            group = 0;
        } else if (reward.mode() == WeeklyReward.Mode.WEIGHTED) {
            group = holder.getInt("rewardGroup");
            if (group < 0 || group >= reward.groups().size()) {
                return ActionResult.failed("invalid");
            }
        } else {
            group = selection;
            if (group < 0 || group >= reward.groups().size()) {
                return ActionResult.failed("selection");
            }
        }

        List<ItemStack> rewards = new ArrayList<>();
        for (WeeklyReward.RewardItem entry : reward.selectedItems(group)) {
            if (!BuiltInRegistries.ITEM.containsKey(entry.item())) {
                return ActionResult.failed("invalid");
            }
            rewards.add(new ItemStack(BuiltInRegistries.ITEM.get(entry.item()), entry.count()));
        }

        TradeInventory.Result plan = TradeInventory.plan(inventory, List.of(), rewards, 1);
        if (!plan.success()) {
            return ActionResult.failed(plan.failure() == TradeInventory.Failure.INVENTORY_FULL
                    ? "inventory_full" : "invalid");
        }

        for (int slot = 0; slot < plan.slots().size(); slot++) {
            inventory.set(slot, plan.slots().get(slot));
        }
        holder.putBoolean("claimed", true);
        holder.putLong("claimedAt", now.toEpochMilli());
        if (reward.mode() == WeeklyReward.Mode.CHOICE) {
            holder.putInt("selection", group);
            holder.putBoolean("selectionSet", true);
        }
        return new ActionResult("ok", plan.granted());
    }

    private static void completeStep(TimedEventDefinition event, EventStep step, CompoundTag stepTag,
                                     CompoundTag eventTag, UUID player, Instant now) {
        stepTag.putBoolean("completed", true);
        stepTag.putLong("completedAt", now.toEpochMilli());
        if (step.reward() != null) {
            freezeReward(stepTag, step.reward(), player + "|" + event.id() + "|step|" + step.id(), now);
        }
        CompoundTag steps = eventTag.getCompound("steps");
        steps.put(step.id().toString(), stepTag);
        boolean allDone = true;
        for (EventStep candidate : event.steps()) {
            if (!steps.getCompound(candidate.id().toString()).getBoolean("completed")) {
                allDone = false;
                break;
            }
        }
        if (allDone && event.completionReward() != null) {
            CompoundTag completion = eventTag.getCompound("completion");
            if (!completion.getBoolean("completed")) {
                completion.putBoolean("completed", true);
                completion.putLong("completedAt", now.toEpochMilli());
                freezeReward(completion, event.completionReward(),
                        player + "|" + event.id() + "|completion", now);
                eventTag.put("completion", completion);
            }
        }
    }

    private static void freezeReward(CompoundTag holder, WeeklyReward reward, String rollKey, Instant now) {
        holder.putBoolean("completed", true);
        if (!holder.contains("completedAt")) {
            holder.putLong("completedAt", now.toEpochMilli());
        }
        holder.put("reward", reward.save());
        int group = -1;
        int roll = -1;
        if (reward.mode() == WeeklyReward.Mode.WEIGHTED) {
            roll = stableRoll(rollKey, reward.totalWeight());
            group = reward.groupForRoll(roll);
        }
        holder.putInt("rewardRoll", roll);
        holder.putInt("rewardGroup", group);
        holder.putBoolean("selectionSet", false);
    }

    private static int stableRoll(String key, int bound) {
        if (bound <= 0) {
            return 0;
        }
        long hash = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        return (int) Math.floorMod(hash, (long) bound);
    }

    private static List<WeeklyTarget> matchingTargets(EventStep step, TargetKind kind, ResourceLocation resource,
                                                      ResourceLocation dimension, Vec3 position,
                                                      CompoundTag progress) {
        List<WeeklyTarget> result = new ArrayList<>();
        for (WeeklyTarget target : step.targets()) {
            if (target.kind().accepts(kind) && target.matches(resource, dimension, position)
                    && progress(target, progress) + 1.0E-9 < target.amount()) {
                result.add(target);
            }
        }
        return result;
    }

    private static CompoundTag newEvent(ResourceLocation eventId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", eventId.toString());
        tag.put("steps", new CompoundTag());
        return tag;
    }

    private static CompoundTag newStep(ResourceLocation stepId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", stepId.toString());
        tag.put("progress", new CompoundTag());
        return tag;
    }

    private static boolean complete(EventStep step, CompoundTag progress) {
        for (WeeklyTarget target : step.targets()) {
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

    private static int removeItems(List<ItemStack> inventory, ResourceLocation resource, int maximum) {
        if (resource == null || maximum <= 0 || !BuiltInRegistries.ITEM.containsKey(resource)) {
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.get(resource);
        int removed = 0;
        for (ItemStack stack : inventory) {
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(stack.getCount(), maximum - removed);
            stack.shrink(take);
            removed += take;
            if (removed == maximum) {
                break;
            }
        }
        return removed;
    }

    static void preserveUnlocks(EventDefinitions previous, EventDefinitions next, EventSavedData data, Instant now) {
        for (TimedEventDefinition event : previous.enabledEvents()) {
            TimedEventDefinition replacement = next.event(event.id());
            if (replacement == null || !replacement.enabled()) {
                continue;
            }
            for (int index = 0; index < event.steps().size(); index++) {
                EventStep step = event.steps().get(index);
                int nextIndex = replacement.stepIndex(step.id());
                if (nextIndex < 0 || event.requirements(index).equals(replacement.requirements(nextIndex))) {
                    continue;
                }
                for (UUID player : data.playerIds()) {
                    CompoundTag eventTag = data.event(player, event.id());
                    if (eventTag.isEmpty() || !isStepUnlocked(event, eventTag, index, now)
                            || isStepUnlocked(replacement, eventTag, nextIndex, now)) {
                        continue;
                    }
                    CompoundTag steps = eventTag.getCompound("steps");
                    CompoundTag stepTag = steps.getCompound(step.id().toString());
                    if (stepTag.isEmpty()) {
                        stepTag = newStep(step.id());
                    }
                    stepTag.putBoolean("carriedUnlock", true);
                    steps.put(step.id().toString(), stepTag);
                    eventTag.put("steps", steps);
                    data.putEvent(player, event.id(), eventTag);
                }
            }
        }
    }

    static void reconcileThresholds(EventDefinitions definitions, EventSavedData data, Instant now) {
        for (UUID player : data.playerIds()) {
            for (TimedEventDefinition event : definitions.enabledEvents()) {
                if (event.phase(now) != TimedEventDefinition.Phase.ACTIVE) {
                    continue;
                }
                CompoundTag eventTag = data.event(player, event.id());
                if (!isParticipating(event, eventTag, now)) {
                    continue;
                }
                CompoundTag steps = eventTag.getCompound("steps");
                boolean changed = false;
                for (int index = 0; index < event.steps().size(); index++) {
                    if (!isStepUnlocked(event, eventTag, index, now)) {
                        continue;
                    }
                    EventStep step = event.steps().get(index);
                    CompoundTag stepTag = steps.getCompound(step.id().toString());
                    if (step.click() || stepTag.isEmpty() || stepTag.getBoolean("completed")) {
                        continue;
                    }
                    if (complete(step, stepTag.getCompound("progress"))) {
                        completeStep(event, step, stepTag, eventTag, player, now);
                        steps.put(step.id().toString(), stepTag);
                        changed = true;
                    }
                }
                if (changed) {
                    eventTag.put("steps", steps);
                    data.putEvent(player, event.id(), eventTag);
                }
            }
        }
    }

    private static void audit(EventSavedData data, String actor, UUID player, ResourceLocation eventId,
                              String targetKey, String operation, CompoundTag previous) {
        CompoundTag entry = new CompoundTag();
        entry.putLong("time", System.currentTimeMillis());
        entry.putString("actor", actor == null ? "" : actor);
        entry.putString("action", operation);
        entry.putString("player", player.toString());
        entry.putString("event", eventId.toString());
        entry.putString("target", targetKey);
        entry.put("previous", previous.copy());
        data.addAudit(entry);
    }
}
