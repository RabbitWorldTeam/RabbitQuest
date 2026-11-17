package com.tr4nce.rabbitquest.weekly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Frozen reward definition. FIXED grants every item; CHOICE lets the player pick a group;
 * WEIGHTED picks a group from a stable persisted roll.
 */
public record WeeklyReward(Mode mode, List<RewardItem> items, List<Group> groups) {
    public enum Mode { FIXED, CHOICE, WEIGHTED }

    public record RewardItem(ResourceLocation item, int count) {
        public RewardItem {
            Objects.requireNonNull(item, "item");
            if (count <= 0) {
                throw new WeeklyConfigException("Reward item " + item + " must have a positive count");
            }
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("item", item.toString());
            tag.putInt("count", count);
            return tag;
        }

        static RewardItem load(CompoundTag tag) {
            return new RewardItem(identifier(tag, "item"), positive(tag, "count"));
        }
    }

    public record Group(int weight, List<RewardItem> items) {
        public Group {
            if (weight <= 0) {
                throw new WeeklyConfigException("Reward group weight must be positive");
            }
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new WeeklyConfigException("Reward group must contain at least one item");
            }
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("weight", weight);
            ListTag list = new ListTag();
            for (RewardItem item : items) {
                list.add(item.save());
            }
            tag.put("items", list);
            return tag;
        }

        static Group load(CompoundTag tag) {
            List<RewardItem> items = new ArrayList<>();
            for (Tag item : tag.getList("items", Tag.TAG_COMPOUND)) {
                items.add(RewardItem.load((CompoundTag) item));
            }
            return new Group(tag.getInt("weight"), items);
        }
    }

    public WeeklyReward {
        Objects.requireNonNull(mode, "mode");
        items = List.copyOf(items);
        groups = List.copyOf(groups);
        if (mode == Mode.FIXED) {
            if (items.isEmpty() || !groups.isEmpty()) {
                throw new WeeklyConfigException("FIXED rewards must contain items and no groups");
            }
        } else if (!items.isEmpty() || groups.isEmpty()) {
            throw new WeeklyConfigException(mode + " rewards must contain groups and no top-level items");
        }
    }

    /** Items granted for the selected group. FIXED ignores the group index. */
    public List<RewardItem> selectedItems(int group) {
        if (mode == Mode.FIXED) {
            return items;
        }
        if (group < 0 || group >= groups.size()) {
            throw new WeeklyConfigException("Reward group out of range: " + group);
        }
        return groups.get(group).items();
    }

    public int totalWeight() {
        long total = 0;
        for (Group group : groups) {
            total += group.weight();
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** Maps a non-negative roll into a group index using the configured weights. */
    public int groupForRoll(int roll) {
        if (mode != Mode.WEIGHTED) {
            return -1;
        }
        int offset = Math.floorMod(roll, totalWeight());
        for (int index = 0; index < groups.size(); index++) {
            offset -= groups.get(index).weight();
            if (offset < 0) {
                return index;
            }
        }
        return groups.size() - 1;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", mode.name());
        ListTag itemList = new ListTag();
        for (RewardItem item : items) {
            itemList.add(item.save());
        }
        tag.put("items", itemList);
        ListTag groupList = new ListTag();
        for (Group group : groups) {
            groupList.add(group.save());
        }
        tag.put("groups", groupList);
        return tag;
    }

    public static WeeklyReward load(CompoundTag tag) {
        List<RewardItem> items = new ArrayList<>();
        for (Tag item : tag.getList("items", Tag.TAG_COMPOUND)) {
            items.add(RewardItem.load((CompoundTag) item));
        }
        List<Group> groups = new ArrayList<>();
        for (Tag group : tag.getList("groups", Tag.TAG_COMPOUND)) {
            groups.add(Group.load((CompoundTag) group));
        }
        Mode mode;
        try {
            mode = Mode.valueOf(tag.getString("mode"));
        } catch (IllegalArgumentException exception) {
            throw new WeeklyConfigException("Unknown reward mode: " + tag.getString("mode"));
        }
        return new WeeklyReward(mode, items, groups);
    }

    static ResourceLocation identifier(CompoundTag tag, String key) {
        String value = tag.getString(key);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) {
            throw new WeeklyConfigException("Invalid resource identifier in " + key + ": " + value);
        }
        return id;
    }

    static int positive(CompoundTag tag, String key) {
        int count = tag.getInt(key);
        if (count <= 0) {
            throw new WeeklyConfigException(key + " must be positive");
        }
        return count;
    }
}
