package com.tr4nce.rabbitquest.shop;

import com.tr4nce.rabbitquest.item.VoucherItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Built-in shop presets installed via {@code /rabbitquest shop preset}.
 */
public final class ShopPresets {
    private ShopPresets() {}

    static int installMaterials(ShopSavedData data, HolderLookup.Provider registries) {
        int previousCategories = data.categories.size();

        Item voucher = VoucherItems.BUILDING_MATERIAL_VOUCHER.get();
        int added = 0;
        int categoryOrder = 10;
        long version = data.revision + 1;
        for (BuildingMaterials.Category group : BuildingMaterials.CATEGORIES) {
            String categoryId = "rabbitquest:materials_" + group.id();
            if (ShopSavedData.find(data.categories, categoryId) == null) {
                ShopSavedData.put(data.categories, category(categoryId, group.name(), categoryOrder,
                        group.entries().getFirst().item(), registries));
            }
            categoryOrder++;

            int productOrder = 0;
            for (BuildingMaterials.Entry entry : group.entries()) {
                String productId = "rabbitquest:materials_" + entry.id();
                if (ShopSavedData.find(data.products, productId) == null) {
                    int units = BuildingMaterials.UNITS_PER_VOUCHER;
                    ShopSavedData.put(data.products, fixedProduct(productId, categoryId, entry.name(),
                            "每张建筑材料兑换券可兑换 " + units + " 个。支持批量购买。", productOrder,
                            version, entry.item(), units, voucher, registries));
                    added++;
                }
                productOrder++;
            }
        }
        if (added > 0 || data.categories.size() != previousCategories) {
            data.revision++;
            data.setDirty();
        }
        return added;
    }

    private static CompoundTag category(String id, String name, int order, Item icon,
                                        HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("name", name);
        tag.putInt("order", order);
        tag.putBoolean("visible", true);
        tag.put("icon", new ItemStack(icon).save(registries));
        return tag;
    }

    private static CompoundTag fixedProduct(String id, String categoryId, String name, String description,
                                            int order, long version, Item rewardItem, int rewardCount,
                                            Item voucher,
                                            HolderLookup.Provider registries) {
        CompoundTag product = baseProduct(id, categoryId, name, description, order, version, rewardItem,
                voucher, registries);
        ListTag options = new ListTag();
        CompoundTag option = new CompoundTag();
        ListTag items = new ListTag();
        items.add(rewardTag(rewardItem, rewardCount, registries));
        option.put("items", items);
        options.add(option);
        product.put("options", options);
        return product;
    }

    private static CompoundTag baseProduct(String id, String categoryId, String name, String description,
                                           int order, long version, Item iconItem, Item voucher,
                                           HolderLookup.Provider registries) {
        CompoundTag product = new CompoundTag();
        product.putString("id", id);
        product.putString("category", categoryId);
        product.putString("name", name);
        product.putString("description", description);
        product.putInt("order", order);
        product.putLong("version", version);
        product.putBoolean("enabled", true);
        product.putBoolean("choice", false);
        product.putBoolean("batch", true);
        product.putInt("totalLimit", 0);
        product.putInt("weeklyLimit", 0);
        product.putInt("stock", -1);
        product.put("icon", new ItemStack(iconItem).save(registries));

        ListTag costs = new ListTag();
        CompoundTag cost = new CompoundTag();
        cost.put("item", new ItemStack(voucher).save(registries));
        cost.putInt("count", 1);
        cost.putBoolean("exact", false);
        costs.add(cost);
        product.put("costs", costs);
        return product;
    }

    private static CompoundTag rewardTag(Item item, int rewardCount, HolderLookup.Provider registries) {
        CompoundTag saved = (CompoundTag) new ItemStack(item).save(registries);
        saved.putInt("rewardCount", rewardCount);
        return saved;
    }
}
