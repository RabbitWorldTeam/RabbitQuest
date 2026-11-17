package com.tr4nce.rabbitquest.item;

import com.tr4nce.rabbitquest.RabbitQuest;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class VoucherItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RabbitQuest.MOD_ID);

    public static final DeferredItem<Item> BUILDING_MATERIAL_VOUCHER =
            ITEMS.registerSimpleItem("building_material_voucher", new Item.Properties().stacksTo(64));
    public static final DeferredItem<Item> WEAPON_TOOL_VOUCHER =
            ITEMS.registerSimpleItem("weapon_tool_voucher", new Item.Properties().stacksTo(64));

    private VoucherItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
