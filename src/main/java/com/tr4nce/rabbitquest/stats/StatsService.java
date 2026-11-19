package com.tr4nce.rabbitquest.stats;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class StatsService {
    private static final String PREFIX = "rabbitquest.stats.";

    private static volatile Consumer<Action> eventSink;

    private StatsService() {}

    public static void register() {
        register(NeoForge.EVENT_BUS);
    }

    public static void register(IEventBus bus) {
        bus.addListener(StatsService::registerCommands);
        bus.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class, StatsService::onBlockBreak);
        bus.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, StatsService::onLivingDeath);
        bus.addListener(EventPriority.LOWEST, LivingDamageEvent.Post.class, StatsService::onLivingDamage);
    }

    /**
     * Receives each accepted individual action. Never receives lifetime totals.
     */
    public static void setEventSink(Consumer<Action> sink) {
        eventSink = sink;
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rabbitquest")
                .then(Commands.literal("stats")
                        .executes(context -> totals(context.getSource(), null))
                        .then(Commands.literal("block")
                                .then(Commands.argument("block", ResourceLocationArgument.id())
                                        .executes(context -> block(context.getSource(), null,
                                                ResourceLocationArgument.getId(context, "block")))))
                        .then(Commands.literal("entity")
                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                        .executes(context -> entity(context.getSource(), null,
                                                ResourceLocationArgument.getId(context, "entity")))))
                        .then(Commands.literal("player")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(context -> totals(context.getSource(), target(context)))
                                        .then(Commands.literal("block")
                                                .then(Commands.argument("block", ResourceLocationArgument.id())
                                                        .executes(context -> block(context.getSource(), target(context),
                                                                ResourceLocationArgument.getId(context, "block")))))
                                        .then(Commands.literal("entity")
                                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                                        .executes(context -> entity(context.getSource(), target(context),
                                                                ResourceLocationArgument.getId(context, "entity")))))))));
    }

    private static String target(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "player");
    }

    private static int totals(CommandSourceStack source, String targetInput) throws CommandSyntaxException {
        Target target = target(source, targetInput);
        StatsSavedData data = data(source, target);
        if (data == null) {
            return 0;
        }
        StatsSavedData.Totals totals = data.totals(target.id());
        source.sendSuccess(() -> Component.translatable(PREFIX + "totals", target.name(),
                Long.toString(totals.harvested()), Long.toString(totals.kills()),
                Double.toString(totals.damage())), false);
        return 1;
    }

    private static int block(CommandSourceStack source, String targetInput, ResourceLocation block)
            throws CommandSyntaxException {
        Target target = target(source, targetInput);
        StatsSavedData data = data(source, target);
        if (data == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(PREFIX + "block", target.name(), block.toString(),
                Long.toString(data.harvested(target.id(), block))), false);
        return 1;
    }

    private static int entity(CommandSourceStack source, String targetInput, ResourceLocation entity)
            throws CommandSyntaxException {
        Target target = target(source, targetInput);
        StatsSavedData data = data(source, target);
        if (data == null) {
            return 0;
        }
        StatsSavedData.EntityTotals totals = data.entity(target.id(), entity);
        source.sendSuccess(() -> Component.translatable(PREFIX + "entity", target.name(), entity.toString(),
                Long.toString(totals.kills()), Double.toString(totals.damage())), false);
        return 1;
    }

    private static StatsSavedData data(CommandSourceStack source, Target target) {
        if (target == null) {
            return null;
        }
        StatsSavedData data = StatsSavedData.get(source.getServer());
        if (data == null) {
            source.sendFailure(Component.translatable(PREFIX + "storage"));
        }
        return data;
    }

    private static Target target(CommandSourceStack source, String input) throws CommandSyntaxException {
        if (input == null) {
            ServerPlayer player = source.getPlayerOrException();
            return new Target(player.getUUID(), player.getGameProfile().getName());
        }
        if (!source.hasPermission(2)) {
            source.sendFailure(Component.translatable(PREFIX + "denied"));
            return null;
        }
        return resolve(source.getServer(), input, source);
    }

    private static Target resolve(MinecraftServer server, String input, CommandSourceStack source) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(input);
        if (online != null) {
            return new Target(online.getUUID(), online.getGameProfile().getName());
        }
        UUID uuid = parseUuid(input);
        if (uuid != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                return new Target(uuid, player.getGameProfile().getName());
            }
            GameProfileCache cache = server.getProfileCache();
            Optional<GameProfile> profile = cache == null ? Optional.empty() : cache.get(uuid);
            return new Target(uuid, profile.map(GameProfile::getName).orElse(uuid.toString()));
        }
        GameProfileCache cache = server.getProfileCache();
        Optional<GameProfile> profile = cache == null ? Optional.empty() : cache.get(input);
        if (profile.isPresent()) {
            return new Target(profile.get().getId(), profile.get().getName());
        }
        source.sendFailure(Component.translatable(PREFIX + "player_not_found", input));
        return null;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var state = event.getState();
        boolean mature = state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
                || state.getBlock() instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) == 3
                || state.getBlock() instanceof CocoaBlock && state.getValue(CocoaBlock.AGE) == 2;
        ResourceLocation block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        record(new Action(player, ActionType.HARVEST, block, 1.0, level.dimension(), event.getPos(), mature));
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob mob)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceLocation entity = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        record(new Action(player, ActionType.KILL, entity, 1.0, mob.level().dimension(), mob.blockPosition()));
    }

    private static void onLivingDamage(LivingDamageEvent.Post event) {
        double amount = event.getNewDamage();
        if (!(amount > 0.0) || !Double.isFinite(amount) || !(event.getEntity() instanceof Mob mob)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceLocation entity = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        record(new Action(player, ActionType.DAMAGE, entity, amount, mob.level().dimension(), mob.blockPosition()));
    }

    private static void record(Action action) {
        StatsSavedData data = StatsSavedData.get(action.player().server);
        if (data != null) {
            data.record(action.player().getUUID(), action.type(), action.target(), action.amount());
        }
        Consumer<Action> sink = eventSink;
        if (sink != null) {
            sink.accept(action);
        }
    }

    private record Target(UUID id, String name) {}
}
