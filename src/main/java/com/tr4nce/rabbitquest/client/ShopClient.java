package com.tr4nce.rabbitquest.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tr4nce.rabbitquest.RabbitQuest;
import com.tr4nce.rabbitquest.network.ShopNetwork;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = RabbitQuest.MOD_ID, value = Dist.CLIENT)
public final class ShopClient {
    private static final KeyMapping OPEN_SHOP = new KeyMapping(
            "key.rabbitquest.shop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.rabbitquest");

    private ShopClient() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SHOP);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        while (OPEN_SHOP.consumeClick()) {
            if (client.player != null && client.screen == null) {
                CompoundTag request = new CompoundTag();
                request.putString("action", "open");
                request.putString("requestId", UUID.randomUUID().toString());
                ShopNetwork.send(request);
            }
        }
    }

    public static void receive(CompoundTag snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !snapshot.getString("type").equals("snapshot")) {
            return;
        }
        if (client.screen instanceof ShopScreen shop) {
            shop.receive(snapshot);
        } else if (snapshot.getBoolean("open")) {
            QuestBookClient.remember(QuestBookClient.Tab.SHOP);
            client.setScreen(new ShopScreen(snapshot));
        }
    }
}
