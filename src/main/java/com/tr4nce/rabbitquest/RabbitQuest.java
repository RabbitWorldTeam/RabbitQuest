package com.tr4nce.rabbitquest;

import com.tr4nce.rabbitquest.block.RabbitChest;
import com.tr4nce.rabbitquest.network.ChestNetwork;
import com.tr4nce.rabbitquest.config.ReloadService;
import com.tr4nce.rabbitquest.event.EventService;
import com.tr4nce.rabbitquest.item.VoucherItems;
import com.tr4nce.rabbitquest.network.EventNetwork;
import com.tr4nce.rabbitquest.network.QuestBookNetwork;
import com.tr4nce.rabbitquest.network.ShopNetwork;
import com.tr4nce.rabbitquest.network.WeeklyNetwork;
import com.tr4nce.rabbitquest.shop.ShopService;
import com.tr4nce.rabbitquest.stats.Action;
import com.tr4nce.rabbitquest.stats.StatsService;
import com.tr4nce.rabbitquest.storage.RecoveryService;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.TaskEventListeners;
import com.tr4nce.rabbitquest.weekly.WeeklyService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(RabbitQuest.MOD_ID)
public final class RabbitQuest {
    public static final String MOD_ID = "rabbitquest";

    public RabbitQuest(IEventBus modEventBus) {
        VoucherItems.register(modEventBus);
        RabbitChest.register(modEventBus);
        modEventBus.addListener(ChestNetwork::register);
        modEventBus.addListener(ShopNetwork::register);
        modEventBus.addListener(WeeklyNetwork::register);
        modEventBus.addListener(EventNetwork::register);
        modEventBus.addListener(QuestBookNetwork::register);
        StatsService.register();
        TaskEventListeners.register(NeoForge.EVENT_BUS);
        StatsService.setEventSink(RabbitQuest::forwardStat);
        NeoForge.EVENT_BUS.addListener(RecoveryService::serverStarted);
        NeoForge.EVENT_BUS.addListener(RecoveryService::serverStopped);
        NeoForge.EVENT_BUS.addListener(RecoveryService::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ShopService::registerCommands);
        NeoForge.EVENT_BUS.addListener(ShopService::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(WeeklyService::registerCommands);
        NeoForge.EVENT_BUS.addListener(WeeklyService::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(WeeklyService::serverStarted);
        NeoForge.EVENT_BUS.addListener(WeeklyService::serverStopped);
        NeoForge.EVENT_BUS.addListener(WeeklyService::playerTick);
        NeoForge.EVENT_BUS.addListener(EventService::registerCommands);
        NeoForge.EVENT_BUS.addListener(EventService::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(EventService::serverStarted);
        NeoForge.EVENT_BUS.addListener(EventService::serverStopped);
        NeoForge.EVENT_BUS.addListener(EventService::playerTick);
        NeoForge.EVENT_BUS.addListener(ReloadService::registerCommands);
    }

    private static void forwardStat(Action action) {
        TargetKind kind = switch (action.type()) {
            case HARVEST -> action.matureCrop() ? TargetKind.MATURE_HARVEST : TargetKind.HARVEST;
            case KILL -> TargetKind.KILL;
            case DAMAGE -> TargetKind.DAMAGE;
        };
        Vec3 pos = Vec3.atCenterOf(action.position());
        ResourceLocation biome = null;
        if (kind == TargetKind.DAMAGE) {
            var level = action.player().getServer().getLevel(action.dimension());
            if (level != null) {
                biome = level.getBiome(action.position()).unwrapKey().map(ResourceKey::location).orElse(null);
            }
        }
        WeeklyService.recordEvent(action.player(), kind, action.target(), action.amount(),
                action.dimension(), pos, biome);
        EventService.recordEvent(action.player(), kind, action.target(), action.amount(),
                action.dimension(), pos, biome);
    }
}
