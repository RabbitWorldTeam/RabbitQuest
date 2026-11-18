package com.tr4nce.rabbitquest.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native top-right corner toast shown when a weekly task or timed event stage completes. */
final class QuestToast implements Toast {
    private static final ResourceLocation BACKGROUND =
            ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long DISPLAY_MS = 5000L;
    private static final int TITLE_COLOR = 0xFFFFD84D;
    private static final int BODY_COLOR = 0xFFFFFFFF;

    private final Component title;
    private final Component subtitle;
    private final ItemStack icon;

    QuestToast(Component title, Component subtitle, String iconId) {
        this.title = title;
        this.subtitle = subtitle;
        ResourceLocation id = ResourceLocation.tryParse(iconId);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            this.icon = new ItemStack(BuiltInRegistries.ITEM.get(id));
        } else {
            this.icon = new ItemStack(Items.BOOK);
        }
    }

    @Override
    public Visibility render(GuiGraphics gui, ToastComponent toastComponent, long timeSinceLastVisible) {
        gui.blitSprite(BACKGROUND, 0, 0, width(), height());
        gui.renderFakeItem(icon, 8, 8);
        Font font = toastComponent.getMinecraft().font;
        int maxWidth = width() - 36;
        String header = font.plainSubstrByWidth(title.getString(), maxWidth);
        String body = font.plainSubstrByWidth(subtitle.getString(), maxWidth);
        gui.drawString(font, header, 30, 7, TITLE_COLOR, false);
        gui.drawString(font, body, 30, 18, BODY_COLOR, false);
        return timeSinceLastVisible >= DISPLAY_MS * toastComponent.getNotificationDisplayTimeMultiplier()
                ? Visibility.HIDE : Visibility.SHOW;
    }
}
