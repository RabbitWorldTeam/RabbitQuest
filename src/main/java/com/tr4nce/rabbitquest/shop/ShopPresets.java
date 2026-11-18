package com.tr4nce.rabbitquest.shop;

import com.tr4nce.rabbitquest.item.VoucherItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Built-in test shop presets: a Weapon Shop (all vanilla Netherite weapons) and a Glass Shop
 * (all 16 vanilla stained glass colors). Installed via {@code /rabbitquest shop preset}.
 */
public final class ShopPresets {
    public enum Target { ALL, WEAPONS, GLASS }

    private record GlassEntry(String id, String name, Item item, int order) {}

    private static final List<GlassEntry> STAINED_GLASS = List.of(
            new GlassEntry("rabbitquest:glass_white", "白色染色玻璃 × 64", Items.WHITE_STAINED_GLASS, 0),
            new GlassEntry("rabbitquest:glass_light_gray", "淡灰色染色玻璃 × 64", Items.LIGHT_GRAY_STAINED_GLASS, 1),
            new GlassEntry("rabbitquest:glass_gray", "灰色染色玻璃 × 64", Items.GRAY_STAINED_GLASS, 2),
            new GlassEntry("rabbitquest:glass_black", "黑色染色玻璃 × 64", Items.BLACK_STAINED_GLASS, 3),
            new GlassEntry("rabbitquest:glass_brown", "棕色染色玻璃 × 64", Items.BROWN_STAINED_GLASS, 4),
            new GlassEntry("rabbitquest:glass_red", "红色染色玻璃 × 64", Items.RED_STAINED_GLASS, 5),
            new GlassEntry("rabbitquest:glass_orange", "橙色染色玻璃 × 64", Items.ORANGE_STAINED_GLASS, 6),
            new GlassEntry("rabbitquest:glass_yellow", "黄色染色玻璃 × 64", Items.YELLOW_STAINED_GLASS, 7),
            new GlassEntry("rabbitquest:glass_lime", "黄绿色染色玻璃 × 64", Items.LIME_STAINED_GLASS, 8),
            new GlassEntry("rabbitquest:glass_green", "绿色染色玻璃 × 64", Items.GREEN_STAINED_GLASS, 9),
            new GlassEntry("rabbitquest:glass_cyan", "青色染色玻璃 × 64", Items.CYAN_STAINED_GLASS, 10),
            new GlassEntry("rabbitquest:glass_light_blue", "淡蓝色染色玻璃 × 64", Items.LIGHT_BLUE_STAINED_GLASS, 11),
            new GlassEntry("rabbitquest:glass_blue", "蓝色染色玻璃 × 64", Items.BLUE_STAINED_GLASS, 12),
            new GlassEntry("rabbitquest:glass_purple", "紫色染色玻璃 × 64", Items.PURPLE_STAINED_GLASS, 13),
            new GlassEntry("rabbitquest:glass_magenta", "品红色染色玻璃 × 64", Items.MAGENTA_STAINED_GLASS, 14),
            new GlassEntry("rabbitquest:glass_pink", "粉红色染色玻璃 × 64", Items.PINK_STAINED_GLASS, 15)
    );

    private ShopPresets() {}

    public static int install(ShopSavedData data, HolderLookup.Provider registries, Target target) {
        int added = 0;
        if (target == Target.ALL || target == Target.WEAPONS) {
            added += installWeapons(data, registries);
        }
        if (target == Target.ALL || target == Target.GLASS) {
            added += installGlass(data, registries);
        }
        if (added > 0) {
            data.revision++;
            data.setDirty();
        }
        return added;
    }

