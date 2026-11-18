package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.client.QuestBookClient.Tab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Renders bookmark-style tabs attached to the left border of the Quest Book screens. */
final class BookmarkTabs {
    static final int GUTTER = 84;
    private static final int WIDTH = 80;
    private static final int HEIGHT = 24;
    private static final int GAP = 5;
    private static final int ACTIVE_BG = 0xFFC6C1B4;
    private static final int INACTIVE_BG = 0xFFAFA89B;
    private static final int HOVER_BG = 0xFFBEB7A9;
    private static final int OUTLINE = 0xFF595449;
    private static final int TEXT_ACTIVE = 0xFF2B261E;
    private static final int TEXT_INACTIVE = 0xFF4C463C;
    private static final int BADGE_BG = 0xFFC94A38;

    private BookmarkTabs() {}

    static void attach(Tab activeTab, int panelLeft, int panelTop, boolean enabled,
                       Consumer<BookmarkButton> registrar) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            boolean isActive = tab == activeTab;
            int y = panelTop + 24 + i * (HEIGHT + GAP);
            int x = panelLeft - WIDTH + (isActive ? 2 : 5);
            int w = WIDTH - (isActive ? 0 : 3);
            BookmarkButton button = new BookmarkButton(x, y, w, HEIGHT, tab, isActive, enabled);
            registrar.accept(button);
        }
    }

    static final class BookmarkButton extends AbstractButton {
        private final Tab tab;
        private final boolean selected;
        private final boolean clickable;

        BookmarkButton(int x, int y, int width, int height, Tab tab, boolean selected, boolean clickable) {
            super(x, y, width, height, labelFor(tab));
            this.tab = tab;
            this.selected = selected;
            this.clickable = clickable;
            this.active = !selected && clickable;
        }

        private static Component labelFor(Tab tab) {
            return switch (tab) {
                case SHOP -> Component.translatable("rabbitquest.tab.shop");
                case WEEKLY -> Component.translatable("rabbitquest.tab.weekly");
                case EVENTS -> Component.translatable("rabbitquest.tab.events");
            };
        }

        @Override
        public void onPress() {
            if (!selected && clickable) {
                QuestBookClient.switchTab(tab);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int bg = selected ? ACTIVE_BG : (isHoveredOrFocused() && active ? HOVER_BG : INACTIVE_BG);
            gui.fill(x, y, x + w, y + h, bg);
            gui.hLine(x, x + w - 1, y, OUTLINE);
            gui.hLine(x, x + w - 1, y + h - 1, OUTLINE);
            gui.vLine(x, y, y + h - 1, OUTLINE);
            if (!selected) {
                gui.vLine(x + w - 1, y, y + h - 1, OUTLINE);
            }

            Font font = Minecraft.getInstance().font;
            int badge = QuestBookClient.badge(tab);
            int textColor = selected ? TEXT_ACTIVE : TEXT_INACTIVE;
            int maxTextWidth = badge > 0 ? w - 24 : w - 10;
            String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(8, maxTextWidth));
            int textY = y + (h - font.lineHeight) / 2;
            gui.drawString(font, text, x + 6, textY, textColor, false);

            if (badge > 0) {
                String badgeText = badge > 99 ? "99+" : Integer.toString(badge);
                int bw = Math.max(12, font.width(badgeText) + 6);
                int bx = x + w - bw - 4;
                int by = y + (h - 12) / 2;
                gui.fill(bx, by, bx + bw, by + 12, BADGE_BG);
                gui.drawCenteredString(font, badgeText, bx + bw / 2, by + 2, 0xFFFFFFFF);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            defaultButtonNarrationText(narration);
        }
    }
}
