package com.tr4nce.rabbitquest.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class ShopEdits {
    private static final int MAX_GROUPS = 32;

    private final ServerPlayer player;
    private final ShopSavedData data;
    private final List<ItemStack> templates = new ArrayList<>();

    ShopEdits(ServerPlayer player, ShopSavedData data) {
        this.player = player;
        this.data = data;
        for (Tag category : data.categories) {
            allow(((CompoundTag) category).getCompound("icon"));
        }
        for (Tag entry : data.products) {
            CompoundTag product = (CompoundTag) entry;
            allow(product.getCompound("icon"));
            for (Tag cost : list(product, "costs")) {
                allow(((CompoundTag) cost).getCompound("item"));
            }
            for (Tag option : list(product, "options")) {
                for (Tag item : list((CompoundTag) option, "items")) {
                    allow((CompoundTag) item);
                }
            }
        }
    }

    CompoundTag category(CompoundTag draft) {
        CompoundTag category = entry(draft);
        category.putBoolean("visible", draft.getBoolean("visible"));
        return category;
    }

    CompoundTag product(CompoundTag draft) {
        CompoundTag product = entry(draft);
        String category = identifier(draft, "category");
        require(ShopSavedData.find(data.categories, category) != null);
        product.putString("category", category);
        String description = draft.getString("description");
        require(description.length() <= 1024);
        product.putString("description", description);
        product.putBoolean("enabled", draft.getBoolean("enabled"));
        boolean choice = draft.getBoolean("choice");
        product.putBoolean("choice", choice);
        product.putBoolean("batch", !choice && draft.getBoolean("batch"));
        product.putInt("totalLimit", number(draft, "totalLimit", 0));
        product.putInt("weeklyLimit", number(draft, "weeklyLimit", 0));
        product.putInt("stock", number(draft, "stock", -1));
        optionalDate(draft, product, "start");
        optionalDate(draft, product, "end");
        require(!product.contains("start") || !product.contains("end")
                || product.getLong("start") < product.getLong("end"));

        ListTag costs = new ListTag();
        for (Tag tag : groups(draft, "costs")) {
            CompoundTag row = (CompoundTag) tag;
            CompoundTag cost = new CompoundTag();
            cost.put("item", sample(row.getCompound("item"), false));
            cost.putInt("count", number(row, "count", 1));
            cost.putBoolean("exact", row.getBoolean("exact"));
            costs.add(cost);
        }
        product.put("costs", costs);
        ListTag options = new ListTag();
        for (Tag tag : groups(draft, "options")) {
            CompoundTag option = new CompoundTag();
            ListTag items = new ListTag();
            for (Tag item : groups((CompoundTag) tag, "items")) {
                items.add(sample((CompoundTag) item, true));
            }
            option.put("items", items);
            options.add(option);
        }
        require(choice || options.size() == 1);
        product.put("options", options);
        return product;
    }

    private CompoundTag entry(CompoundTag draft) {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", identifier(draft, "id"));
        String name = draft.getString("name").strip();
        require(!name.isEmpty() && name.length() <= 128);
        entry.putString("name", name);
        entry.putInt("order", draft.getInt("order"));
        CompoundTag icon = draft.getCompound("icon");
        if (!icon.isEmpty()) {
            entry.put("icon", sample(icon, false));
        }
        return entry;
    }

    private CompoundTag sample(CompoundTag tag, boolean keepCount) {
        ItemStack stack;
        if (tag.contains("slot", Tag.TAG_INT)) {
            int slot = tag.getInt("slot");
            require(slot >= 0 && slot < player.getInventory().items.size());
            stack = player.getInventory().items.get(slot).copy();
            ItemStack expected = parse(tag.getCompound("expected"));
            require(!stack.isEmpty() && !expected.isEmpty()
                    && ItemStack.isSameItemSameComponents(stack, expected));
            int count = tag.contains("rewardCount", Tag.TAG_INT) ? tag.getInt("rewardCount")
                    : (tag.contains("count", Tag.TAG_INT) ? tag.getInt("count") : stack.getCount());
            require(!stack.isEmpty() && (!keepCount || count > 0));
            stack.setCount(1);
            CompoundTag saved = (CompoundTag) stack.save(player.registryAccess());
            if (keepCount) {
                saved.putInt("rewardCount", count);
            }
            return saved;
        } else {
            stack = parse(tag);
            int count = tag.contains("rewardCount", Tag.TAG_INT) ? tag.getInt("rewardCount")
                    : (tag.contains("count", Tag.TAG_INT) ? tag.getInt("count") : stack.getCount());
            require(!stack.isEmpty() && (sampledFromInventory(stack) || templates.stream()
                    .anyMatch(template -> ItemStack.isSameItemSameComponents(stack, template))));
            require(!keepCount || count > 0);
            stack.setCount(1);
            CompoundTag saved = (CompoundTag) stack.save(player.registryAccess());
            if (keepCount) {
                saved.putInt("rewardCount", count);
            }
            return saved;
        }
    }

    private boolean sampledFromInventory(ItemStack stack) {
        return player.getInventory().items.stream()
                .anyMatch(sample -> !sample.isEmpty() && ItemStack.isSameItemSameComponents(stack, sample));
    }

    private void allow(CompoundTag tag) {
        ItemStack stack = parse(tag);
        if (!stack.isEmpty()) {
            templates.add(stack);
        }
    }

    private ItemStack parse(CompoundTag tag) {
        return tag.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(player.registryAccess(), tag).orElse(ItemStack.EMPTY);
    }

    private static ListTag list(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_COMPOUND);
    }

    private static ListTag groups(CompoundTag tag, String key) {
        ListTag result = list(tag, key);
        require(!result.isEmpty() && result.size() <= MAX_GROUPS);
        return result;
    }

    private static String identifier(CompoundTag draft, String key) {
        String id = draft.getString(key);
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        require(id.length() <= 128 && parsed != null && !parsed.getPath().isEmpty() && id.equals(parsed.toString()));
        return id;
    }

    private static void optionalDate(CompoundTag draft, CompoundTag entry, String key) {
        if (draft.contains(key)) {
            long seconds = draft.getLong(key);
            require(draft.contains(key, Tag.TAG_LONG) && seconds >= Instant.MIN.getEpochSecond()
                    && seconds <= Instant.MAX.getEpochSecond());
            entry.putLong(key, seconds);
        }
    }

    private static int number(CompoundTag draft, String key, int minimum) {
        require(draft.contains(key, Tag.TAG_INT));
        int number = draft.getInt(key);
        require(number >= minimum);
        return number;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid RabbitQuest shop definition");
        }
    }
}
