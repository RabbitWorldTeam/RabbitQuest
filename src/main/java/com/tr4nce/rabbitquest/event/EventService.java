package com.tr4nce.rabbitquest.event;

import com.tr4nce.rabbitquest.block.RabbitChest;
import net.minecraft.core.BlockPos;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tr4nce.rabbitquest.network.EventNetwork;
import com.tr4nce.rabbitquest.network.QuestBookNetwork;
import com.tr4nce.rabbitquest.storage.InventorySnapshot;
import com.tr4nce.rabbitquest.storage.RecoveryService;
import com.tr4nce.rabbitquest.storage.TransactionJournal;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative timed event service. Definitions load once per server start; player enrollment,
 * step progress and reward claims are persisted in overworld SavedData.
 */
public final class EventService {
    private static final Logger LOGGER = LoggerFactory.getLogger("rabbitquest-event");
    private static final String RESULT_PREFIX = "rabbitquest.event.result.";
    private static final int ARRIVAL_SAMPLE_TICKS = 10;

    private record Runtime(MinecraftServer server, EventDefinitions definitions, EventSavedData data) {}

    private static volatile Runtime runtime;
    private static volatile String configError;
    private static volatile long definitionVersion = 1L;

    public static EventDefinitions activeDefinitions() {
        Runtime current = runtime;
        return current == null ? null : current.definitions();
    }

    public static void applyReload(EventDefinitions next, long nextVersion) {
        Runtime current = runtime;
        if (current == null || next == null) {
            return;
        }
        EventEngine.reconcileThresholds(next, current.data(), Instant.now());
        runtime = new Runtime(current.server(), next, current.data());
        definitionVersion = nextVersion;
        configError = null;
        for (ServerPlayer player : current.server().getPlayerList().getPlayers()) {
            send(player, false, "", "");
        }
    }

    private EventService() {}

