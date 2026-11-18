package com.tr4nce.rabbitquest.client;

import com.tr4nce.rabbitquest.network.EventNetwork;
import com.tr4nce.rabbitquest.network.ShopNetwork;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Player-facing timed events screen; shares the parchment layout with WeeklyScreen. */
final class EventScreen extends Screen {
    private static final String KEY_PREFIX = "rabbitquest.event.";
    private static final int ROW_HEIGHT = 22;
    private static final int TEXT_COLOR = 0xFF3F3F3F;
    private static final int PANEL_COLOR = 0xFFC6C1B4;
    private static final int OUTLINE_COLOR = 0xFF595449;

    private final List<Caption> captions = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final List<Consumer<Integer>> rows = new ArrayList<>();
    private final Map<String, Integer> stepSelections = new HashMap<>();
    private CompoundTag snapshot;
    private String message = "";
    private String pendingRequest = "";
    private String selectedEvent = "";
    private int completionSelection;
    private int scroll;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int contentWidth;

    EventScreen(CompoundTag snapshot) {
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
        String first = firstEventId(incoming);
        if (!hasEvent(incoming, selectedEvent)) {
            selectedEvent = first;
            completionSelection = 0;
            stepSelections.clear();
            scroll = 0;
        }
        rebuild();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - BookmarkTabs.GUTTER - 12, 470);
        panelHeight = Math.min(height - 12, 330);
        left = BookmarkTabs.GUTTER + (width - BookmarkTabs.GUTTER - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        contentTop = top + 32;
        contentBottom = top + panelHeight - 52;
        contentWidth = panelWidth - 18;
        captions.clear();
        icons.clear();
        rows.clear();

        BookmarkTabs.attach(QuestBookClient.Tab.EVENTS, left, top, pendingRequest.isEmpty(),
                this::addRenderableWidget);
        addButton(label("title"), left + 7, top + 6, 120, this::rebuild).active = false;
        addButton(label("refresh"), left + panelWidth - 73, top + 6, 66, EventClient::open)
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
        ListTag events = list(snapshot, "events");
        if (events.isEmpty()) {
            textRow(label("no_events"));
            return;
        }
        if (selectedEvent.isEmpty() || !hasEvent(snapshot, selectedEvent)) {
            selectedEvent = firstEventId(snapshot);
        }
        if (events.size() > 1) {
            textRow(label("events"));
            for (Tag tag : events) {
                CompoundTag event = (CompoundTag) tag;
                rows.add(y -> addButton(display(event, "event"), left + 7, y, contentWidth,
                        () -> selectEvent(event)).active = !event.getString("id").equals(selectedEvent)
                        && pendingRequest.isEmpty());
            }
        }
        CompoundTag event = selectedEventTag();
        if (event == null) {
            textRow(label("no_events"));
            return;
        }

        String phase = event.getString("phase");
        rows.add(y -> {
            icon(event.getString("icon"), left + 7, y + 2);
            caption(display(event, "event"), left + 27, y + 6, contentWidth - 27);
        });
        textRow(label("phase_line", label("phase." + phase.toLowerCase(Locale.ROOT))));
        Component description = displayDescription(event, "event");
        if (!description.getString().isEmpty()) {
            for (FormattedCharSequence line : font.split(description, contentWidth)) {
                rows.add(y -> captions.add(new Caption(line, left + 7, y + 6)));
            }
        }

        if (event.getBoolean("enrollmentRequired") && !event.getBoolean("enrolled")) {
            if ("ACTIVE".equals(phase)) {
                rows.add(y -> addButton(label("enroll"), left + 7, y, contentWidth,
                        () -> enroll(event)).active = pendingRequest.isEmpty());
            } else {
                textRow(label("enroll_required"));
            }
        }

        rows.add(y -> caption(label("steps"), left + 7, y + 6, contentWidth));
        for (Tag tag : list(event, "steps")) {
            CompoundTag step = (CompoundTag) tag;
            buildStepRows(event, step, phase);
        }

        if (event.getBoolean("hasCompletionReward")) {
            rows.add(y -> caption(label("completion_reward"), left + 7, y + 6, contentWidth));
            CompoundTag reward = event.getCompound("completionReward");
            String mode = reward.getString("mode");
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            if ("CHOICE".equals(mode) && groups.size() > 1 && event.getBoolean("completionClaimable")) {
                rows.add(y -> addButton(label("option", completionSelection + 1, groups.size()),
                        left + 7, y, contentWidth, () -> {
                            completionSelection = (completionSelection + 1) % groups.size();
                            rebuild();
                        }).active = pendingRequest.isEmpty());
            }
            completionSelection = Mth.clamp(completionSelection, 0, Math.max(0, groups.size() - 1));
            int shown = "WEIGHTED".equals(mode) && event.getInt("completionRewardGroup") >= 0
                    ? event.getInt("completionRewardGroup") : completionSelection;
            renderRewardItems(reward, shown);
            if (event.getBoolean("completionClaimable")) {
                rows.add(y -> addButton(label("claim_completion"), left + 7, y, contentWidth,
                        () -> claimCompletion(event)).active = pendingRequest.isEmpty());
            } else if (event.getBoolean("completionClaimed")) {
                textRow(label("claimed"));
            }
        }
    }

