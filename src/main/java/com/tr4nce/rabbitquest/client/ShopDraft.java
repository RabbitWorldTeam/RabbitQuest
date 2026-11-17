package com.tr4nce.rabbitquest.client;

import java.time.Instant;
import java.util.IdentityHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Client-side edit state, kept apart from the latest server snapshot. */
final class ShopDraft {
    enum Section { GENERAL, COSTS, REWARDS, LIMITS }

    final boolean category;
    final boolean existing;
    final CompoundTag data;
    private final CompoundTag original;
    private final IdentityHashMap<CompoundTag, String> counts = new IdentityHashMap<>();
    long baseRevision;
    boolean conflicted;
    Section section = Section.GENERAL;
    String id;
    String order;
    String start;
    String end;
    String totalLimit;
    String weeklyLimit;
    String stock;
    boolean stockEdited;

    ShopDraft(boolean category, boolean existing, String id, CompoundTag original, long baseRevision) {
        this.category = category;
        this.existing = existing;
        this.id = id;
        this.original = original;
        this.data = original.copy();
        this.baseRevision = baseRevision;
        order = Integer.toString(data.getInt("order"));
        start = date(data, "start");
        end = date(data, "end");
        totalLimit = Integer.toString(data.getInt("totalLimit"));
        weeklyLimit = Integer.toString(data.getInt("weeklyLimit"));
        stock = Integer.toString(data.getInt("stock"));
    }

    String count(CompoundTag row) {
        int count = row.contains("count") ? row.getInt("count") : 1;
        return counts.getOrDefault(row, Integer.toString(count));
    }

    void setCount(CompoundTag row, String value) {
        counts.put(row, value);
    }

    String normalizedId() {
        String input = id.strip();
        return input.contains(":") ? input : "rabbitquest:" + input;
    }

    boolean canRetry(CompoundTag current) {
        if (!existing) {
            return true;
        }
        if (current.isEmpty()) {
            return false;
        }
        if (category) {
            return current.equals(original);
        }
        return current.getLong("version") == original.getLong("version")
                && (!stockEdited || current.getInt("stock") == original.getInt("stock"));
    }

    CompoundTag serialize(String id, CompoundTag current) {
        var resource = ResourceLocation.tryParse(id);
        if (resource == null || resource.getPath().isEmpty() || !resource.toString().equals(id)
                || data.getString("name").isBlank() || (!category && data.getString("category").isBlank())) {
            throw new IllegalArgumentException();
        }

        CompoundTag result = data.copy();
        result.putString("id", id);
        result.putInt("order", Integer.parseInt(order));
        if (category) {
            return result;
        }

        writeDate(result, "start", start);
        writeDate(result, "end", end);
        if (result.contains("start") && result.contains("end") && result.getLong("start") >= result.getLong("end")) {
            throw new IllegalArgumentException();
        }
        result.putInt("totalLimit", nonNegative(totalLimit));
        result.putInt("weeklyLimit", nonNegative(weeklyLimit));
        int sharedStock = Integer.parseInt(stock);
        if (sharedStock < -1) {
            throw new IllegalArgumentException();
        }
        // Preserve purchase-driven stock changes if the editor never changed stock.
        result.putInt("stock", stockEdited || current.isEmpty() ? sharedStock : current.getInt("stock"));

        ListTag costs = list(data, "costs");
        ListTag savedCosts = list(result, "costs");
        for (int index = 0; index < costs.size(); index++) {
            savedCosts.getCompound(index).putInt("count", positive(count(costs.getCompound(index))));
        }
        ListTag options = list(data, "options");
        ListTag savedOptions = list(result, "options");
        if (!result.getBoolean("choice")) {
            while (savedOptions.size() > 1) {
                savedOptions.remove(savedOptions.size() - 1);
            }
        }
        for (int index = 0; index < savedOptions.size(); index++) {
            ListTag items = list(options.getCompound(index), "items");
            ListTag savedItems = list(savedOptions.getCompound(index), "items");
            for (int item = 0; item < items.size(); item++) {
                savedItems.getCompound(item).putInt("count", positive(count(items.getCompound(item))));
            }
        }
        return result;
    }

    static int positive(String value) {
        int count = nonNegative(value);
        if (count == 0) {
            throw new IllegalArgumentException();
        }
        return count;
    }

    private static int nonNegative(String value) {
        int count = Integer.parseInt(value);
        if (count < 0) {
            throw new IllegalArgumentException();
        }
        return count;
    }

    private static ListTag list(CompoundTag data, String key) {
        return data.getList(key, Tag.TAG_COMPOUND);
    }

    private static void writeDate(CompoundTag data, String key, String value) {
        if (value.isBlank()) {
            data.remove(key);
        } else {
            data.putLong(key, Instant.parse(value).getEpochSecond());
        }
    }

    private static String date(CompoundTag data, String key) {
        return data.contains(key) ? Instant.ofEpochSecond(data.getLong(key)).toString() : "";
    }
}
