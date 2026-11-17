package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.network.ShopNetwork;
import com.tr4nce.rabbitquest.network.WeeklyNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/** Player-facing weekly summary; reached from the shop and returns to the shop. */
final class WeeklyScreen extends Screen {
    private static final String KEY_PREFIX = "rabbitquest.weekly.";
    private static final int ROW_HEIGHT = 22;
    private static final int TEXT_COLOR = 0xFF3F3F3F;
    private static final int PANEL_COLOR = 0xFFC6C1B4;
    private static final int OUTLINE_COLOR = 0xFF595449;

    private final List<Caption> captions = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final List<Consumer<Integer>> rows = new ArrayList<>();
    private CompoundTag snapshot;
    private String message = "";
    private String pendingRequest = "";
    private String selectedTask = "";
    private int scroll;
    private int selection;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int contentWidth;

    WeeklyScreen(CompoundTag snapshot) {
        super(label("title"));
        this.snapshot = snapshot;
    }

    void receive(CompoundTag incoming) {
        snapshot = incoming;
        String reply = incoming.getString("requestId");
        if (!pendingRequest.isEmpty() && reply.equals(pendingRequest)) {
            pendingRequest = "";
            message = incoming.getString("message");
        }
        String first = firstTaskId(incoming);
        if (!hasTask(incoming, selectedTask)) {
            selectedTask = first;
            selection = 0;
            scroll = 0;
        }
        rebuild();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 12, 470);
        panelHeight = Math.min(height - 12, 330);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        contentTop = top + 32;
        contentBottom = top + panelHeight - 52;
        contentWidth = panelWidth - 18;
        captions.clear();
        icons.clear();
        rows.clear();

        addButton(label("title"), left + 7, top + 6, 120, () -> rebuild()).active = false;
        addButton(label("refresh"), left + panelWidth - 150, top + 6, 66, WeeklyClient::open)
                .active = pendingRequest.isEmpty();
        addButton(label("back"), left + panelWidth - 80, top + 6, 73, this::backToShop)
                .active = pendingRequest.isEmpty();
        addButton(label("close"), left + panelWidth - 80, top + panelHeight - 25, 73, this::onClose);

