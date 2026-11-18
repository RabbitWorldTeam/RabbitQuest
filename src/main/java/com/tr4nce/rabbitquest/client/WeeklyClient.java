package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.network.WeeklyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public final class WeeklyClient {
    private WeeklyClient() {}

    public static void open() {
        CompoundTag request = new CompoundTag();
        request.putString("action", "open");
        request.putString("requestId", UUID.randomUUID().toString());
        request.putBoolean("open", true);
        WeeklyNetwork.send(request);
    }

    public static void receive(CompoundTag snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !snapshot.getString("type").equals("weekly_snapshot")) {
            return;
        }
        QuestBookClient.updateWeeklyBadges(snapshot);
        if (client.screen instanceof WeeklyScreen weekly) {
            weekly.receive(snapshot);
        } else if (snapshot.getBoolean("open")) {
            QuestBookClient.remember(QuestBookClient.Tab.WEEKLY);
            client.setScreen(new WeeklyScreen(snapshot));
        }
    }
}