    private void buildStepRows(CompoundTag event, CompoundTag step, String phase) {
        int index = step.getInt("index") + 1;
        String stepId = step.getString("id");
        boolean unlocked = step.getBoolean("unlocked");
        boolean completed = step.getBoolean("completed");
        String visibility = step.getString("lockedVisibility");

        if (!unlocked && !completed && "HIDDEN".equals(visibility)) {
            textRow(label("step_hidden", index));
            return;
        }

        Component statusLabel = completed ? label("status.completed")
                : (unlocked ? label("status.unlocked") : label("status.locked"));
        rows.add(y -> caption(label("step_line", index, display(step, "step"), statusLabel),
                left + 7, y + 6, contentWidth));

        if (!unlocked && !completed) {
            String condKey = step.getString("unlockConditionKey");
            String cond = step.getString("unlockCondition");
            if (!condKey.isBlank() || !cond.isBlank()) {
                Component condText = condKey.isBlank() ? Component.literal(cond) : Component.translatable(condKey);
                textRow(label("unlock_line", condText));
            }
            if (!"DETAILS".equals(visibility)) {
                return;
            }
        }

        Component stepDesc = displayDescription(step, "step");
        if (!stepDesc.getString().isEmpty()) {
            for (FormattedCharSequence line : font.split(stepDesc, contentWidth)) {
                rows.add(y -> captions.add(new Caption(line, left + 7, y + 6)));
            }
        }

        boolean canDeliver = "ACTIVE".equals(phase) && unlocked && !completed;
        for (Tag targetTag : list(step, "targets")) {
            CompoundTag target = (CompoundTag) targetTag;
            String kind = target.getString("kind");
            if ("DELIVER".equals(kind) || "DELIVERY".equals(kind)) {
                rows.add(y -> {
                    caption(targetText(target), left + 7, y + 6, contentWidth - 78);
                    addButton(label("deliver"), left + panelWidth - 79, y, 72,
                            () -> deliver(event, step, target)).active = canDeliver && pendingRequest.isEmpty();
                });
            } else {
                rows.add(y -> caption(targetText(target), left + 7, y + 6, contentWidth));
            }
        }

        if (step.contains("reward", Tag.TAG_COMPOUND)) {
            CompoundTag reward = step.getCompound("reward");
            String mode = reward.getString("mode");
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            int sel = Mth.clamp(stepSelections.getOrDefault(stepId, 0), 0, Math.max(0, groups.size() - 1));
            if ("CHOICE".equals(mode) && groups.size() > 1 && step.getBoolean("claimable")) {
                rows.add(y -> addButton(label("option", sel + 1, groups.size()), left + 7, y,
                        contentWidth, () -> {
                            stepSelections.put(stepId, (sel + 1) % groups.size());
                            rebuild();
                        }).active = pendingRequest.isEmpty());
            }
            int shown = "WEIGHTED".equals(mode) && step.getInt("rewardGroup") >= 0
                    ? step.getInt("rewardGroup") : sel;
            renderRewardItems(reward, shown);
        }

        if (step.getBoolean("claimable")) {
            int sel = stepSelections.getOrDefault(stepId, 0);
            rows.add(y -> addButton(label("claim_step"), left + 7, y, contentWidth,
                    () -> claimStep(event, step, sel)).active = pendingRequest.isEmpty());
        } else if (step.getBoolean("claimed")) {
            textRow(label("claimed"));
        }
    }

