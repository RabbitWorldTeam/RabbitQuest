package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.block.RabbitChest;
import com.tr4nce.rabbitquest.network.ChestNetwork;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = "rabbitquest", value = Dist.CLIENT)
public final class ChestScreen extends Screen {
    private CompoundTag snapshot;
    private String search = "";
    private String selection;
    private int scroll;
    private int capacity;
    private int rowCount;
    private EditBox searchBox;
    private List<CompoundTag> visibleRows = List.of();
    private boolean pending;

    private ChestScreen(CompoundTag snapshot) {
        super(text("title"));
        this.snapshot = snapshot;
        selection = snapshot.getString("binding");
    }

    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(RabbitChest.ENTITY.get(), ChestRenderer::new);
    }

    public static void receive(CompoundTag snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (client.screen instanceof ChestScreen screen
                && screen.snapshot.getLong("pos") == snapshot.getLong("pos")
                && screen.snapshot.getString("dimension").equals(snapshot.getString("dimension"))) {
            screen.snapshot = snapshot;
            screen.pending = false;
            if ("checked".equals(snapshot.getString("message")) && !snapshot.getString("binding").isEmpty()) {
                screen.search = snapshot.getString("binding");
                screen.scroll = 0;
            }
            screen.rebuildWidgets();
        } else if (snapshot.getString("message").isEmpty()) {
            client.setScreen(new ChestScreen(snapshot));
        }
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(430, width - 16);
        int x = (width - panelWidth) / 2;
        capacity = Math.max(1, (height - 122) / 22);
        searchBox = new EditBox(font, x, 29, panelWidth, 18, text("search"));
        searchBox.setHint(text("search"));
        searchBox.setMaxLength(96);
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            int cursor = searchBox.getCursorPosition();
            search = value;
            scroll = 0;
            rebuildWidgets();
            setFocused(searchBox);
            searchBox.setCursorPosition(cursor);
        });
        addRenderableWidget(searchBox);

        String query = search.strip().toLowerCase(Locale.ROOT);
        visibleRows = snapshot.getList("targets", Tag.TAG_COMPOUND).stream()
                .map(tag -> (CompoundTag) tag)
                .filter(row -> (label(row).getString() + " " + row.getString("id"))
                        .toLowerCase(Locale.ROOT).contains(query))
                .toList();
        rowCount = visibleRows.size();
        scroll = Mth.clamp(scroll, 0, Math.max(0, rowCount - capacity));
        for (int i = scroll; i < Math.min(rowCount, scroll + capacity); i++) {
            CompoundTag row = visibleRows.get(i);
            String id = row.getString("id");
            Component full = label(row).copy().append(" · ").append(text("status." + row.getString("status")));
            String fitted = font.plainSubstrByWidth(full.getString(), panelWidth - 32);
            Button button = addRenderableWidget(Button.builder(Component.literal(fitted), widget -> {
                selection = id;
                rebuildWidgets();
            }).bounds(x, 51 + (i - scroll) * 22, panelWidth - 24, 20).build());
            button.setTooltip(Tooltip.create(full.copy().append(" [" + id + "]")));
            button.active = !selection.equals(id) && !pending;
        }
        addRenderableWidget(Button.builder(Component.literal("↑"), button -> scroll(-1))
                .bounds(x + panelWidth - 20, 51, 20, 20).build()).active = scroll > 0;
        addRenderableWidget(Button.builder(Component.literal("↓"), button -> scroll(1))
                .bounds(x + panelWidth - 20, 73, 20, 20).build()).active = scroll + capacity < rowCount;
        int third = (panelWidth - 8) / 3;
        addRenderableWidget(Button.builder(text("bind"), button -> submit("bind"))
                .bounds(x, height - 25, third, 20).build()).active = !pending && !selection.isEmpty()
                && !selection.equals(snapshot.getString("binding"));
        addRenderableWidget(Button.builder(text("check"), button -> submit("check"))
                .bounds(x + third + 4, height - 25, third, 20).build()).active = !pending;
        addRenderableWidget(Button.builder(text("close"), button -> onClose())
                .bounds(x + 2 * (third + 4), height - 25, third, 20).build());
    }

    private Component label(CompoundTag row) {
        Component component = Component.Serializer.fromJson(row.getString("title"), minecraft.player.registryAccess());
        return component == null ? Component.literal(row.getString("id")) : component;
    }

    private void submit(String action) {
        CompoundTag request = new CompoundTag();
        request.putString("action", action);
        request.putLong("pos", snapshot.getLong("pos"));
        request.putString("dimension", snapshot.getString("dimension"));
        request.putString("binding", selection);
        pending = true;
        ChestNetwork.send(request);
        rebuildWidgets();
    }

    private void scroll(int delta) {
        scroll = Mth.clamp(scroll + delta, 0, Math.max(0, rowCount - capacity));
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && rowCount > capacity) {
            scroll(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void render(GuiGraphics gui, int x, int y, float partialTick) {
        super.render(gui, x, y, partialTick);
        BlockPos pos = BlockPos.of(snapshot.getLong("pos"));
        gui.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        gui.drawCenteredString(font, pos.toShortString(), width / 2, 17, 0xFFFFFF);
        String binding = snapshot.getString("binding");
        boolean valid = snapshot.getList("targets", Tag.TAG_COMPOUND).stream()
                .anyMatch(tag -> ((CompoundTag) tag).getString("id").equals(binding));
        Component current = binding.isEmpty() ? text("unbound")
                : text(valid ? "bound" : "orphan", binding);
        gui.drawCenteredString(font, current, width / 2, height - 62, 0xFFFFFF);
        if (!selection.isEmpty() && !selection.equals(binding)) {
            gui.drawCenteredString(font, text("selected", selection), width / 2, height - 49, 0xFFFFFF);
        }
        if (visibleRows.isEmpty()) {
            gui.drawCenteredString(font, text("empty"), width / 2, 54, 0xFFFFFF);
        }
        String message = snapshot.getString("message");
        if (pending || !message.isEmpty()) {
            gui.drawCenteredString(font, text(pending ? "pending" : message), width / 2, height - 37, 0xFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("rabbitquest.chest." + key, args);
    }
}
