package com.tr4nce.rabbitquest.weekly;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One configured engineering order. Exactly one enabled order rotates per week. */
public record EngineeringOrder(ResourceLocation id, String name, String nameKey,
                               String description, String descriptionKey, ResourceLocation icon,
                               int order, boolean enabled, ResourceLocation task, WeeklyReward reward) {
    public EngineeringOrder {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new WeeklyConfigException("Order " + id + " has no name");
        }
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(reward, "reward");
    }
}
