package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.network.EventNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public final class EventClient {
    private EventClient() {}

    public static void open() {
        CompoundTag request = new CompoundTag();
        request.putString("action", "open");
        request.putString("requestId", UUID.randomUUID().toString());
        request.putBoolean("open", true);
        EventNetwork.send(request);
    }

    public static void receive(CompoundTag snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !snapshot.getString("type").equals("event_snapshot")) {
            return;
        }
        if (client.screen instanceof EventScreen eventScreen) {
            eventScreen.receive(snapshot);
        } else if (snapshot.getBoolean("open")) {
            client.setScreen(new EventScreen(snapshot));
        }
    }
}
