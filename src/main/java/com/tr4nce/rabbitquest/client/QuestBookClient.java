package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.RabbitQuest;
import com.tr4nce.rabbitquest.item.QuestBookItem;
import com.tr4nce.rabbitquest.network.ShopNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.List;
import java.util.UUID;

/** Shared client-side Quest Book state: remembered tab, claimable badge counts and book audio. */
@EventBusSubscriber(modid = RabbitQuest.MOD_ID, value = Dist.CLIENT)
public final class QuestBookClient {
    public enum Tab {
        SHOP, WEEKLY, EVENTS
    }

    private static Tab lastTab = Tab.WEEKLY;
    static ShopScreen.Position shopPosition;
    static WeeklyScreen.Position weeklyPosition;
    static EventScreen.Position eventPosition;
    private static int weeklyClaimable;
    private static int eventClaimable;

    static {
        QuestBookItem.setClientTooltipHook(QuestBookClient::appendTooltip);
    }

    private QuestBookClient() {}

    public static Tab lastTab() {
        return lastTab;
    }

    public static void remember(Tab tab) {
        lastTab = tab;
    }

    public static int badge(Tab tab) {
        return switch (tab) {
            case SHOP -> 0;
            case WEEKLY -> weeklyClaimable;
            case EVENTS -> eventClaimable;
        };
    }

    public static void updateWeeklyBadges(CompoundTag snapshot) {
        weeklyClaimable = snapshot.getList("claims", Tag.TAG_COMPOUND).size();
    }

    public static void updateEventBadges(CompoundTag snapshot) {
        int count = 0;
        for (Tag tag : snapshot.getList("events", Tag.TAG_COMPOUND)) {
            CompoundTag event = (CompoundTag) tag;
            if (event.getBoolean("completionClaimable")) {
                count++;
            }
            ListTag steps = event.getList("steps", Tag.TAG_COMPOUND);
            for (Tag stepTag : steps) {
                if (((CompoundTag) stepTag).getBoolean("claimable")) {
                    count++;
                }
            }
        }
        eventClaimable = count;
    }

    public static void openFromBook() {
        playPageTurn();
        openTab(lastTab);
    }

    public static void switchTab(Tab tab) {
        Runnable change = () -> {
            playPageTurn();
            openTab(tab);
        };
        if (Minecraft.getInstance().screen instanceof ShopScreen shop) {
            shop.leaveEditor(change);
        } else {
            change.run();
        }
    }

    public static void openTab(Tab tab) {
        lastTab = tab;
        switch (tab) {
            case SHOP -> {
                CompoundTag request = new CompoundTag();
                request.putString("action", "open");
                request.putString("requestId", UUID.randomUUID().toString());
                ShopNetwork.send(request);
            }
            case WEEKLY -> WeeklyClient.open();
            case EVENTS -> EventClient.open();
        }
    }

    public static void showToast(String titleKey, String subtitle, String subtitleKey, String iconId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        Component title = Component.translatable(titleKey);
        Component body = subtitleKey != null && !subtitleKey.isBlank()
                ? Component.translatable(subtitleKey)
                : Component.literal(subtitle == null ? "" : subtitle);
        client.getToasts().addToast(new QuestToast(title, body, iconId));
        client.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.5F, 1.0F);
    }

    public static void playPageTurn() {
        playSound(SoundEvents.BOOK_PAGE_TURN, 1.0F, 1.0F);
    }

    public static void playSound(SoundEvent sound, float volume, float pitch) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.playSound(sound, volume, pitch);
        }
    }

    private static void appendTooltip(List<Component> tooltip) {
        if (weeklyClaimable > 0 || eventClaimable > 0) {
            tooltip.add(Component.translatable("item.rabbitquest.quest_book.claimable",
                    weeklyClaimable, eventClaimable).withStyle(ChatFormatting.GOLD));
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void reset() {
        shopPosition = null;
        weeklyPosition = null;
        eventPosition = null;
        weeklyClaimable = 0;
        eventClaimable = 0;
        lastTab = Tab.WEEKLY;
    }
}