        buildRows();
        int capacity = capacity();
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - capacity));
        for (int index = scroll; index < Math.min(scroll + capacity, rows.size()); index++) {
            rows.get(index).accept(contentTop + (index - scroll) * ROW_HEIGHT);
        }
        if (rows.size() > capacity) {
            addButton(label("up"), left + panelWidth - 22, top + 32, 14, () -> scrollBy(-1))
                    .active = scroll > 0 && pendingRequest.isEmpty();
            addButton(label("down"), left + panelWidth - 22, top + 54, 14, () -> scrollBy(1))
                    .active = scroll + capacity < rows.size() && pendingRequest.isEmpty();
        }
        if (!message.isEmpty()) {
            caption(text(message), left + 7, top + panelHeight - 19, panelWidth - 90);
        }
    }

    private void buildRows() {
        if (snapshot.contains("error") && !snapshot.getString("error").isBlank()) {
            textRow(text(snapshot.getString("error")));
            return;
        }
        ListTag tasks = list(snapshot, "tasks");
        if (tasks.isEmpty()) {
            textRow(label("no_order"));
            buildClaims();
            return;
        }
        if (tasks.size() > 1) {
            textRow(label("tasks"));
            for (Tag tag : tasks) {
                CompoundTag task = (CompoundTag) tag;
                rows.add(y -> addButton(display(task, "task"), left + 7, y, contentWidth,
                        () -> selectTask(task)).active = pendingRequest.isEmpty());
            }
        }
        CompoundTag task = selectedTaskTag();
        if (task == null) {
            textRow(label("no_order"));
            buildClaims();
            return;
        }

        if (!task.getString("orderName").isEmpty() || !task.getString("orderNameKey").isEmpty()) {
            rows.add(y -> {
                icon(task.getString("orderIcon"), left + 7, y + 2);
                caption(label("order_line", display(task, "order")), left + 27, y + 6, contentWidth - 27);
            });
        }
        rows.add(y -> {
            icon(task.getString("taskIcon"), left + 7, y + 2);
            caption(label("task_line", display(task, "task")), left + 27, y + 6, contentWidth - 27);
        });
        Component description = displayDescription(task, "task");
        if (!description.getString().isEmpty()) {
            for (FormattedCharSequence line : font.split(description, contentWidth)) {
                rows.add(y -> captions.add(new Caption(line, left + 7, y + 6)));
            }
        }

        rows.add(y -> caption(label("targets"), left + 7, y + 6, contentWidth));
        boolean closed = task.getBoolean("closed") || task.getBoolean("expired");
        for (Tag tag : list(task, "targets")) {
            CompoundTag target = (CompoundTag) tag;
            String kind = target.getString("kind");
            if (kind.equals("DELIVER") || kind.equals("DELIVERY")) {
                rows.add(y -> {
                    caption(targetText(target), left + 7, y + 6, contentWidth - 78);
                    addButton(label("deliver"), left + panelWidth - 79, y, 72, () -> deliver(target))
                            .active = !closed && !task.getBoolean("completed") && pendingRequest.isEmpty();
                });
            } else {
                rows.add(y -> caption(targetText(target), left + 7, y + 6, contentWidth));
            }
        }

        rows.add(y -> caption(label("rewards"), left + 7, y + 6, contentWidth));
        CompoundTag reward = task.getCompound("reward");
        String mode = reward.getString("mode");
        if (mode.equals("CHOICE") || mode.equals("WEIGHTED")) {
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            if (mode.equals("CHOICE") && groups.size() > 1) {
                rows.add(y -> addButton(label("option", selection + 1, groups.size()), left + 7, y,
                        contentWidth, () -> {
                            selection = (selection + 1) % groups.size();
                            rebuild();
                        }).active = pendingRequest.isEmpty());
            }
            selection = Mth.clamp(selection, 0, Math.max(0, groups.size() - 1));
            int shown = mode.equals("WEIGHTED") && task.getInt("rewardGroup") >= 0
                    ? task.getInt("rewardGroup") : selection;
            if (!groups.isEmpty()) {
                CompoundTag group = groups.getCompound(Mth.clamp(shown, 0, groups.size() - 1));
                for (Tag item : group.getList("items", Tag.TAG_COMPOUND)) {
                    rewardRow((CompoundTag) item);
                }
            }
        } else {
            for (Tag item : reward.getList("items", Tag.TAG_COMPOUND)) {
                rewardRow((CompoundTag) item);
            }
        }

        if (task.getBoolean("claimable")) {
            rows.add(y -> addButton(label("claim"), left + 7, y, contentWidth, this::claimCurrent)
                    .active = pendingRequest.isEmpty());
        } else if (task.getBoolean("claimed")) {
            textRow(label("claimed"));
        } else if (task.getBoolean("expired")) {
            textRow(label("expired"));
        } else if (task.getBoolean("completed")) {
            textRow(label("expired"));
        }

        buildClaims();
    }

    private void buildClaims() {
        ListTag claims = snapshot.getList("claims", Tag.TAG_COMPOUND);
        if (claims.isEmpty()) {
            return;
        }
        textRow(label("claims"));
        for (Tag tag : claims) {
            CompoundTag claim = (CompoundTag) tag;
            if (claim.getBoolean("current")) {
                continue;
            }
            Component claimName = claim.getString("nameKey").isBlank()
                    ? Component.literal(claim.getString("name"))
                    : Component.translatable(claim.getString("nameKey"));
            rows.add(y -> caption(label("claim_line", claim.getString("cycle"), claimName),
                    left + 7, y + 6, contentWidth));
            CompoundTag reward = claim.getCompound("reward");
            String mode = reward.getString("mode");
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            if (mode.equals("CHOICE")) {
                for (int index = 0; index < groups.size(); index++) {
                    int group = index;
                    rows.add(y -> {
                        caption(rewardText(reward, group), left + 7, y + 6, contentWidth - 78);
                        addButton(label("claim"), left + panelWidth - 79, y, 72, () -> claim(claim, group))
                                .active = pendingRequest.isEmpty();
                    });
                }
            } else {
                int group = mode.equals("WEIGHTED") ? claim.getInt("rewardGroup") : 0;
                rows.add(y -> {
                    caption(rewardText(reward, group), left + 7, y + 6, contentWidth - 78);
                    addButton(label("claim"), left + panelWidth - 79, y, 72, () -> claim(claim, group))
                            .active = pendingRequest.isEmpty();
                });
            }
        }
    }

    private void rewardRow(CompoundTag item) {
        rows.add(y -> {
            ResourceLocation id = ResourceLocation.tryParse(item.getString("item"));
            int count = item.getInt("count");
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id), Math.max(1, count));
                icons.add(new Icon(stack, left + 7, y + 2));
                caption(Component.literal(count + " × ").append(stack.getHoverName()),
                        left + 27, y + 6, contentWidth - 27);
            } else {
                caption(Component.literal(item.getString("item") + " × " + count), left + 7, y + 6, contentWidth);
            }
        });
    }

    private Component targetText(CompoundTag target) {
        String kind = target.getString("kind");
        String resource = target.getString("resource");
        String suffix = resource.isEmpty() ? "" : " " + resource;
        return Component.translatable(KEY_PREFIX + "kind." + kind.toLowerCase(Locale.ROOT))
                .append(Component.literal(suffix + "  " + progressText(target) + " / "
                        + target.getInt("amount")));
    }

    private static String progressText(CompoundTag target) {
        double value = target.getDouble("progress");
        if (target.getBoolean("fractional")) {
            return number(value);
        }
        return Long.toString((long) Math.floor(value));
    }

    private static String number(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.isEmpty() ? "0" : text;
    }

    private Component rewardText(CompoundTag reward, int group) {
        String mode = reward.getString("mode");
        ListTag items = mode.equals("FIXED")
                ? reward.getList("items", Tag.TAG_COMPOUND)
                : reward.getList("groups", Tag.TAG_COMPOUND).getCompound(Mth.clamp(group, 0,
                Math.max(0, reward.getList("groups", Tag.TAG_COMPOUND).size() - 1)))
                .getList("items", Tag.TAG_COMPOUND);
        StringBuilder text = new StringBuilder();
        for (Tag tag : items) {
            CompoundTag item = (CompoundTag) tag;
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(item.getString("item")).append(" × ").append(item.getInt("count"));
        }
        return Component.literal(text.toString());
    }

    private void selectTask(CompoundTag task) {
        selectedTask = task.getString("id");
        selection = 0;
        scroll = 0;
        rebuild();
    }

    private void deliver(CompoundTag target) {
        CompoundTag task = selectedTaskTag();
        if (task == null) {
            return;
        }
        CompoundTag request = request("deliver");
        request.putString("cycle", snapshot.getString("cycle"));
        request.putString("task", task.getString("id"));
        request.putString("order", task.getString("token"));
        request.putString("target", target.getString("id"));
        request.putInt("amount", 0);
        submit(request);
    }

    private void claimCurrent() {
        CompoundTag task = selectedTaskTag();
        if (task == null) {
            return;
        }
        CompoundTag request = request("claim");
        request.putString("cycle", snapshot.getString("cycle"));
        request.putString("task", task.getString("id"));
        request.putString("order", task.getString("token"));
        request.putInt("selection", selection);
        submit(request);
    }

    private void claim(CompoundTag claim, int group) {
        CompoundTag request = request("claim");
        request.putString("cycle", claim.getString("cycle"));
        request.putString("task", claim.getString("task"));
        request.putString("order", claim.getString("order"));
        request.putInt("selection", group);
        submit(request);
    }

    private CompoundTag request(String action) {
        CompoundTag request = new CompoundTag();
        request.putString("action", action);
        request.putString("requestId", UUID.randomUUID().toString());
        request.putLong("definitionVersion", snapshot.getLong("definitionVersion"));
        return request;
    }

    private void submit(CompoundTag request) {
        if (!pendingRequest.isEmpty()) {
            return;
        }
        pendingRequest = request.getString("requestId");
        message = KEY_PREFIX + "pending";
        WeeklyNetwork.send(request);
        rebuild();
    }

    private void backToShop() {
        if (!pendingRequest.isEmpty()) {
            return;
        }
        CompoundTag request = new CompoundTag();
        request.putString("action", "open");
        request.putString("requestId", UUID.randomUUID().toString());
        pendingRequest = request.getString("requestId");
        ShopNetwork.send(request);
    }

    private void scrollBy(int direction) {
        int next = Mth.clamp(scroll + direction, 0, Math.max(0, rows.size() - capacity()));
        if (next != scroll) {
            scroll = next;
            rebuild();
        }
    }

    private int capacity() {
        return Math.max(1, (contentBottom - contentTop) / ROW_HEIGHT);
    }

    private CompoundTag selectedTaskTag() {
        for (Tag tag : list(snapshot, "tasks")) {
            CompoundTag task = (CompoundTag) tag;
            if (task.getString("id").equals(selectedTask)) {
                return task;
            }
        }
        return null;
    }

    private static boolean hasTask(CompoundTag snapshot, String id) {
        if (id.isEmpty()) {
            return false;
        }
        for (Tag tag : list(snapshot, "tasks")) {
            if (((CompoundTag) tag).getString("id").equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String firstTaskId(CompoundTag snapshot) {
        ListTag tasks = list(snapshot, "tasks");
        return tasks.isEmpty() ? "" : ((CompoundTag) tasks.getFirst()).getString("id");
    }

    private Component display(CompoundTag tag, String prefix) {
        String key = tag.getString(prefix + "NameKey");
        return key.isBlank() ? Component.literal(tag.getString(prefix + "Name")) : Component.translatable(key);
    }

    private Component displayDescription(CompoundTag tag, String prefix) {
        String key = tag.getString(prefix + "DescriptionKey");
        return key.isBlank() ? Component.literal(tag.getString(prefix + "Description"))
                : Component.translatable(key);
    }

    private Component text(String value) {
        if (value.isEmpty()) {
            return Component.empty();
        }
        return value.startsWith("rabbitquest.") ? Component.translatable(value) : Component.literal(value);
    }

    private void textRow(Component text) {
        rows.add(y -> caption(text, left + 7, y + 6, contentWidth));
    }

    private void caption(Component text, int x, int y, int size) {
        if (size <= 0) {
            return;
        }
        var lines = font.split(text, size);
        if (!lines.isEmpty()) {
            captions.add(new Caption(lines.getFirst(), x, y));
        }
    }

    private void icon(String id, int x, int y) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location != null && BuiltInRegistries.ITEM.containsKey(location)) {
            icons.add(new Icon(new ItemStack(BuiltInRegistries.ITEM.get(location)), x, y));
        }
    }

    private Button addButton(Component label, int x, int y, int size, Runnable click) {
        Component fitted = font.width(label) + 8 > size
                ? Component.literal(font.plainSubstrByWidth(label.getString(), Math.max(0, size - 8)))
                : label;
        Button button = addRenderableWidget(Button.builder(fitted, ignored -> {
            if (pendingRequest.isEmpty() || label.equals(label("close"))) {
                click.run();
            }
        }).bounds(x, y, Math.max(1, size), 20).build());
        button.active = pendingRequest.isEmpty() || label.equals(label("close"));
        if (fitted != label) {
            button.setTooltip(Tooltip.create(label));
        }
        return button;
    }

    private void rebuild() {
        if (minecraft != null) {
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0 && mouseY >= contentTop && mouseY < contentBottom) {
            scrollBy(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        super.renderBackground(gui, mouseX, mouseY, delta);
        gui.fill(left, top, left + panelWidth, top + panelHeight, PANEL_COLOR);
        gui.renderOutline(left, top, panelWidth, panelHeight, OUTLINE_COLOR);
        gui.fill(left + 5, contentTop - 5, left + panelWidth - 5, contentBottom + 5, 0xFFDAD6CA);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
        super.render(gui, mouseX, mouseY, delta);
        for (Caption caption : captions) {
            gui.drawString(font, caption.text(), caption.x(), caption.y(), TEXT_COLOR, false);
        }
        ItemStack tooltip = ItemStack.EMPTY;
        for (Icon icon : icons) {
            gui.renderItem(icon.stack(), icon.x(), icon.y());
            gui.renderItemDecorations(font, icon.stack(), icon.x(), icon.y());
            if (mouseX >= icon.x() && mouseX < icon.x() + 16 && mouseY >= icon.y() && mouseY < icon.y() + 16) {
                tooltip = icon.stack();
            }
        }
        if (!tooltip.isEmpty()) {
            gui.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private static ListTag list(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_COMPOUND);
    }

    private static Component label(String key, Object... args) {
        return Component.translatable(KEY_PREFIX + key, args);
    }

    private record Caption(FormattedCharSequence text, int x, int y) {}

    private record Icon(ItemStack stack, int x, int y) {}
}