    private static int installWeapons(ShopSavedData data, HolderLookup.Provider registries) {
        String categoryId = "rabbitquest:weapon_shop";
        ShopSavedData.put(data.categories, category(categoryId, "武器商店", 0, Items.NETHERITE_SWORD, registries));
        long ver = data.revision + 1;
        Item voucher = VoucherItems.WEAPON_TOOL_VOUCHER.get();

        ShopSavedData.put(data.products, fixedProduct(
                "rabbitquest:weapon_netherite_sword", categoryId, "下界合金剑",
                "使用武器工具兑换券兑换原版下界合金剑。", 0, ver,
                Items.NETHERITE_SWORD, 1, voucher, 1, false, registries));
        ShopSavedData.put(data.products, fixedProduct(
                "rabbitquest:weapon_netherite_axe", categoryId, "下界合金斧",
                "使用武器工具兑换券兑换原版下界合金斧。", 1, ver,
                Items.NETHERITE_AXE, 1, voucher, 1, false, registries));
        ShopSavedData.put(data.products, choiceProduct(
                "rabbitquest:weapon_netherite_choice", categoryId, "下界合金武器自选",
                "从下界合金剑与下界合金斧中任选一把。", 2, ver,
                Items.NETHERITE_SWORD,
                List.of(new ItemStack(Items.NETHERITE_SWORD), new ItemStack(Items.NETHERITE_AXE)),
                voucher, 1, registries));
        return 3;
    }

    private static int installGlass(ShopSavedData data, HolderLookup.Provider registries) {
        String categoryId = "rabbitquest:glass_shop";
        ShopSavedData.put(data.categories, category(categoryId, "玻璃商店", 1, Items.GLASS, registries));
        long ver = data.revision + 1;
        Item voucher = VoucherItems.BUILDING_MATERIAL_VOUCHER.get();

        for (GlassEntry entry : STAINED_GLASS) {
            ShopSavedData.put(data.products, fixedProduct(
                    entry.id(), categoryId, entry.name(),
                    "使用建筑材料兑换券兑换 64 个彩色玻璃（支持批量购买）。",
                    entry.order(), ver, entry.item(), 64, voucher, 1, true, registries));
        }
        return STAINED_GLASS.size();
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
                                            Item costItem, int costCount, boolean batch,
                                            HolderLookup.Provider registries) {
        CompoundTag product = baseProduct(id, categoryId, name, description, order, version, rewardItem,
                costItem, costCount, false, batch, registries);
        ListTag options = new ListTag();
        CompoundTag option = new CompoundTag();
        ListTag items = new ListTag();
        items.add(rewardTag(rewardItem, rewardCount, registries));
        option.put("items", items);
        options.add(option);
        product.put("options", options);
        return product;
    }

    private static CompoundTag choiceProduct(String id, String categoryId, String name, String description,
                                             int order, long version, Item iconItem, List<ItemStack> choices,
                                             Item costItem, int costCount, HolderLookup.Provider registries) {
        CompoundTag product = baseProduct(id, categoryId, name, description, order, version, iconItem,
                costItem, costCount, true, false, registries);
        ListTag options = new ListTag();
        for (ItemStack choice : choices) {
            CompoundTag option = new CompoundTag();
            ListTag items = new ListTag();
            items.add(rewardTag(choice.getItem(), choice.getCount(), registries));
            option.put("items", items);
            options.add(option);
        }
        product.put("options", options);
        return product;
    }

    private static CompoundTag baseProduct(String id, String categoryId, String name, String description,
                                           int order, long version, Item iconItem, Item costItem, int costCount,
                                           boolean choice, boolean batch, HolderLookup.Provider registries) {
        CompoundTag product = new CompoundTag();
        product.putString("id", id);
        product.putString("category", categoryId);
        product.putString("name", name);
        product.putString("description", description);
        product.putInt("order", order);
        product.putLong("version", version);
        product.putBoolean("enabled", true);
        product.putBoolean("choice", choice);
        product.putBoolean("batch", batch);
        product.putInt("totalLimit", 0);
        product.putInt("weeklyLimit", 0);
        product.putInt("stock", -1);
        product.put("icon", new ItemStack(iconItem).save(registries));

        ListTag costs = new ListTag();
        CompoundTag cost = new CompoundTag();
        cost.put("item", new ItemStack(costItem).save(registries));
        cost.putInt("count", costCount);
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
