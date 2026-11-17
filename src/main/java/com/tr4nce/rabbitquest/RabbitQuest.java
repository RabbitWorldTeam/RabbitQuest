package com.tr4nce.rabbitquest;

import com.tr4nce.rabbitquest.item.VoucherItems;
import com.tr4nce.rabbitquest.network.ShopNetwork;
import com.tr4nce.rabbitquest.shop.ShopService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(RabbitQuest.MOD_ID)
public final class RabbitQuest {
    public static final String MOD_ID = "rabbitquest";

    public RabbitQuest(IEventBus modEventBus) {
        VoucherItems.register(modEventBus);
        modEventBus.addListener(ShopNetwork::register);
        NeoForge.EVENT_BUS.addListener(ShopService::registerCommands);
        NeoForge.EVENT_BUS.addListener(ShopService::playerLoggedIn);
    }
}
