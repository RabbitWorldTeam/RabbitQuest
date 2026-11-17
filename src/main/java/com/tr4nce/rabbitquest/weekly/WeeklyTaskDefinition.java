package com.tr4nce.rabbitquest.weekly;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configured weekly task. All targets must be satisfied. A task with a direct {@code reward} is an
 * ordinary auto-open task; a task without one is a template used by an engineering order.
 */
public record WeeklyTaskDefinition(ResourceLocation id, String name, String nameKey,
                                   String description, String descriptionKey, ResourceLocation icon,
                                   int order, boolean enabled, List<WeeklyTarget> targets,
                                   WeeklyReward reward) {
    public WeeklyTaskDefinition(ResourceLocation id, String name, String nameKey,
                                String description, String descriptionKey, ResourceLocation icon,
                                int order, boolean enabled, List<WeeklyTarget> targets) {
        this(id, name, nameKey, description, descriptionKey, icon, order, enabled, targets, null);
    }

    public WeeklyTaskDefinition {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new WeeklyConfigException("Task " + id + " has no name");
        }
        Objects.requireNonNull(icon, "icon");
        targets = List.copyOf(targets);
        if (targets.isEmpty()) {
            throw new WeeklyConfigException("Task " + id + " has no targets");
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (WeeklyTarget target : targets) {
            if (!ids.add(target.id())) {
                throw new WeeklyConfigException("Task " + id + " has duplicate target " + target.id());
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
