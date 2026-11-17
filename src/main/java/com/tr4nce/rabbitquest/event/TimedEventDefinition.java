package com.tr4nce.rabbitquest.event;

import com.tr4nce.rabbitquest.weekly.WeeklyReward;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Independent timed event session. Reruns must use a distinct session {@code id} so player
 * progress and claims remain isolated.
 */
public record TimedEventDefinition(ResourceLocation id, String name, String nameKey,
                                   String description, String descriptionKey, ResourceLocation icon,
                                   int order, boolean enabled, boolean enrollmentRequired,
                                   Instant previewAt, Instant startAt, Instant endAt, Instant claimEndAt,
                                   List<EventStep> steps, WeeklyReward completionReward) {
    public enum Phase {
        UPCOMING,
        PREVIEW,
        ACTIVE,
        CLAIM_ONLY,
        ENDED
    }

    public TimedEventDefinition {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new EventConfigException("Event " + id + " has no name");
        }
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(claimEndAt, "claimEndAt");
        if (!endAt.isAfter(startAt)) {
            throw new EventConfigException("Event " + id + " endAt must be after startAt");
        }
        if (claimEndAt.isBefore(endAt)) {
            throw new EventConfigException("Event " + id + " claimEndAt must not be before endAt");
        }
        if (previewAt != null && previewAt.isAfter(startAt)) {
            throw new EventConfigException("Event " + id + " previewAt must not be after startAt");
        }
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new EventConfigException("Event " + id + " must have at least one step");
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (EventStep step : steps) {
            if (!ids.add(step.id())) {
                throw new EventConfigException("Event " + id + " has duplicate step " + step.id());
            }
        }
    }

    public Phase phase(Instant now) {
        if (now.isBefore(startAt)) {
            if (previewAt != null && !now.isBefore(previewAt)) {
                return Phase.PREVIEW;
            }
            return Phase.UPCOMING;
        }
        if (now.isBefore(endAt)) {
            return Phase.ACTIVE;
        }
        if (now.isBefore(claimEndAt)) {
            return Phase.CLAIM_ONLY;
        }
        return Phase.ENDED;
    }

    public boolean visibleAt(Instant now) {
        Phase phase = phase(now);
        return phase == Phase.PREVIEW || phase == Phase.ACTIVE || phase == Phase.CLAIM_ONLY;
    }

    public int stepIndex(ResourceLocation stepId) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).id().equals(stepId)) {
                return index;
            }
        }
        return -1;
    }

    public EventStep step(ResourceLocation stepId) {
        int index = stepIndex(stepId);
        return index < 0 ? null : steps.get(index);
    }
}
