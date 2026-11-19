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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private String pendingAction = "";
    private String selectedEvent = "";
    private int completionSelection;
    private int scroll;
    private int browseScroll;
    private boolean rewardsOnly;

    private String selectedStep = "";
    private String selectedStage;
    private boolean stageExpanded = true;
    private boolean eventDetail;
    private boolean showCompleted;
    private boolean showLocked;
    private int detailScroll;
    private boolean split;
    private Pane routePane;
    private Pane detailPane;
    private final List<Consumer<Integer>> detailRows = new ArrayList<>();
    private final Map<String, RouteNode> routeNodes = new HashMap<>();

    record Position(String selected, int scroll, int browseScroll, boolean rewardsOnly, String step,
                    String stage, boolean showCompleted, boolean showLocked, boolean stageExpanded,
                    boolean eventDetail, int detailScroll) {}

    private record Pane(List<Consumer<Integer>> rows, int x, int width) {}

    private record RouteNode(int x, int y, boolean completed, ListTag requires) {}
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
        Position position = QuestBookClient.eventPosition;
        if (position != null) {
            selectedEvent = position.selected();
            scroll = position.scroll();
            browseScroll = position.browseScroll();
            rewardsOnly = position.rewardsOnly();
            selectedStep = position.step();
            selectedStage = position.stage();
            showCompleted = position.showCompleted();
            showLocked = position.showLocked();
            stageExpanded = position.stageExpanded();
            eventDetail = position.eventDetail();
            detailScroll = position.detailScroll();
        }
    }

    @Override
    public void removed() {
        QuestBookClient.eventPosition = new Position(selectedEvent, scroll, browseScroll, rewardsOnly,
                selectedStep, selectedStage, showCompleted, showLocked, stageExpanded, eventDetail, detailScroll);
        super.removed();
    }

    void receive(CompoundTag incoming) {
        snapshot = incoming;
        String reply = incoming.getString("requestId");
        if (!pendingRequest.isEmpty() && reply.equals(pendingRequest)) {
            String action = pendingAction;
            pendingRequest = "";
            pendingAction = "";
            message = incoming.getString("message");
            if ((KEY_PREFIX + "result.ok").equals(message)) {
                if ("claim_completion".equals(action)) {
                    QuestBookClient.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.0F);
                    QuestBookClient.playSound(SoundEvents.PLAYER_LEVELUP, 0.5F, 1.0F);
                } else if ("claim_step".equals(action)) {
                    QuestBookClient.playSound(SoundEvents.PLAYER_LEVELUP, 0.5F, 1.2F);
                } else if ("deliver".equals(action) || "enroll".equals(action)) {
                    QuestBookClient.playSound(SoundEvents.BUNDLE_INSERT, 0.7F, 1.0F);
                }
            } else if (!message.isEmpty()) {
                QuestBookClient.playSound(SoundEvents.VILLAGER_NO, 0.4F, 1.0F);
            }
        }
        String first = firstEventId(incoming);
        if (!hasEvent(incoming, selectedEvent)) {
            selectedEvent = first;
            eventDetail = false;
            clearStepSelection();
            if (rewardsOnly) {
                browseScroll = 0;
            } else {
                scroll = 0;
            }
        }
        rebuild();
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - BookmarkTabs.GUTTER - 12, 650);
        panelHeight = Math.min(height - 12, 330);
        left = BookmarkTabs.GUTTER + (width - BookmarkTabs.GUTTER - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        contentTop = top + 54;
        contentBottom = top + panelHeight - 52;
        contentWidth = panelWidth - 18;
        CompoundTag event = selectedEventTag();
        split = !rewardsOnly && eventDetail && event != null;
        int routeWidth = split ? panelWidth / 2 : panelWidth;
        routePane = new Pane(rows, left + 7, routeWidth - 29);
        detailPane = split ? new Pane(detailRows, left + routeWidth + 7, panelWidth - routeWidth - 29) : routePane;
        if (split) {
            contentTop += ROW_HEIGHT;
        }
        captions.clear();
        icons.clear();
        rows.clear();
        routeNodes.clear();
        detailRows.clear();

        BookmarkTabs.attach(QuestBookClient.Tab.EVENTS, left, top, pendingRequest.isEmpty(),
                this::addRenderableWidget);
        addButton(label("title"), left + 7, top + 6, 120, this::rebuild).active = false;
        addButton(label("refresh"), left + panelWidth - 73, top + 6, 66, EventClient::open)
                .active = pendingRequest.isEmpty();
        addButton(label("close"), left + panelWidth - 80, top + panelHeight - 25, 73, this::onClose);

        int claimable = QuestBookClient.badge(QuestBookClient.Tab.EVENTS);
        Component rewardFilter = rewardsOnly ? Component.translatable("rabbitquest.rewards.back")
                : Component.translatable("rabbitquest.rewards.available", claimable);
        addButton(rewardFilter, left + 7, top + 29, panelWidth - 14, this::toggleRewards)
                .active = pendingRequest.isEmpty() && (rewardsOnly || claimable > 0);

        if (rewardsOnly) {
            buildRewardList();
        } else {
            buildRows();
        }
        scroll = drawPane(routePane, scroll, false);
        if (split) {
            detailScroll = drawPane(detailPane, detailScroll, true);
        }
        if (!message.isEmpty()) {
            caption(text(message), left + 7, top + panelHeight - 19, panelWidth - 90);
        }
    }

    private int drawPane(Pane pane, int current, boolean details) {
        int capacity = capacity();
        int count = pane.rows().size();
        int first = Mth.clamp(current, 0, Math.max(0, count - capacity));
        for (int index = first; index < Math.min(first + capacity, count); index++) {
            pane.rows().get(index).accept(contentTop + (index - first) * ROW_HEIGHT);
        }
        if (count > capacity) {
            int x = pane.x() + pane.width() + 2;
            addButton(label("up"), x, contentTop, 14, () -> scrollBy(-1, details))
                    .active = first > 0 && pendingRequest.isEmpty();
            addButton(label("down"), x, contentTop + ROW_HEIGHT, 14, () -> scrollBy(1, details))
                    .active = first + capacity < count && pendingRequest.isEmpty();
        }
        return first;
    }

    private void toggleRewards() {
        if (rewardsOnly) {
            scroll = browseScroll;
        } else {
            browseScroll = scroll;
            scroll = 0;
        }
        rewardsOnly = !rewardsOnly;
        rebuild();
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
        if (!eventDetail) {
            textRow(label("events"));
            for (Tag tag : events) {
                CompoundTag event = (CompoundTag) tag;
                ListTag steps = list(event, "steps");
                long completed = steps.stream().filter(step -> ((CompoundTag) step).getBoolean("completed")).count();
                Component entry = label("route_event_entry", display(event, "event"), completed, steps.size());
                rows.add(y -> addButton(entry, left + 7, y, contentWidth - 16, () -> selectEvent(event)));
            }
            return;
        }
        CompoundTag event = selectedEventTag();
        if (event == null) {
            textRow(label("no_events"));
            return;
        }

        int backWidth = Math.min(120, Math.max(64, contentWidth / 3));
        addButton(label("route_back"), left + 7, contentTop - ROW_HEIGHT, backWidth, () -> {
            eventDetail = false;
            scroll = 0;
            rebuild();
        });
        String phase = event.getString("phase");
        Component header = display(event, "event").copy().append(" · ")
                .append(label("phase." + phase.toLowerCase(Locale.ROOT)));
        caption(header, left + backWidth + 14, contentTop - ROW_HEIGHT + 6, panelWidth - backWidth - 27);

        if (event.getBoolean("enrollmentRequired") && !event.getBoolean("enrolled")) {
            if ("ACTIVE".equals(phase)) {
                detailPane.rows().add(y -> addButton(label("enroll"), detailPane.x(), y, detailPane.width(),
                        () -> enroll(event)).active = pendingRequest.isEmpty());
            } else {
                textRow(detailPane, label("enroll_required"));
            }
        }

        buildSteps(event, phase);
        buildCompletionReward(detailPane, event);
        Component description = displayDescription(event, "event");
        if (!description.getString().isBlank()) {
            wrappedText(detailPane, description);
        }
    }

    private void buildSteps(CompoundTag event, String phase) {
        List<CompoundTag> steps = list(event, "steps").stream().map(tag -> (CompoundTag) tag).toList();
        if (steps.isEmpty()) {
            return;
        }

        Map<String, CompoundTag> byId = new LinkedHashMap<>();
        for (CompoundTag step : steps) {
            byId.put(step.getString("id"), step);
        }
        CompoundTag selected = byId.get(selectedStep);
        if (selected == null) {
            selected = steps.stream().filter(EventScreen::actionable).findFirst().orElse(steps.getFirst());
            selectedStep = selected.getString("id");
        }
        long completed = steps.stream().filter(step -> step.getBoolean("completed")).count();
        textRow(routePane, label("route_progress", completed, steps.size()));

        Map<Integer, List<CompoundTag>> levels = new LinkedHashMap<>();
        boolean drawRoute = steps.size() <= 6 && steps.stream().noneMatch(step -> !step.getString("stage").isBlank());
        if (drawRoute) {
            Map<String, Integer> ranks = new HashMap<>();
            for (CompoundTag step : steps) {
                int level = routeLevel(step, byId, ranks);
                levels.computeIfAbsent(level, ignored -> new ArrayList<>()).add(step);
            }
            drawRoute = levels.values().stream().allMatch(level -> level.size() <= 3
                    && routePane.width() / level.size() >= 43);
        }
        if (drawRoute) {
            buildRoute(levels);
        } else {
            buildStages(steps, selected);
        }


        buildStepRows(detailPane, event, selected, phase, byId);
    }

    private void buildRoute(Map<Integer, List<CompoundTag>> levels) {
        int usable = routePane.width();
        for (int level = 0; level < levels.size(); level++) {
            List<CompoundTag> nodes = levels.get(level);
            if (level > 0) {
                rows.add(y -> {});
            }
            rows.add(y -> {
                int cell = usable / nodes.size();
                int size = Math.min(136, cell - 6);
                for (int index = 0; index < nodes.size(); index++) {
                    CompoundTag step = nodes.get(index);
                    String id = step.getString("id");
                    int center = routePane.x() + index * cell + cell / 2;
                    routeNodes.put(id, new RouteNode(center, y, step.getBoolean("completed"),
                            listStrings(step, "requires")));
                    Button button = addButton(stepSummary(step), center - size / 2, y, size,
                            () -> selectStep(step, false));
                    routeTooltip(button, step);
                }
            });
        }
    }

    private int routeLevel(CompoundTag step, Map<String, CompoundTag> byId, Map<String, Integer> ranks) {
        String id = step.getString("id");
        Integer known = ranks.get(id);
        if (known != null) {
            return known;
        }
        int rank = 0;
        for (Tag required : listStrings(step, "requires")) {
            CompoundTag dependency = byId.get(required.getAsString());
            if (dependency != null) {
                rank = Math.max(rank, routeLevel(dependency, byId, ranks) + 1);
            }
        }
        ranks.put(id, rank);
        return rank;
    }

    private void buildStages(List<CompoundTag> steps, CompoundTag selected) {
        Map<String, List<CompoundTag>> stages = new LinkedHashMap<>();
        for (CompoundTag step : steps) {
            stages.computeIfAbsent(step.getString("stage"), ignored -> new ArrayList<>()).add(step);
        }
        if (!stages.containsKey(selectedStage)) {
            selectedStage = selected.getString("stage");
        }
        for (var entry : stages.entrySet()) {
            String stage = entry.getKey();
            List<CompoundTag> members = entry.getValue();
            boolean expanded = stageExpanded && stage.equals(selectedStage);
            Component name = stage.isBlank() ? label("route_ungrouped") : Component.literal(stage);
            long done = members.stream().filter(step -> step.getBoolean("completed")).count();
            long available = members.stream().filter(EventScreen::actionable).count();
            Component heading = label(expanded ? "route_stage_open" : "route_stage_closed",
                    name, done, members.size(), available, members.size() - done - available);
            rows.add(y -> {
                Button button = addButton(heading, routePane.x(), y, routePane.width(), () -> {
                    selectedStage = stage;
                    stageExpanded = !expanded;
                    rebuild();
                });
                button.setTooltip(Tooltip.create(heading));
            });
            if (expanded) {
                buildStageSteps(members, done, members.size() - done - available);
            }
        }
    }

    private void buildStageSteps(List<CompoundTag> members, long done, long locked) {
        for (CompoundTag step : members) {
            if (actionable(step)) {
                stageStepRow(step);
            }
        }
        if (done > 0) {
            rows.add(y -> addButton(label(showCompleted ? "route_hide_done" : "route_show_done", done),
                    routePane.x() + 8, y, routePane.width() - 8, () -> {
                        showCompleted = !showCompleted;
                        rebuild();
                    }));
            if (showCompleted) {
                members.stream().filter(step -> step.getBoolean("completed")).forEach(this::stageStepRow);
            }
        }
        if (locked > 0) {
            rows.add(y -> addButton(label(showLocked ? "route_hide_locked" : "route_show_locked", locked),
                    routePane.x() + 8, y, routePane.width() - 8, () -> {
                        showLocked = !showLocked;
                        rebuild();
                    }));
            if (showLocked) {
                members.stream().filter(step -> !step.getBoolean("completed") && !actionable(step))
                        .forEach(this::stageStepRow);
            }
        }
    }

    private void stageStepRow(CompoundTag step) {
        rows.add(y -> routeTooltip(addButton(stepSummary(step), routePane.x() + 8, y, routePane.width() - 8,
                () -> selectStep(step, false)), step));
    }

    private void routeTooltip(Button button, CompoundTag step) {
        button.setTooltip(Tooltip.create(stepSummary(step)));
        button.active = pendingRequest.isEmpty() && !step.getString("id").equals(selectedStep);
    }

    private Component stepSummary(CompoundTag step) {
        String status = step.getBoolean("completed") ? "completed"
                : actionable(step) ? "unlocked" : "locked";
        ListTag targets = list(step, "targets");
        Component state = label("status." + status);
        if (actionable(step) && targets.size() == 1) {
            state = Component.literal(progressText(targets.getCompound(0)) + " / " + targets.getCompound(0).getInt("amount"));
        } else if (actionable(step) && targets.size() > 1) {
            long completed = targets.stream().map(tag -> (CompoundTag) tag)
                    .filter(target -> target.getDouble("progress") + 1.0E-9 >= target.getInt("amount")).count();
            state = label("route_goal_progress", completed, targets.size());
        }
        return label("route_node", state, stepName(step));
    }

    private Component stepName(CompoundTag step) {
        if (step.getString("stepName").isEmpty() && step.getString("stepNameKey").isEmpty()) {
            return label("step_hidden", step.getInt("index") + 1);
        }
        return display(step, "step");
    }

    private static boolean actionable(CompoundTag step) {
        return step.getBoolean("unlocked") && !step.getBoolean("completed");
    }

    private static ListTag listStrings(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_STRING);
    }

    private void clearStepSelection() {
        selectedStep = "";
        detailScroll = 0;
        selectedStage = null;
        stageExpanded = true;
        showCompleted = false;
        showLocked = false;
        completionSelection = 0;
        stepSelections.clear();
    }

    private void selectStep(CompoundTag step, boolean jump) {
        selectedStep = step.getString("id");
        selectedStage = step.getString("stage");
        stageExpanded = true;
        if (jump) {
            showCompleted |= step.getBoolean("completed");
            showLocked |= !step.getBoolean("unlocked") && !step.getBoolean("completed");
        }
        detailScroll = 0;
        rebuild();
    }

    private void buildRewardList() {
        for (Tag eventTag : list(snapshot, "events")) {
            CompoundTag event = (CompoundTag) eventTag;
            int firstReward = rows.size();
            for (Tag stepTag : list(event, "steps")) {
                CompoundTag step = (CompoundTag) stepTag;
                if (step.getBoolean("claimable")) {
                    textRow(display(step, "step"));
                    buildStepReward(routePane, event, step);
                }
            }
            if (event.getBoolean("completionClaimable")) {
                buildCompletionReward(routePane, event);
            }
            if (rows.size() > firstReward) {
                rows.add(firstReward, y -> caption(display(event, "event"), left + 7, y + 6, contentWidth));
            }
        }
        if (rows.isEmpty()) {
            textRow(Component.translatable("rabbitquest.rewards.empty"));
        }
    }

    private void buildCompletionReward(Pane pane, CompoundTag event) {
        if (event.getBoolean("hasCompletionReward")) {
            pane.rows().add(y -> caption(label("completion_reward"), pane.x(), y + 6, pane.width()));
            CompoundTag reward = event.getCompound("completionReward");
            String mode = reward.getString("mode");
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            boolean claimable = event.getBoolean("completionClaimable");
            if ("CHOICE".equals(mode)) {
                for (int i = 0; i < groups.size(); i++) {
                    int groupIndex = i;
                    pane.rows().add(y -> {
                        caption(label("option_label", groupIndex + 1), pane.x(), y + 6, pane.width() - 78);
                        if (claimable) {
                            addButton(label("claim_completion"), pane.x() + pane.width() - 72, y, 72,
                                    () -> claimCompletionGroup(event, groupIndex)).active = pendingRequest.isEmpty();
                        }
                    });
                    renderRewardItems(pane, reward, groupIndex);
                }
                if (!claimable && event.getBoolean("completionClaimed")) {
                    textRow(pane, label("claimed"));
                }
            } else {
                int shown = "WEIGHTED".equals(mode) && event.getInt("completionRewardGroup") >= 0
                        ? event.getInt("completionRewardGroup") : 0;
                renderRewardItems(pane, reward, shown);
                if (claimable) {
                    pane.rows().add(y -> addButton(label("claim_completion"), pane.x(), y, pane.width(),
                            () -> claimCompletionGroup(event, 0)).active = pendingRequest.isEmpty());
                } else if (event.getBoolean("completionClaimed")) {
                    textRow(pane, label("claimed"));
                }
            }
        }
    }

    private void buildStepRows(Pane pane, CompoundTag event, CompoundTag step, String phase,
                               Map<String, CompoundTag> steps) {
        int index = step.getInt("index") + 1;
        boolean unlocked = step.getBoolean("unlocked");
        boolean completed = step.getBoolean("completed");
        String visibility = step.getString("lockedVisibility");

        if (!unlocked && !completed && "HIDDEN".equals(visibility)) {
            textRow(pane, label("step_hidden", index));
            return;
        }

        Component statusLabel = completed ? label("status.completed")
                : (unlocked ? label("status.unlocked") : label("status.locked"));
        wrappedText(pane, label("route_node", statusLabel, stepName(step)));

        if (!unlocked && !completed) {
            for (Tag required : listStrings(step, "requires")) {
                CompoundTag prerequisite = steps.get(required.getAsString());
                if (prerequisite != null && !prerequisite.getBoolean("completed")) {
                    pane.rows().add(y -> addButton(label("route_requires", stepName(prerequisite)),
                            pane.x(), y, pane.width(), () -> selectStep(prerequisite, true)));
                }
            }
            String condKey = step.getString("unlockConditionKey");
            String cond = step.getString("unlockCondition");
            if (!condKey.isBlank() || !cond.isBlank()) {
                Component condText = condKey.isBlank() ? Component.literal(cond) : Component.translatable(condKey);
                textRow(pane, label("unlock_line", condText));
            }
            if (!"DETAILS".equals(visibility)) {
                return;
            }
        }

        Component stepDesc = displayDescription(step, "step");
        if (!stepDesc.getString().isEmpty()) {
            for (FormattedCharSequence line : font.split(stepDesc, pane.width())) {
                pane.rows().add(y -> captions.add(new Caption(line, pane.x(), y + 6)));
            }
        }

        boolean canDeliver = "ACTIVE".equals(phase) && unlocked && !completed;
        if (step.getBoolean("click") && canDeliver) {
            pane.rows().add(y -> addButton(label("complete_step"), pane.x(), y, pane.width(), () -> {
                CompoundTag request = request("click_step");
                request.putString("event", event.getString("id"));
                request.putString("step", step.getString("id"));
                submit(request);
            }));
        }
        for (Tag targetTag : list(step, "targets")) {
            CompoundTag target = (CompoundTag) targetTag;
            String kind = target.getString("kind");
            wrappedText(pane, targetText(target));
            if ("DELIVER".equals(kind) || "DELIVERY".equals(kind)) {
                pane.rows().add(y -> addButton(label("deliver"), pane.x(), y, pane.width(),
                        () -> deliver(event, step, target)).active = canDeliver && pendingRequest.isEmpty());
            }
        }

        buildStepReward(pane, event, step);
    }

    private void buildStepReward(Pane pane, CompoundTag event, CompoundTag step) {
        boolean isChoice = false;
        if (step.contains("reward", Tag.TAG_COMPOUND)) {
            CompoundTag reward = step.getCompound("reward");
            String mode = reward.getString("mode");
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            isChoice = "CHOICE".equals(mode);
            if (isChoice) {
                boolean stepClaimable = step.getBoolean("claimable");
                for (int i = 0; i < groups.size(); i++) {
                    int groupIndex = i;
                    pane.rows().add(y -> {
                        caption(label("option_label", groupIndex + 1), pane.x(), y + 6, pane.width() - 78);
                        if (stepClaimable) {
                            addButton(label("claim_step"), pane.x() + pane.width() - 72, y, 72,
                                    () -> claimStep(event, step, groupIndex)).active = pendingRequest.isEmpty();
                        }
                    });
                    renderRewardItems(pane, reward, groupIndex);
                }
            } else {
                int shown = "WEIGHTED".equals(mode) && step.getInt("rewardGroup") >= 0
                        ? step.getInt("rewardGroup") : 0;
                renderRewardItems(pane, reward, shown);
            }
        }

        if (!isChoice && step.getBoolean("claimable")) {
            pane.rows().add(y -> addButton(label("claim_step"), pane.x(), y, pane.width(),
                    () -> claimStep(event, step, 0)).active = pendingRequest.isEmpty());
        } else if (step.getBoolean("claimed")) {
            textRow(pane, label("claimed"));
        }
    }

    private void renderRewardItems(Pane pane, CompoundTag reward, int groupIndex) {
        String mode = reward.getString("mode");
        if ("CHOICE".equals(mode) || "WEIGHTED".equals(mode)) {
            ListTag groups = reward.getList("groups", Tag.TAG_COMPOUND);
            if (!groups.isEmpty()) {
                CompoundTag group = groups.getCompound(Mth.clamp(groupIndex, 0, groups.size() - 1));
                for (Tag item : group.getList("items", Tag.TAG_COMPOUND)) {
                    rewardRow(pane, (CompoundTag) item);
                }
            }
        } else {
            for (Tag item : reward.getList("items", Tag.TAG_COMPOUND)) {
                rewardRow(pane, (CompoundTag) item);
            }
        }
    }

    private void rewardRow(Pane pane, CompoundTag item) {
        pane.rows().add(y -> {
            ResourceLocation id = ResourceLocation.tryParse(item.getString("item"));
            int count = item.getInt("count");
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id), Math.max(1, count));
                icons.add(new Icon(stack, pane.x(), y + 2));
                caption(Component.literal(count + " × ").append(stack.getHoverName()),
                        pane.x() + 20, y + 6, pane.width() - 20);
            } else {
                caption(Component.literal(item.getString("item") + " × " + count), pane.x(), y + 6, pane.width());
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
        String id = event.getString("id");
        if (!id.equals(selectedEvent)) {
            clearStepSelection();
        }
        selectedEvent = id;
        eventDetail = true;
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

    private void claimCompletionGroup(CompoundTag event, int groupIndex) {
        CompoundTag request = request("claim_completion");
        request.putString("event", event.getString("id"));
        request.putInt("selection", groupIndex);
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
        pendingAction = request.getString("action");
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

    private void scrollBy(int direction, boolean details) {
        Pane pane = details ? detailPane : routePane;
        int current = details ? detailScroll : scroll;
        int next = Mth.clamp(current + direction, 0, Math.max(0, pane.rows().size() - capacity()));
        if (next == current) {
            return;
        }
        if (details) {
            detailScroll = next;
        } else {
            scroll = next;
        }
        rebuild();
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
        textRow(routePane, text);
    }

    private void textRow(Pane pane, Component text) {
        pane.rows().add(y -> caption(text, pane.x(), y + 6, pane.width()));
    }

    private void wrappedText(Pane pane, Component text) {
        for (FormattedCharSequence line : font.split(text, pane.width())) {
            pane.rows().add(y -> captions.add(new Caption(line, pane.x(), y + 6)));
        }
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
            scrollBy(vertical > 0 ? -1 : 1, split && mouseX >= detailPane.x() - 7);
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
        if (split) {
            int divider = detailPane.x() - 7;
            gui.vLine(divider, contentTop - 4, contentBottom + 4, OUTLINE_COLOR);
        }
        for (RouteNode node : routeNodes.values()) {
            for (Tag required : node.requires()) {
                RouteNode parent = routeNodes.get(required.getAsString());
                if (parent == null) {
                    continue;
                }
                int startY = parent.y() + 20;
                int middleY = startY + (node.y() - startY) / 2;
                int color = parent.completed() ? OUTLINE_COLOR : 0xFF9E9788;
                gui.vLine(parent.x(), startY, middleY, color);
                gui.hLine(Math.min(parent.x(), node.x()), Math.max(parent.x(), node.x()), middleY, color);
                gui.vLine(node.x(), middleY, node.y(), color);
            }
        }
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