    public static void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            EventDefinitions definitions = EventDefinitions.load(server.getResourceManager());
            EventSavedData data = EventSavedData.get(server);
            if (data == null) {
                throw new EventConfigException("Event SavedData could not be loaded");
            }
            runtime = new Runtime(server, definitions, data);
            configError = null;
            LOGGER.info("RabbitQuest events loaded: {} total, {} enabled",
                    definitions.events().size(), definitions.enabledEvents().size());
        } catch (RuntimeException exception) {
            runtime = null;
            configError = exception.getMessage();
            LOGGER.error("RabbitQuest event configuration rejected: {}", exception.getMessage(), exception);
        }
    }

    public static void serverStopped(ServerStoppedEvent event) {
        runtime = null;
        configError = null;
    }

    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, false, "", "");
        }
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> sub = eventActions(Commands.literal("event"));
        if (event.getDispatcher().getRoot().getChild("rabbitquest")
                instanceof com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> root) {
            root.addChild(sub.build());
        } else {
            event.getDispatcher().register(Commands.literal("rabbitquest").then(sub));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> eventActions(
            LiteralArgumentBuilder<CommandSourceStack> builder) {
        return builder
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    send(player, true, "", "");
                    return 1;
                })
                .then(Commands.literal("status").requires(source -> source.hasPermission(2))
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("query").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(context -> query(context.getSource(),
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("reset").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .then(Commands.argument("event", StringArgumentType.string())
                                        .executes(context -> reset(context.getSource(),
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "event"))))))
                .then(Commands.literal("reissue").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", StringArgumentType.string())
                                .then(Commands.argument("event", StringArgumentType.string())
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> reissue(context.getSource(),
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "event"),
                                                        StringArgumentType.getString(context, "target")))))));
    }

    public static void handle(ServerPlayer player, CompoundTag request) {
        Runtime current = runtime;
        String action = request.getString("action");
        String requestId = request.getString("requestId");
        if (current == null) {
            sendError(player, request.getBoolean("open"), isRequestId(requestId) ? requestId : "");
            return;
        }
        if (!action.equals("open") && request.contains("definitionVersion")
                && request.getLong("definitionVersion") != definitionVersion) {
            send(player, false, RESULT_PREFIX + "stale", isRequestId(requestId) ? requestId : "");
            return;
        }
        switch (action) {
            case "open" -> send(player, true, "", isRequestId(requestId) ? requestId : "");
            case "enroll" -> enroll(current, player, request, requestId);
            case "deliver" -> deliver(current, player, request, requestId);
            case "claim_step" -> claimStep(current, player, request, requestId);
            case "claim_completion" -> claimCompletion(current, player, request, requestId);
            default -> send(player, false, RESULT_PREFIX + "invalid", isRequestId(requestId) ? requestId : "");
        }
    }

    /**
     * Authoritative event entry point for gameplay and custom triggers. Only unlocked steps in
     * active, participating events advance.
     */
    public static int recordEvent(ServerPlayer player, TargetKind kind, ResourceLocation resource, double amount,
                                  ResourceKey<Level> dimension, Vec3 position) {
        Runtime current = runtime;
        if (current == null || player == null || kind == null || !(amount > 0.0) || !Double.isFinite(amount)) {
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server != current.server() || server.getRunningThread() != Thread.currentThread()) {
            return 0;
        }
        CompletionState before = captureCompletionState(current, player.getUUID());
        int added = EventEngine.record(current.data(), player.getUUID(), current.definitions(), Instant.now(),
                kind, resource, amount, dimension == null ? null : dimension.location(), position,
                player.getInventory().items);
        if (added > 0) {
            if (kind == TargetKind.DELIVER) {
                player.getInventory().setChanged();
                player.containerMenu.broadcastChanges();
            }
            notifyEventCompletions(current, player, before, true);
        }
        return added;
    }

    public static int recordEvent(ServerPlayer player, TargetKind kind, ResourceLocation resource, double amount) {
        if (player == null) {
            return 0;
        }
        return recordEvent(player, kind, resource, amount, player.level().dimension(), player.position());
    }

    public static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Runtime current = runtime;
        if (current == null || player.tickCount % ARRIVAL_SAMPLE_TICKS != 0) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != current.server() || server.getRunningThread() != Thread.currentThread()) {
            return;
        }
        CompletionState before = captureCompletionState(current, player.getUUID());
        int added = EventEngine.recordArrival(current.data(), player.getUUID(), current.definitions(),
                Instant.now(), player.level().dimension().location(), player.position());
        if (added > 0) {
            notifyEventCompletions(current, player, before, false);
            send(player, false, "", "");
        }
    }

    private static void enroll(Runtime current, ServerPlayer player, CompoundTag request, String requestId) {
        if (!isRequestId(requestId)) {
            send(player, false, RESULT_PREFIX + "invalid", "");
            return;
        }
        ResourceLocation eventId = ResourceLocation.tryParse(request.getString("event"));
        EventEngine.ActionResult result = EventEngine.enroll(current.data(), player.getUUID(),
                current.definitions(), eventId, requestId, Instant.now());
        send(player, false, RESULT_PREFIX + result.outcome(), requestId);
    }

    private static void deliver(Runtime current, ServerPlayer player, CompoundTag request, String requestId) {
        if (!isRequestId(requestId)) {
            send(player, false, RESULT_PREFIX + "invalid", "");
            return;
        }
        ResourceLocation eventId = ResourceLocation.tryParse(request.getString("event"));
        ResourceLocation stepId = ResourceLocation.tryParse(request.getString("step"));
        ResourceLocation targetId = ResourceLocation.tryParse(request.getString("target"));
        CompletionState before = captureCompletionState(current, player.getUUID());
        TransactionJournal wal = RecoveryService.journal();
        String beforeHash = wal == null ? ""
                : InventorySnapshot.fingerprint(player.getInventory().items, player.registryAccess());
        EventEngine.DeliverResult result = EventEngine.deliver(current.data(), player.getUUID(),
                current.definitions(), Instant.now(), requestId, eventId, stepId, targetId,
                request.getInt("amount"), player.level().dimension().location(), player.position(),
                player.getInventory().items);
        if (result.outcome().equals("ok")) {
            if (wal != null && eventId != null) {
                recordWal(wal, requestId, "DELIVER", player, beforeHash, eventId, current.data());
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            notifyEventCompletions(current, player, before, false);
        }
        send(player, false, RESULT_PREFIX + result.outcome(), requestId);
    }

    private record CompletionState(Map<ResourceLocation, Set<ResourceLocation>> steps,
                                   Set<ResourceLocation> events) {}

    private static CompletionState captureCompletionState(Runtime current, UUID playerId) {
        Map<ResourceLocation, Set<ResourceLocation>> steps = new HashMap<>();
        Set<ResourceLocation> events = new HashSet<>();
        for (TimedEventDefinition event : current.definitions().enabledEvents()) {
            CompoundTag eventTag = current.data().event(playerId, event.id());
            CompoundTag stepsTag = eventTag.getCompound("steps");
            Set<ResourceLocation> completedSteps = new HashSet<>();
            boolean allCompleted = !event.steps().isEmpty();
            for (EventStep step : event.steps()) {
                if (stepsTag.getCompound(step.id().toString()).getBoolean("completed")) {
                    completedSteps.add(step.id());
                } else {
                    allCompleted = false;
                }
            }
            steps.put(event.id(), completedSteps);
            if (allCompleted) {
                events.add(event.id());
            }
        }
        return new CompletionState(steps, events);
    }

    private static void notifyEventCompletions(Runtime current, ServerPlayer player, CompletionState before,
                                               boolean pushSnapshot) {
        boolean anyNew = false;
        for (TimedEventDefinition event : current.definitions().enabledEvents()) {
            CompoundTag eventTag = current.data().event(player.getUUID(), event.id());
            CompoundTag stepsTag = eventTag.getCompound("steps");
            Set<ResourceLocation> beforeSteps = before.steps().getOrDefault(event.id(), Set.of());
            boolean allCompleted = !event.steps().isEmpty();
            for (EventStep step : event.steps()) {
                boolean completed = stepsTag.getCompound(step.id().toString()).getBoolean("completed");
                if (!completed) {
                    allCompleted = false;
                } else if (!beforeSteps.contains(step.id())) {
                    QuestBookNetwork.sendToast(player, "rabbitquest.toast.event_step_complete",
                            step.name(), step.nameKey(), event.icon().toString());
                    anyNew = true;
                }
            }
            if (allCompleted && !before.events().contains(event.id())) {
                QuestBookNetwork.sendToast(player, "rabbitquest.toast.event_complete",
                        event.name(), event.nameKey(), event.icon().toString());
                anyNew = true;
            }
        }
        if (anyNew && pushSnapshot) {
            send(player, false, "", "");
        }
    }

    private static void claimStep(Runtime current, ServerPlayer player, CompoundTag request, String requestId) {
        if (!isRequestId(requestId)) {
            send(player, false, RESULT_PREFIX + "invalid", "");
            return;
        }
        ResourceLocation eventId = ResourceLocation.tryParse(request.getString("event"));
        ResourceLocation stepId = ResourceLocation.tryParse(request.getString("step"));
        TransactionJournal wal = RecoveryService.journal();
        String beforeHash = wal == null ? ""
                : InventorySnapshot.fingerprint(player.getInventory().items, player.registryAccess());
        EventEngine.ActionResult result = EventEngine.claimStep(current.data(), player.getUUID(),
                current.definitions(), Instant.now(), requestId, eventId, stepId,
                request.getInt("selection"), player.getInventory().items);
        if (result.outcome().equals("ok")) {
            if (wal != null && eventId != null) {
                recordWal(wal, requestId, "CLAIM_STEP", player, beforeHash, eventId, current.data());
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        send(player, false, RESULT_PREFIX + result.outcome(), requestId);
    }

    private static void claimCompletion(Runtime current, ServerPlayer player, CompoundTag request, String requestId) {
        if (!isRequestId(requestId)) {
            send(player, false, RESULT_PREFIX + "invalid", "");
            return;
        }
        ResourceLocation eventId = ResourceLocation.tryParse(request.getString("event"));
        TransactionJournal wal = RecoveryService.journal();
        String beforeHash = wal == null ? ""
                : InventorySnapshot.fingerprint(player.getInventory().items, player.registryAccess());
        EventEngine.ActionResult result = EventEngine.claimCompletion(current.data(), player.getUUID(),
                current.definitions(), Instant.now(), requestId, eventId,
                request.getInt("selection"), player.getInventory().items);
        if (result.outcome().equals("ok")) {
            if (wal != null && eventId != null) {
                recordWal(wal, requestId, "CLAIM_COMPLETION", player, beforeHash, eventId, current.data());
            }
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        send(player, false, RESULT_PREFIX + result.outcome(), requestId);
    }

    private static void send(ServerPlayer player, boolean open, String message, String requestId) {
        Runtime current = runtime;
        if (current == null) {
            sendError(player, open, requestId);
            return;
        }
        EventNetwork.send(player, snapshot(current, player.getUUID(), player.getGameProfile().getName(),
                open, message, requestId, false));
    }

    private static void sendError(ServerPlayer player, boolean open, String requestId) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("type", "event_snapshot");
        snapshot.putBoolean("open", open);
        snapshot.putBoolean("adminView", false);
        snapshot.putString("error", configError == null ? "rabbitquest.event.error.unavailable" : configError);
        if (!requestId.isEmpty()) {
            snapshot.putString("requestId", requestId);
        }
        EventNetwork.send(player, snapshot);
    }

    static CompoundTag snapshot(Runtime current, UUID playerId, String playerName, boolean open,
                                String message, String requestId, boolean adminView) {
        Instant now = Instant.now();
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("type", "event_snapshot");
        snapshot.putLong("definitionVersion", definitionVersion);
        snapshot.putBoolean("open", open);
        snapshot.putBoolean("adminView", adminView);
        snapshot.putString("player", playerName);
        snapshot.putString("playerId", playerId.toString());

        ListTag eventList = new ListTag();
        for (TimedEventDefinition event : current.definitions().enabledEvents()) {
            if (!adminView && !event.visibleAt(now)) {
                continue;
            }
            eventList.add(eventSnapshot(current.data(), playerId, event, now));
        }
        snapshot.put("events", eventList);
        if (!message.isEmpty()) {
            snapshot.putString("message", message);
        }
        if (!requestId.isEmpty()) {
            snapshot.putString("requestId", requestId);
        }
        return snapshot;
    }

    public static void chestTargets(ServerPlayer player, BlockPos pos, ListTag rows) {
        Runtime current = runtime;
        if (current == null || current.server() != player.getServer()) {
            return;
        }
        Instant now = Instant.now();
        for (TimedEventDefinition event : current.definitions().enabledEvents()) {
            CompoundTag data = current.data().event(player.getUUID(), event.id());
            for (int index = 0; index < event.steps().size(); index++) {
                EventStep step = event.steps().get(index);
                CompoundTag progress = data.getCompound("steps").getCompound(step.id().toString())
                        .getCompound("progress");
                Component title = Component.translatable("rabbitquest.chest.events").append(" / ")
                        .append(RabbitChest.title(event.name(), event.nameKey())).append(" / ")
                        .append(RabbitChest.title(step.name(), step.nameKey()));
                for (WeeklyTarget target : step.targets()) {
                    String status;
                    if (progress(target, progress) >= target.amount()) {
                        status = "counted";
                    } else if (event.phase(now) != TimedEventDefinition.Phase.ACTIVE) {
                        status = event.phase(now).name().toLowerCase(java.util.Locale.ROOT);
                    } else if (!EventEngine.isParticipating(event, data, now)) {
                        status = "not_enrolled";
                    } else {
                        status = EventEngine.isStepUnlocked(event, data, index, now) ? "ready" : "locked";
                    }
                    RabbitChest.addTarget(player, pos, rows, target, title, status);
                }
            }
        }
    }

    private static CompoundTag eventSnapshot(EventSavedData data, UUID playerId, TimedEventDefinition event,
                                             Instant now) {
        CompoundTag eventTag = data.event(playerId, event.id());
        TimedEventDefinition.Phase phase = event.phase(now);
        boolean participating = EventEngine.isParticipating(event, eventTag, now);

        CompoundTag row = new CompoundTag();
        row.putString("id", event.id().toString());
        putDisplay(row, "event", event.name(), event.nameKey(), event.description(), event.descriptionKey());
        row.putString("icon", event.icon().toString());
        row.putString("phase", phase.name());
        row.putBoolean("enrollmentRequired", event.enrollmentRequired());
        row.putBoolean("enrolled", eventTag.getBoolean("enrolled"));
        row.putBoolean("participating", participating);
        if (event.previewAt() != null) {
            row.putString("previewAt", event.previewAt().toString());
        }
        row.putString("startAt", event.startAt().toString());
        row.putString("endAt", event.endAt().toString());
        row.putString("claimEndAt", event.claimEndAt().toString());

        CompoundTag stepsTag = eventTag.getCompound("steps");
        ListTag steps = new ListTag();
        boolean allCompleted = true;
        for (int index = 0; index < event.steps().size(); index++) {
            EventStep step = event.steps().get(index);
            CompoundTag stepTag = stepsTag.getCompound(step.id().toString());
            boolean completed = stepTag.getBoolean("completed");
            if (!completed) {
                allCompleted = false;
            }
            boolean unlocked = EventEngine.isStepUnlocked(event, eventTag, index, now);
            boolean claimed = stepTag.getBoolean("claimed");
            boolean claimable = completed && !claimed
                    && (phase == TimedEventDefinition.Phase.ACTIVE || phase == TimedEventDefinition.Phase.CLAIM_ONLY);

            CompoundTag stepRow = new CompoundTag();
            stepRow.putInt("index", index);
            stepRow.putString("id", step.id().toString());
            stepRow.putBoolean("unlocked", unlocked);
            stepRow.putBoolean("completed", completed);
            stepRow.putBoolean("claimed", claimed);
            stepRow.putBoolean("claimable", claimable);
            stepRow.putString("lockedVisibility", step.lockedVisibility().name());

            boolean revealDetails = unlocked || completed
                    || step.lockedVisibility() == EventStep.LockedVisibility.DETAILS;
            boolean revealTitle = revealDetails
                    || step.lockedVisibility() == EventStep.LockedVisibility.TITLE_AND_CONDITION;

            if (revealTitle) {
                putDisplay(stepRow, "step", step.name(), step.nameKey(),
                        revealDetails ? step.description() : "",
                        revealDetails ? step.descriptionKey() : "");
                stepRow.putString("unlockCondition", step.unlockCondition() == null ? "" : step.unlockCondition());
                stepRow.putString("unlockConditionKey",
                        step.unlockConditionKey() == null ? "" : step.unlockConditionKey());
            }

            if (revealDetails) {
                CompoundTag progress = stepTag.getCompound("progress");
                ListTag targets = new ListTag();
                for (WeeklyTarget target : step.targets()) {
                    CompoundTag targetTag = new CompoundTag();
                    targetTag.putString("id", target.id().toString());
                    targetTag.putString("kind", target.kind().name());
                    if (target.resource() != null) {
                        targetTag.putString("resource", target.resource().toString());
                    }
                    targetTag.putInt("amount", target.amount());
                    targetTag.putDouble("progress", progress(target, progress));
                    targetTag.putBoolean("fractional", target.fractional());
                    if (target.dimension() != null) {
                        targetTag.putString("dimension", target.dimension().toString());
                    }
                    if (target.region() != null) {
                        targetTag.put("region", target.region().save());
                    }
                    targets.add(targetTag);
                }
                stepRow.put("targets", targets);
                if (step.reward() != null) {
                    CompoundTag reward = stepTag.contains("reward", Tag.TAG_COMPOUND)
                            ? stepTag.getCompound("reward").copy() : step.reward().save();
                    stepRow.put("reward", reward);
                    stepRow.putString("rewardMode", reward.getString("mode"));
                    stepRow.putInt("selection", stepTag.contains("selection") ? stepTag.getInt("selection") : 0);
                    stepRow.putInt("rewardGroup",
                            stepTag.contains("rewardGroup") ? stepTag.getInt("rewardGroup") : -1);
                }
            }
            steps.add(stepRow);
        }
        row.put("steps", steps);
        row.putBoolean("completed", allCompleted);

        if (event.completionReward() != null) {
            CompoundTag completionTag = eventTag.getCompound("completion");
            boolean compCompleted = completionTag.getBoolean("completed");
            boolean compClaimed = completionTag.getBoolean("claimed");
            boolean compClaimable = compCompleted && !compClaimed
                    && (phase == TimedEventDefinition.Phase.ACTIVE || phase == TimedEventDefinition.Phase.CLAIM_ONLY);
            row.putBoolean("hasCompletionReward", true);
            row.putBoolean("completionCompleted", compCompleted);
            row.putBoolean("completionClaimed", compClaimed);
            row.putBoolean("completionClaimable", compClaimable);
            CompoundTag reward = completionTag.contains("reward", Tag.TAG_COMPOUND)
                    ? completionTag.getCompound("reward").copy() : event.completionReward().save();
            row.put("completionReward", reward);
            row.putString("completionRewardMode", reward.getString("mode"));
            row.putInt("completionSelection",
                    completionTag.contains("selection") ? completionTag.getInt("selection") : 0);
            row.putInt("completionRewardGroup",
                    completionTag.contains("rewardGroup") ? completionTag.getInt("rewardGroup") : -1);
        }
        return row;
    }

    private static double progress(WeeklyTarget target, CompoundTag progress) {
        Tag tag = progress.get(target.id().toString());
        return tag instanceof NumericTag number ? number.getAsDouble() : 0.0;
    }

    private static void putDisplay(CompoundTag snapshot, String prefix, String name, String nameKey,
                                   String description, String descriptionKey) {
        snapshot.putString(prefix + "Name", name == null ? "" : name);
        snapshot.putString(prefix + "NameKey", nameKey == null ? "" : nameKey);
        snapshot.putString(prefix + "Description", description == null ? "" : description);
        snapshot.putString(prefix + "DescriptionKey", descriptionKey == null ? "" : descriptionKey);
    }

    private static int status(CommandSourceStack source) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest events are unavailable" : "RabbitQuest event config error: " + configError));
            return 0;
        }
        Instant now = Instant.now();
        source.sendSuccess(() -> Component.literal("RabbitQuest events: total="
                + current.definitions().events().size()
                + " enabled=" + current.definitions().enabledEvents().size()
                + " visible=" + current.definitions().visibleEvents(now).size()), false);
        return 1;
    }

    private static int query(CommandSourceStack source, String target) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest events are unavailable" : "RabbitQuest event config error: " + configError));
            return 0;
        }
        UUID uuid = findPlayer(current.server(), target);
        if (uuid == null) {
            source.sendFailure(Component.translatable("rabbitquest.event.result.unknown_player", target));
            return 0;
        }
        ServerPlayer receiver = source.getPlayer();
        if (receiver == null) {
            StringBuilder summary = new StringBuilder();
            Instant now = Instant.now();
            for (TimedEventDefinition event : current.definitions().enabledEvents()) {
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                CompoundTag tag = current.data().event(uuid, event.id());
                summary.append(event.id()).append(":phase=").append(event.phase(now))
                        .append(",enrolled=").append(tag.getBoolean("enrolled"))
                        .append(",completionClaimed=").append(tag.getCompound("completion").getBoolean("claimed"));
            }
            source.sendSuccess(() -> Component.literal("Events " + target + ": " + summary), false);
            return 1;
        }
        EventNetwork.send(receiver, snapshot(current, uuid, target, true, "", "", true));
        source.sendSuccess(() -> Component.translatable("rabbitquest.event.result.queried", target), false);
        return 1;
    }

    private static int reset(CommandSourceStack source, String target, String eventName) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest events are unavailable" : "RabbitQuest event config error: " + configError));
            return 0;
        }
        UUID uuid = findPlayer(current.server(), target);
        if (uuid == null) {
            source.sendFailure(Component.translatable("rabbitquest.event.result.unknown_player", target));
            return 0;
        }
        ResourceLocation eventId = ResourceLocation.tryParse(eventName);
        if (eventId == null || !eventId.toString().equals(eventName)) {
            source.sendFailure(Component.translatable("rabbitquest.event.result.invalid"));
            return 0;
        }
        EventEngine.AdminResult result = EventEngine.resetEvent(current.data(), uuid, eventId, source.getTextName());
        if (!result.outcome().equals("ok")) {
            source.sendFailure(Component.translatable("rabbitquest.event.result." + result.outcome(),
                    target, eventId.toString()));
            return 0;
        }
        ServerPlayer online = current.server().getPlayerList().getPlayer(uuid);
        if (online != null) {
            send(online, false, "", "");
        }
        source.sendSuccess(() -> Component.translatable("rabbitquest.event.result.reset",
                target, eventId.toString()), true);
        return 1;
    }

    private static int reissue(CommandSourceStack source, String target, String eventName, String targetKey) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest events are unavailable" : "RabbitQuest event config error: " + configError));
            return 0;
        }
        UUID uuid = findPlayer(current.server(), target);
        if (uuid == null) {
            source.sendFailure(Component.translatable("rabbitquest.event.result.unknown_player", target));
            return 0;
        }
        ResourceLocation eventId = ResourceLocation.tryParse(eventName);
        TimedEventDefinition event = eventId == null ? null : current.definitions().event(eventId);
        if (event == null) {
            source.sendFailure(Component.translatable("rabbitquest.event.result.invalid"));
            return 0;
        }
        EventEngine.AdminResult result = EventEngine.reissueReward(current.data(), uuid, event, targetKey,
                Instant.now(), source.getTextName());
        if (!result.outcome().equals("ok")) {
            source.sendFailure(Component.translatable("rabbitquest.event.result." + result.outcome(),
                    target, eventId.toString(), targetKey));
            return 0;
        }
        ServerPlayer online = current.server().getPlayerList().getPlayer(uuid);
        if (online != null) {
            send(online, false, "", "");
        }
        source.sendSuccess(() -> Component.translatable("rabbitquest.event.result.reissued",
                target, eventId.toString(), targetKey), true);
        return 1;
    }

    private static UUID findPlayer(MinecraftServer server, String target) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(target);
        if (online != null) {
            return online.getUUID();
        }
        var profile = server.getProfileCache().get(target);
        if (profile.isPresent()) {
            return profile.get().getId();
        }
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void recordWal(TransactionJournal wal, String requestId, String action, ServerPlayer player,
                                  String beforeHash, ResourceLocation eventId, EventSavedData data) {
        CompoundTag payload = new CompoundTag();
        payload.putString("event", eventId.toString());
        payload.put("eventTag", data.event(player.getUUID(), eventId).copy());
        String afterHash = InventorySnapshot.fingerprint(player.getInventory().items, player.registryAccess());
        wal.begin(requestId, TransactionJournal.Domain.EVENT, action, player.getUUID(), beforeHash, afterHash,
                InventorySnapshot.saveSlots(player.getInventory().items, player.registryAccess()), payload);
        wal.markCommitted(requestId);
    }

    public static RecoveryService.DomainHandler recoveryHandler(MinecraftServer server) {
        return recoveryHandler(EventSavedData.get(server));
    }

    public static RecoveryService.DomainHandler recoveryHandler(EventSavedData data) {
        return new RecoveryService.DomainHandler() {
            @Override
            public boolean isApplied(UUID playerId, String txId) {
                return data != null && data.receipt(playerId, txId) != null;
            }

            @Override
            public boolean replayWorld(UUID playerId, String txId, String action, CompoundTag payload,
                                       net.minecraft.core.HolderLookup.Provider registries) {
                if (data == null) {
                    return false;
                }
                if (data.receipt(playerId, txId) != null) {
                    return true;
                }
                ResourceLocation eventId = ResourceLocation.tryParse(payload.getString("event"));
                if (eventId == null) {
                    return false;
                }
                data.putEvent(playerId, eventId, payload.getCompound("eventTag").copy());
                data.putReceipt(playerId, txId, "ok");
                return true;
            }
        };
    }

    private static boolean isRequestId(String value) {
        if (value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
