package com.tr4nce.rabbitquest.item;

import com.tr4nce.rabbitquest.RabbitQuest;
import com.tr4nce.rabbitquest.block.RabbitChest;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class VoucherItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RabbitQuest.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RabbitQuest.MOD_ID);

    public static final DeferredItem<Item> BUILDING_MATERIAL_VOUCHER =
            ITEMS.registerSimpleItem("building_material_voucher", new Item.Properties().stacksTo(64));
    public static final DeferredItem<Item> WEAPON_TOOL_VOUCHER =
            ITEMS.registerSimpleItem("weapon_tool_voucher", new Item.Properties().stacksTo(64));
    public static final DeferredItem<QuestBookItem> QUEST_BOOK =
            ITEMS.register("quest_book", () -> new QuestBookItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> RABBITQUEST_TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rabbitquest"))
                    .icon(() -> new ItemStack(QUEST_BOOK.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(QUEST_BOOK.get());
                        output.accept(RabbitChest.ITEM.get());
                        output.accept(BUILDING_MATERIAL_VOUCHER.get());
                        output.accept(WEAPON_TOOL_VOUCHER.get());
                    })
                    .build());

    private VoucherItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}
