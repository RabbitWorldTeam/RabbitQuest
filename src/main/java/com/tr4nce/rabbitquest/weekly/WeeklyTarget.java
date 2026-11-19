package com.tr4nce.rabbitquest.weekly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * A single required weekly objective. All targets in a task must reach their amount.
 *
 * @param resource  item, entity, damage type or custom event identifier; null matches any resource
 * @param dimension optional dimension restriction
 * @param region    optional inclusive axis-aligned box
 */
public record WeeklyTarget(ResourceLocation id, TargetKind kind, ResourceLocation resource, int amount,
                           ResourceLocation dimension, Region region) {
    public WeeklyTarget {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if (amount <= 0) {
            throw new WeeklyConfigException("Target " + id + " must have a positive amount");
        }
        if (kind == TargetKind.OPEN_RABBIT_CHEST && (resource == null || amount != 1)) {
            throw new WeeklyConfigException("OPEN_RABBIT_CHEST target " + id + " requires a chest ID and amount 1");
        }
        if (kind == TargetKind.TRAVEL && resource != null) {
            throw new WeeklyConfigException("TRAVEL target " + id + " does not accept a resource");
        }
        if (kind == TargetKind.ARRIVAL && amount != 1) {
            throw new WeeklyConfigException("ARRIVAL target " + id + " must have amount 1");
        }
    }

    /** True when this objective stores fractional event amounts. */
    public boolean fractional() {
        return kind == TargetKind.DAMAGE || kind == TargetKind.TRAVEL;
    }

    public boolean matches(ResourceLocation incomingResource, ResourceLocation incomingDimension, Vec3 position) {
        if (resource != null && !resource.equals(incomingResource)) {
            return false;
        }
        if (dimension != null && !dimension.equals(incomingDimension)) {
            return false;
        }
        return region == null || position != null && region.contains(position);
    }

    /** Inclusive box bounds; {@code min} components must not exceed {@code max}. */
    public record Region(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Region {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new WeeklyConfigException("Region minimum must not exceed maximum");
            }
        }

        public boolean contains(Vec3 position) {
            return position.x >= minX && position.x <= maxX
                    && position.y >= minY && position.y <= maxY
                    && position.z >= minZ && position.z <= maxZ;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("min", vector(minX, minY, minZ));
            tag.put("max", vector(maxX, maxY, maxZ));
            return tag;
        }

        private static ListTag vector(double x, double y, double z) {
            ListTag tag = new ListTag();
            tag.add(net.minecraft.nbt.DoubleTag.valueOf(x));
            tag.add(net.minecraft.nbt.DoubleTag.valueOf(y));
            tag.add(net.minecraft.nbt.DoubleTag.valueOf(z));
            return tag;
        }
    }
}
