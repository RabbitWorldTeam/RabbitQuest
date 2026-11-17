package com.tr4nce.rabbitquest.weekly;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tr4nce.rabbitquest.network.WeeklyNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative weekly service. Definitions load once per server start; progress and claims are
 * overworld SavedData. Independent from RabbitCore lifetime statistics.
 */
public final class WeeklyService {
    private static final Logger LOGGER = LoggerFactory.getLogger("rabbitquest-weekly");
    private static final String RESULT_PREFIX = "rabbitquest.weekly.result.";
    private static final int ARRIVAL_SAMPLE_TICKS = 10;

    private record Runtime(MinecraftServer server, WeeklyDefinitions definitions, WeeklySavedData data) {}

    private static volatile Runtime runtime;
    private static volatile String configError;

    private WeeklyService() {}

    public static void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            WeeklyDefinitions definitions = WeeklyDefinitions.load(server.getResourceManager());
            WeeklyCycle.activate(definitions.cycle());
            WeeklySavedData data = WeeklySavedData.get(server);
            if (data == null) {
                throw new WeeklyConfigException("Weekly SavedData could not be loaded");
            }
            LocalDate week = definitions.cycle().start(Instant.now());
            data.ensureAnchor(week.toString());
            WeeklyEngine.materializeOrder(definitions, data, week);
            runtime = new Runtime(server, definitions, data);
            configError = null;
            LOGGER.info("RabbitQuest weekly loaded: {} tasks, {} orders, {} enabled, {} ordinary; anchor {}",
                    definitions.tasks().size(), definitions.orders().size(),
                    definitions.enabledOrders().size(), definitions.ordinaryTasks().size(), data.anchor());
        } catch (RuntimeException exception) {
            runtime = null;
            configError = exception.getMessage();
            WeeklyCycle.reset();
            LOGGER.error("RabbitQuest weekly configuration rejected: {}", exception.getMessage(), exception);
        }
    }

    public static void serverStopped(ServerStoppedEvent event) {
        runtime = null;
        configError = null;
        WeeklyCycle.reset();
    }

    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, false, "", "");
        }
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(weeklyActions(Commands.literal("rabbitquestweekly")));
        LiteralArgumentBuilder<CommandSourceStack> weekly = weeklyActions(Commands.literal("weekly"));
        if (event.getDispatcher().getRoot().getChild("rabbitquest")
                instanceof com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> root) {
            root.addChild(weekly.build());
        } else {
            event.getDispatcher().register(Commands.literal("rabbitquest").then(weekly));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> weeklyActions(
            LiteralArgumentBuilder<CommandSourceStack> builder) {
        return builder
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    send(player, true, "", "");
                    return 1;
                })
                .then(Commands.literal("open").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    send(player, true, "", "");
                    return 1;
                }))
                .then(Commands.literal("status").requires(source -> source.hasPermission(2))
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("admin").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("query")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .executes(context -> query(context.getSource(),
                                                StringArgumentType.getString(context, "player")))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .then(Commands.argument("task", StringArgumentType.string())
                                                .executes(context -> admin(context.getSource(),
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "task"), null, false))
                                                .then(Commands.argument("week", StringArgumentType.string())
                                                        .executes(context -> admin(context.getSource(),
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "task"),
                                                                StringArgumentType.getString(context, "week"),
                                                                false))))))
                        .then(Commands.literal("reissue")
                                .then(Commands.argument("player", StringArgumentType.string())
                                        .then(Commands.argument("task", StringArgumentType.string())
                                                .executes(context -> admin(context.getSource(),
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "task"), null, true))
                                                .then(Commands.argument("week", StringArgumentType.string())
                                                        .executes(context -> admin(context.getSource(),
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "task"),
                                                                StringArgumentType.getString(context, "week"),
                                                                true)))))));
    }

    public static void handle(ServerPlayer player, CompoundTag request) {
        Runtime current = runtime;
        String action = request.getString("action");
        String requestId = request.getString("requestId");
        if (current == null) {
            sendError(player, request.getBoolean("open"), isRequestId(requestId) ? requestId : "");
            return;
        }
        switch (action) {
            case "open" -> send(player, true, "", isRequestId(requestId) ? requestId : "");
            case "claim" -> claim(current, player, request, requestId);
            case "deliver" -> deliver(current, player, request, requestId);
            default -> send(player, false, RESULT_PREFIX + "invalid", isRequestId(requestId) ? requestId : "");
        }
    }

    /**
     * Authoritative event entry point for external stat collectors. Returns the amount accepted.
     * DELIVER removes at most the remaining need from the player's main inventory.
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
        LocalDate week = current.definitions().cycle().start(Instant.now());
        int added = WeeklyEngine.record(current.data(), player.getUUID(), current.definitions(), week, kind,
                resource, amount, dimension == null ? null : dimension.location(), position,
                player.getInventory().items, System.currentTimeMillis());
        if (added > 0 && kind == TargetKind.DELIVER) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        return added;
    }

    /** Derives dimension and position from the player's current context. */
    public static int recordEvent(ServerPlayer player, TargetKind kind, ResourceLocation resource, double amount) {
        if (player == null) {
            return 0;
        }
        return recordEvent(player, kind, resource, amount, player.level().dimension(), player.position());
    }

    /** Sampled, event-driven arrival trigger; never counts the same objective twice. */
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
        LocalDate week = current.definitions().cycle().start(Instant.now());
        int added = WeeklyEngine.recordArrival(current.data(), player.getUUID(), current.definitions(), week,
                player.level().dimension().location(), player.position(), System.currentTimeMillis());
        if (added > 0) {
            send(player, false, "", "");
        }
    }

    private static void claim(Runtime current, ServerPlayer player, CompoundTag request, String requestId) {
        if (!isRequestId(requestId)) {
            send(player, false, RESULT_PREFIX + "invalid", "");
            return;
        }
        LocalDate now = current.definitions().cycle().start(Instant.now());
        LocalDate cycle;
        try {
            cycle = LocalDate.parse(request.getString("cycle"));
        } catch (DateTimeParseException exception) {
            send(player, false, RESULT_PREFIX + "invalid", requestId);
            return;
        }
        ResourceLocation task = ResourceLocation.tryParse(request.getString("task"));
        ResourceLocation order = ResourceLocation.tryParse(request.getString("order"));
        if (task == null || order == null) {
            send(player, false, RESULT_PREFIX + "invalid", requestId);
            return;
        }
        WeeklyEngine.reconcile(current.data(), player.getUUID(), now);
        WeeklyEngine.ClaimResult result = WeeklyEngine.claim(current.data(), player.getUUID(), current.definitions(),
                now, requestId, cycle.toString(), task, order, request.getInt("selection"),
                player.getInventory().items, System.currentTimeMillis());
        if (result.outcome().equals("ok")) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        send(player, false, RESULT_PREFIX + result.outcome(), requestId);
    }

    private static void deliver(Runtime current, ServerPlayer player, CompoundTag request, String requestId) {
        if (!isRequestId(requestId)) {
            send(player, false, RESULT_PREFIX + "invalid", "");
            return;
        }
        ResourceLocation task = ResourceLocation.tryParse(request.getString("task"));
        ResourceLocation order = ResourceLocation.tryParse(request.getString("order"));
        ResourceLocation target = ResourceLocation.tryParse(request.getString("target"));
        if (task == null || order == null || target == null) {
            send(player, false, RESULT_PREFIX + "invalid", requestId);
            return;
        }
        LocalDate week = current.definitions().cycle().start(Instant.now());
        WeeklyEngine.DeliverResult result = WeeklyEngine.deliver(current.data(), player.getUUID(),
                current.definitions(), week, requestId, request.getString("cycle"), task, order, target,
                request.getInt("amount"), player.level().dimension().location(), player.position(),
                player.getInventory().items, System.currentTimeMillis());
        if (result.outcome().equals("ok")) {
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
        WeeklyNetwork.send(player, snapshot(current, player.getUUID(), player.getGameProfile().getName(), open,
                message, requestId, false));
    }

    private static void sendError(ServerPlayer player, boolean open, String requestId) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("type", "weekly_snapshot");
        snapshot.putBoolean("open", open);
        snapshot.putBoolean("admin", player.hasPermissions(2));
        snapshot.putBoolean("adminView", false);
        snapshot.putString("error", configError == null ? "rabbitquest.weekly.error.unavailable" : configError);
        if (!requestId.isEmpty()) {
            snapshot.putString("requestId", requestId);
        }
        WeeklyNetwork.send(player, snapshot);
    }

    static CompoundTag snapshot(Runtime current, UUID playerId, String playerName, boolean open,
                                String message, String requestId, boolean adminView) {
        Instant now = Instant.now();
        LocalDate week = current.definitions().cycle().start(now);
        WeeklyEngine.reconcile(current.data(), playerId, week);
        WeeklyDefinitions definitions = current.definitions();
        EngineeringOrder order = WeeklyEngine.activeOrder(definitions, current.data(), week);
        List<WeeklyEngine.ActiveTask> active = WeeklyEngine.activeTasks(definitions, current.data(), week);

        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("type", "weekly_snapshot");
        snapshot.putBoolean("open", open);
        snapshot.putBoolean("admin", adminView);
        snapshot.putBoolean("adminView", adminView);
        snapshot.putString("player", playerName);
        snapshot.putString("playerId", playerId.toString());
        snapshot.putString("cycle", week.toString());
        snapshot.putString("cycleEnd", current.definitions().cycle().end(now).toString());
        String anchor = current.data().anchor();
        snapshot.putString("anchor", anchor == null ? "" : anchor);
        if (order != null) {
            snapshot.putString("order", order.id().toString());
            putDisplay(snapshot, "order", order.name(), order.nameKey(), order.description(), order.descriptionKey(),
                    order.icon());
        }
        ListTag tasks = new ListTag();
        for (WeeklyEngine.ActiveTask task : active) {
            tasks.add(taskSnapshot(current.data(), playerId, week, task));
        }
        snapshot.put("tasks", tasks);
        putClaims(snapshot, current.data(), definitions, playerId, week);
        if (!message.isEmpty()) {
            snapshot.putString("message", message);
        }
        if (!requestId.isEmpty()) {
            snapshot.putString("requestId", requestId);
        }
        return snapshot;
    }

    private static CompoundTag taskSnapshot(WeeklySavedData data, UUID playerId, LocalDate week,
                                            WeeklyEngine.ActiveTask active) {
        WeeklyTaskDefinition task = active.task();
        CompoundTag row = new CompoundTag();
        row.putString("id", task.id().toString());
        row.putString("token", active.token().toString());
        row.putBoolean("ordinary", active.ordinary());
        putDisplay(row, "task", task.name(), task.nameKey(), task.description(), task.descriptionKey(), task.icon());
        if (active.order() != null) {
            EngineeringOrder order = active.order();
            putDisplay(row, "order", order.name(), order.nameKey(), order.description(), order.descriptionKey(),
                    order.icon());
        }
        CompoundTag taskTag = data.task(playerId, week.toString(), task.id());
        CompoundTag progress = taskTag.getCompound("progress");
        ListTag targets = new ListTag();
        for (WeeklyTarget target : task.targets()) {
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
        row.put("targets", targets);
        row.putBoolean("completed", taskTag.getBoolean("completed"));
        row.putBoolean("claimed", taskTag.getBoolean("claimed"));
        row.putBoolean("closed", taskTag.getBoolean("closed"));
        row.putBoolean("expired", taskTag.getBoolean("expired"));
        row.putBoolean("claimable", WeeklyEngine.isClaimable(taskTag, week));
        row.putInt("selection", taskTag.contains("selection") ? taskTag.getInt("selection") : 0);
        row.putBoolean("selectionSet", taskTag.getBoolean("selectionSet"));
        row.putInt("rewardGroup", taskTag.contains("rewardGroup") ? taskTag.getInt("rewardGroup") : -1);
        row.putInt("rewardRoll", taskTag.contains("rewardRoll") ? taskTag.getInt("rewardRoll") : -1);
        CompoundTag reward = taskTag.contains("reward", Tag.TAG_COMPOUND)
                ? taskTag.getCompound("reward").copy() : active.reward().save();
        row.put("reward", reward);
        row.putString("rewardMode", reward.getString("mode"));
        return row;
    }

    private static double progress(WeeklyTarget target, CompoundTag progress) {
        Tag tag = progress.get(target.id().toString());
        return tag instanceof net.minecraft.nbt.NumericTag number ? number.getAsDouble() : 0.0;
    }

    private static void putDisplay(CompoundTag snapshot, String prefix, String name, String nameKey,
                                   String description, String descriptionKey, ResourceLocation icon) {
        snapshot.putString(prefix + "Name", name);
        snapshot.putString(prefix + "NameKey", nameKey == null ? "" : nameKey);
        snapshot.putString(prefix + "Description", description == null ? "" : description);
        snapshot.putString(prefix + "DescriptionKey", descriptionKey == null ? "" : descriptionKey);
        snapshot.putString(prefix + "Icon", icon.toString());
    }

    private static void putClaims(CompoundTag snapshot, WeeklySavedData data, WeeklyDefinitions definitions,
                                  UUID playerId, LocalDate week) {
        List<CompoundTag> claims = new ArrayList<>();
        for (CompoundTag task : data.tasks(playerId)) {
            if (!WeeklyEngine.isClaimable(task, week)) {
                continue;
            }
            CompoundTag claim = new CompoundTag();
            String cycle = task.getString("cycle");
            String definition = task.getString("definition");
            String orderId = task.getString("order");
            claim.putString("cycle", cycle);
            claim.putString("task", definition);
            claim.putString("order", orderId);
            ResourceLocation taskId = ResourceLocation.tryParse(definition);
            WeeklyTaskDefinition taskDefinition = taskId == null ? null : definitions.task(taskId);
            ResourceLocation lookupOrder = ResourceLocation.tryParse(orderId);
            EngineeringOrder orderDefinition = lookupOrder == null ? null : definitions.order(lookupOrder);
            claim.putString("name", taskDefinition == null ? definition : taskDefinition.name());
            claim.putString("nameKey", taskDefinition == null ? "" : taskDefinition.nameKey());
            claim.putString("orderName", orderDefinition == null ? "" : orderDefinition.name());
            claim.putString("orderNameKey", orderDefinition == null ? "" : orderDefinition.nameKey());
            CompoundTag reward = task.getCompound("reward");
            claim.put("reward", reward.copy());
            claim.putString("rewardMode", reward.getString("mode"));
            claim.putInt("rewardGroup", task.contains("rewardGroup") ? task.getInt("rewardGroup") : -1);
            claim.putBoolean("current", cycle.equals(week.toString()));
            claims.add(claim);
        }
        claims.sort(Comparator.comparing((CompoundTag claim) -> claim.getString("cycle")).reversed()
                .thenComparing(claim -> claim.getString("task")));
        ListTag list = new ListTag();
        for (CompoundTag claim : claims) {
            list.add(claim);
        }
        snapshot.put("claims", list);
    }

    private static int status(CommandSourceStack source) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest weekly is unavailable" : "RabbitQuest weekly config error: " + configError));
            return 0;
        }
        LocalDate week = current.definitions().cycle().start(Instant.now());
        EngineeringOrder order = WeeklyEngine.activeOrder(current.definitions(), current.data(), week);
        source.sendSuccess(() -> Component.literal("RabbitQuest weekly: cycle=" + week
                + " anchor=" + current.data().anchor()
                + " order=" + (order == null ? "none" : order.id())
                + " ordinary=" + current.definitions().ordinaryTasks().size()), false);
        return 1;
    }

    private static int query(CommandSourceStack source, String target) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest weekly is unavailable" : "RabbitQuest weekly config error: " + configError));
            return 0;
        }
        UUID uuid = findPlayer(current.server(), target);
        if (uuid == null) {
            source.sendFailure(Component.translatable("rabbitquest.weekly.result.unknown_player", target));
            return 0;
        }
        ServerPlayer receiver = source.getPlayer();
        if (receiver == null) {
            LocalDate week = current.definitions().cycle().start(Instant.now());
            WeeklyEngine.reconcile(current.data(), uuid, week);
            List<WeeklyEngine.ActiveTask> active = WeeklyEngine.activeTasks(current.definitions(), current.data(),
                    week);
            StringBuilder summary = new StringBuilder();
            for (WeeklyEngine.ActiveTask task : active) {
                CompoundTag taskTag = current.data().task(uuid, week.toString(), task.task().id());
                if (summary.length() > 0) {
                    summary.append(", ");
                }
                summary.append(task.task().id()).append(":completed=").append(taskTag.getBoolean("completed"))
                        .append(",claimed=").append(taskTag.getBoolean("claimed"))
                        .append(",expired=").append(taskTag.getBoolean("expired"));
            }
            String activeText = summary.length() == 0 ? "none" : summary.toString();
            source.sendSuccess(() -> Component.literal("Weekly " + target + ": cycle=" + week
                    + " tasks=" + activeText), false);
            return 1;
        }
        WeeklyNetwork.send(receiver, snapshot(current, uuid, target, true, "", "", true));
        source.sendSuccess(() -> Component.translatable("rabbitquest.weekly.result.queried", target), false);
        return 1;
    }

    private static int admin(CommandSourceStack source, String target, String taskName, String weekName,
                             boolean reissue) {
        Runtime current = runtime;
        if (current == null) {
            source.sendFailure(Component.literal(configError == null
                    ? "RabbitQuest weekly is unavailable" : "RabbitQuest weekly config error: " + configError));
            return 0;
        }
        UUID uuid = findPlayer(current.server(), target);
        if (uuid == null) {
            source.sendFailure(Component.translatable("rabbitquest.weekly.result.unknown_player", target));
            return 0;
        }
        ResourceLocation task = ResourceLocation.tryParse(taskName);
        if (task == null || !task.toString().equals(taskName)) {
            source.sendFailure(Component.translatable("rabbitquest.weekly.result.invalid"));
            return 0;
        }
        LocalDate now = current.definitions().cycle().start(Instant.now());
        LocalDate week;
        try {
            week = weekName == null ? now : LocalDate.parse(weekName);
        } catch (DateTimeParseException exception) {
            source.sendFailure(Component.translatable("rabbitquest.weekly.result.invalid_week"));
            return 0;
        }
        WeeklyEngine.AdminResult result = reissue
                ? WeeklyEngine.reissueTask(current.data(), uuid, week, task, now, source.getTextName())
                : WeeklyEngine.resetTask(current.data(), uuid, week, task, source.getTextName());
        if (!result.outcome().equals("ok")) {
            source.sendFailure(Component.translatable("rabbitquest.weekly.result." + result.outcome(),
                    target, task.toString(), week.toString()));
            return 0;
        }
        ServerPlayer online = current.server().getPlayerList().getPlayer(uuid);
        if (online != null) {
            send(online, false, "", "");
        }
        source.sendSuccess(() -> Component.translatable("rabbitquest.weekly.result."
                + (reissue ? "reissued" : "reset"), target, task.toString(), week.toString()), true);
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
