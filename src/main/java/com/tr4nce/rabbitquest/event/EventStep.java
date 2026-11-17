package com.tr4nce.rabbitquest.event;

import com.tr4nce.rabbitquest.weekly.WeeklyReward;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One ordered stage inside a timed event. Locked steps do not accumulate progress.
 */
public record EventStep(ResourceLocation id, String name, String nameKey, String description,
                        String descriptionKey, String unlockCondition, String unlockConditionKey,
                        LockedVisibility lockedVisibility, List<WeeklyTarget> targets,
                        WeeklyReward reward) {
    public enum LockedVisibility {
        TITLE_AND_CONDITION,
        DETAILS,
        HIDDEN;

        public static LockedVisibility parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return TITLE_AND_CONDITION;
            }
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new EventConfigException("Unknown lockedVisibility: " + raw);
            }
        }
    }

    public EventStep {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new EventConfigException("Event step " + id + " has no name");
        }
        Objects.requireNonNull(lockedVisibility, "lockedVisibility");
        targets = List.copyOf(targets);
        if (targets.isEmpty()) {
            throw new EventConfigException("Event step " + id + " has no targets");
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (WeeklyTarget target : targets) {
            if (!ids.add(target.id())) {
                throw new EventConfigException("Event step " + id + " has duplicate target " + target.id());
            }
        }
    }

    public WeeklyTarget target(ResourceLocation targetId) {
        for (WeeklyTarget target : targets) {
            if (target.id().equals(targetId)) {
                return target;
            }
        }
        return null;
    }
}
