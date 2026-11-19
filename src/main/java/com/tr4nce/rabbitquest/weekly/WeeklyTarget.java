package com.tr4nce.rabbitquest.weekly;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import java.util.Objects;

/**
 * A single required weekly objective. All targets in a task must reach their amount.
 *
 * @param resource  item, entity, damage type or custom event identifier; null matches any resource
 * @param dimension optional dimension restriction
 * @param region    optional inclusive axis-aligned box
 */
public record WeeklyTarget(ResourceLocation id, TargetKind kind, ResourceLocation resource, int amount,
                           ResourceLocation dimension, Region region, ResourceLocation potion,
                           ResourceLocation biome) {
    public WeeklyTarget(ResourceLocation id, TargetKind kind, ResourceLocation resource, int amount,
                        ResourceLocation dimension, Region region) {
        this(id, kind, resource, amount, dimension, region, null, null);
    }

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
        if (biome != null && kind != TargetKind.DAMAGE) {
            throw new WeeklyConfigException("Target " + id + " only supports biome with DAMAGE");
        }
        if (potion != null && (kind != TargetKind.DELIVER || resource == null)) {
            throw new WeeklyConfigException("Target " + id + " only supports potion with item delivery");
        }
        if (kind == TargetKind.ARRIVAL && amount != 1) {
            throw new WeeklyConfigException("ARRIVAL target " + id + " must have amount 1");
        }
    }

    public void validatePotion() {
        if (potion == null) {
            return;
        }
        var item = BuiltInRegistries.ITEM.get(resource);
        if (item != Items.POTION && item != Items.SPLASH_POTION && item != Items.LINGERING_POTION
                && item != Items.TIPPED_ARROW) {
            throw new WeeklyConfigException("Target " + id + " has a potion filter on a non-potion item");
        }
        if (!BuiltInRegistries.POTION.containsKey(potion)) {
            throw new WeeklyConfigException("Target " + id + " references unknown potion " + potion);
        }
    }

    public boolean acceptsDelivery(ItemStack stack) {
        if (resource == null || stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(resource)) {
            return false;
        }
        if (potion == null) {
            return true;
        }
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.potion().filter(holder -> holder.is(potion)).isPresent();
    }

    public int takeDelivery(List<ItemStack> inventory, int maximum) {
        int removed = 0;
        for (ItemStack stack : inventory) {
            if (removed >= maximum) {
                break;
            }
            if (acceptsDelivery(stack)) {
                int take = Math.min(stack.getCount(), maximum - removed);
                stack.shrink(take);
                removed += take;
            }
        }
        return removed;
    }

    /** True when this objective stores fractional event amounts. */
    public boolean fractional() {
        return kind == TargetKind.DAMAGE || kind == TargetKind.TRAVEL;
    }

    public boolean matches(ResourceLocation incomingResource, ResourceLocation incomingDimension, Vec3 position) {
        return matches(incomingResource, incomingDimension, position, null);
    }

    public boolean matches(ResourceLocation incomingResource, ResourceLocation incomingDimension, Vec3 position,
                           ResourceLocation incomingBiome) {
        if (biome != null && !biome.equals(incomingBiome)) {
            return false;
        }
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
