package com.tr4nce.rabbitquest.config;

import com.tr4nce.rabbitquest.event.EventDefinitions;
import com.tr4nce.rabbitquest.event.EventSavedData;
import com.tr4nce.rabbitquest.event.EventStep;
import com.tr4nce.rabbitquest.event.TimedEventDefinition;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyDefinitions;
import com.tr4nce.rabbitquest.weekly.WeeklySavedData;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import com.tr4nce.rabbitquest.weekly.WeeklyTaskDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Validates candidate weekly and event definitions against active definitions and persisted player
 * state before an atomic reload is published. Incomplete DELIVER objectives with delivered items
 * cannot be removed, disabled or have their item resource replaced.
 */
public final class ReloadValidator {
    private ReloadValidator() {}

    public static ReloadResult validateWeekly(WeeklyDefinitions previous, WeeklyDefinitions candidate,
                                              WeeklySavedData data, LocalDate currentWeek) {
        if (candidate == null || data == null) {
            return ReloadResult.failed(0, "weekly", "definitions", "missing definitions or data");
        }
        for (UUID playerId : data.playerIds()) {
            for (CompoundTag taskTag : data.tasks(playerId)) {
                if (!taskTag.getString("cycle").equals(currentWeek.toString())) {
                    continue;
                }
                if (taskTag.getBoolean("completed") || taskTag.getBoolean("closed") || taskTag.getBoolean("expired")) {
                    continue;
                }
                CompoundTag progress = taskTag.getCompound("progress");
                if (progress.isEmpty()) {
                    continue;
                }
                ResourceLocation taskId = ResourceLocation.tryParse(taskTag.getString("definition"));
                if (taskId == null) {
                    continue;
                }
                WeeklyTaskDefinition prevTask = previous == null ? null : previous.task(taskId);
                WeeklyTaskDefinition nextTask = candidate.task(taskId);
                for (String targetKey : progress.getAllKeys()) {
                    if (number(progress.get(targetKey)) <= 0.0) {
                        continue;
                    }
                    ResourceLocation targetId = ResourceLocation.tryParse(targetKey);
                    WeeklyTarget prevTarget = prevTask == null || targetId == null ? null : prevTask.target(targetId);
                    if (prevTarget != null && prevTarget.kind() != TargetKind.DELIVER) {
                        continue;
                    }
                    if (nextTask == null || !nextTask.enabled()) {
                        return ReloadResult.failed(0, taskId.toString(), targetKey,
                                "cannot remove or disable weekly task with delivered items");
                    }
                    WeeklyTarget nextTarget = targetId == null ? null : nextTask.target(targetId);
                    if (nextTarget == null) {
                        return ReloadResult.failed(0, taskId.toString(), targetKey,
                                "cannot remove DELIVER target with delivered items");
                    }
                    if (nextTarget.kind() != TargetKind.DELIVER) {
                        return ReloadResult.failed(0, taskId.toString(), targetKey,
                                "cannot change DELIVER target kind with delivered items");
                    }
                    if (prevTarget != null && !Objects.equals(prevTarget.resource(), nextTarget.resource())) {
                        return ReloadResult.failed(0, taskId.toString(), targetKey,
                                "cannot replace DELIVER target resource with delivered items");
                    }
                }
            }
        }
        return ReloadResult.ok(0);
    }

    public static ReloadResult validateEvents(EventDefinitions previous, EventDefinitions candidate,
                                              EventSavedData data) {
        if (candidate == null || data == null) {
            return ReloadResult.failed(0, "event", "definitions", "missing definitions or data");
        }
        for (UUID playerId : data.playerIds()) {
            CompoundTag events = data.playerEvents(playerId);
            for (String eventKey : events.getAllKeys()) {
                CompoundTag eventTag = events.getCompound(eventKey);
                CompoundTag steps = eventTag.getCompound("steps");
                if (steps.isEmpty()) {
                    continue;
                }
                ResourceLocation eventId = ResourceLocation.tryParse(eventKey);
                if (eventId == null) {
                    continue;
                }
                TimedEventDefinition prevEvent = previous == null ? null : previous.event(eventId);
                TimedEventDefinition nextEvent = candidate.event(eventId);
                for (String stepKey : steps.getAllKeys()) {
                    CompoundTag stepTag = steps.getCompound(stepKey);
                    if (stepTag.getBoolean("completed")) {
                        continue;
                    }
                    CompoundTag progress = stepTag.getCompound("progress");
                    if (progress.isEmpty()) {
                        continue;
                    }
                    ResourceLocation stepId = ResourceLocation.tryParse(stepKey);
                    EventStep prevStep = prevEvent == null || stepId == null ? null : prevEvent.step(stepId);
                    EventStep nextStep = nextEvent == null || stepId == null ? null : nextEvent.step(stepId);
                    for (String targetKey : progress.getAllKeys()) {
                        if (number(progress.get(targetKey)) <= 0.0) {
                            continue;
                        }
                        ResourceLocation targetId = ResourceLocation.tryParse(targetKey);
                        WeeklyTarget prevTarget = prevStep == null || targetId == null
                                ? null : prevStep.target(targetId);
                        if (prevTarget != null && prevTarget.kind() != TargetKind.DELIVER) {
                            continue;
                        }
                        if (nextEvent == null || !nextEvent.enabled() || nextStep == null) {
                            return ReloadResult.failed(0, eventKey, stepKey + "/" + targetKey,
                                    "cannot remove or disable event step with delivered items");
                        }
                        WeeklyTarget nextTarget = targetId == null ? null : nextStep.target(targetId);
                        if (nextTarget == null) {
                            return ReloadResult.failed(0, eventKey, targetKey,
                                    "cannot remove DELIVER target with delivered items");
                        }
                        if (nextTarget.kind() != TargetKind.DELIVER) {
                            return ReloadResult.failed(0, eventKey, targetKey,
                                    "cannot change DELIVER target kind with delivered items");
                        }
                        if (prevTarget != null && !Objects.equals(prevTarget.resource(), nextTarget.resource())) {
                            return ReloadResult.failed(0, eventKey, targetKey,
                                    "cannot replace DELIVER target resource with delivered items");
                        }
                    }
                }
            }
        }
        return ReloadResult.ok(0);
    }

    private static double number(Tag tag) {
        return tag instanceof NumericTag numeric ? numeric.getAsDouble() : 0.0;
    }
}