    private void renderRewardItems(CompoundTag reward, int groupIndex) {
        String mode = reward.getString("mode");
        if ("CHOICE".equals(mode) || "WEIGHTED".equals(mode)) {
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            if (!groups.isEmpty()) {
                CompoundTag group = groups.getCompound(Mth.clamp(groupIndex, 0, groups.size() - 1));
                for (Tag item : group.getList("items", Tag.TAG_COMPOUND)) {
                    rewardRow((CompoundTag) item);
                }
            }
        } else {
            for (Tag item : reward.getList("items", Tag.TAG_COMPOUND)) {
                rewardRow((CompoundTag) item);
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
        return Component.translatable("rabbitquest.weekly.kind." + kind.toLowerCase(Locale.ROOT))
                .append(Component.literal(suffix + "  " + progressText(target) + " / "
                        + target.getInt("amount")));
    }

    private static String progressText(CompoundTag target) {
        double value = target.getDouble("progress");
        if (target.getBoolean("fractional")) {
            String text = String.format(Locale.ROOT, "%.2f", value)
                    .replaceAll("0+$", "").replaceAll("\\.$", "");
            return text.isEmpty() ? "0" : text;
        }
        return Long.toString((long) Math.floor(value));
    }

    private void selectEvent(CompoundTag event) {
        selectedEvent = event.getString("id");
        completionSelection = 0;
        stepSelections.clear();
        scroll = 0;
        rebuild();
    }

    private void enroll(CompoundTag event) {
        CompoundTag request = request("enroll");
        request.putString("event", event.getString("id"));
        submit(request);
    }

    private void deliver(CompoundTag event, CompoundTag step, CompoundTag target) {
        CompoundTag request = request("deliver");
        request.putString("event", event.getString("id"));
        request.putString("step", step.getString("id"));
        request.putString("target", target.getString("id"));
        request.putInt("amount", 0);
        submit(request);
    }

    private void claimStep(CompoundTag event, CompoundTag step, int selection) {
        CompoundTag request = request("claim_step");
        request.putString("event", event.getString("id"));
        request.putString("step", step.getString("id"));
        request.putInt("selection", selection);
        submit(request);
    }

    private void claimCompletion(CompoundTag event) {
        CompoundTag request = request("claim_completion");
        request.putString("event", event.getString("id"));
        request.putInt("selection", completionSelection);
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
        EventNetwork.send(request);
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

    private CompoundTag selectedEventTag() {
        for (Tag tag : list(snapshot, "events")) {
            CompoundTag event = (CompoundTag) tag;
            if (event.getString("id").equals(selectedEvent)) {
                return event;
            }
        }
        return null;
    }

    private static boolean hasEvent(CompoundTag snapshot, String id) {
        if (id.isEmpty()) {
            return false;
        }
        for (Tag tag : list(snapshot, "events")) {
            if (((CompoundTag) tag).getString("id").equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String firstEventId(CompoundTag snapshot) {
        ListTag events = list(snapshot, "events");
        return events.isEmpty() ? "" : ((CompoundTag) events.getFirst()).getString("id");
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
