package com.tr4nce.rabbitquest.config;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tr4nce.rabbitquest.event.EventDefinitions;
import com.tr4nce.rabbitquest.event.EventSavedData;
import com.tr4nce.rabbitquest.event.EventService;
import com.tr4nce.rabbitquest.weekly.WeeklyDefinitions;
import com.tr4nce.rabbitquest.weekly.WeeklySavedData;
import com.tr4nce.rabbitquest.weekly.WeeklyService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates atomic configuration validation, definition versioning and real-time snapshot push
 * for {@code /rabbitquest reload}.
 */
public final class ReloadService {
    private static final AtomicLong VERSION = new AtomicLong(1L);

    private ReloadService() {}

    public static long currentVersion() {
        return VERSION.get();
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> reload = Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(context -> execute(context.getSource()));
        if (event.getDispatcher().getRoot().getChild("rabbitquest")
                instanceof com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> root) {
            root.addChild(reload.build());
        } else {
            event.getDispatcher().register(Commands.literal("rabbitquest").then(reload));
        }
    }

    private static int execute(CommandSourceStack source) {
        ReloadResult result = reloadServer(source.getServer());
        if (result.success()) {
            source.sendSuccess(() -> Component.translatable("rabbitquest.reload.ok", result.version()), true);
            return 1;
        }
        source.sendFailure(Component.translatable("rabbitquest.reload.failed", result.summary()));
        return 0;
    }

    public static ReloadResult reloadServer(MinecraftServer server) {
        long current = VERSION.get();
        WeeklySavedData weeklyData = WeeklySavedData.get(server);
        EventSavedData eventData = EventSavedData.get(server);
        if (weeklyData == null || eventData == null) {
            return ReloadResult.failed(current, "storage", "", "SavedData unavailable");
        }
        server.getPackRepository().reload();
        try (CloseableResourceManager candidateManager = new MultiPackResourceManager(
                PackType.SERVER_DATA, server.getPackRepository().openAllSelected())) {
            WeeklyDefinitions candidateWeekly = WeeklyDefinitions.load(candidateManager);
            EventDefinitions candidateEvents = EventDefinitions.load(candidateManager);
            LocalDate week = candidateWeekly.cycle().start(Instant.now());
            ReloadResult check = validate(
                    WeeklyService.activeDefinitions(), candidateWeekly, weeklyData,
                    EventService.activeDefinitions(), candidateEvents, eventData, week);
            if (!check.success()) {
                return ReloadResult.failed(current, check.file(), check.field(), check.reason());
            }
            long nextVersion = VERSION.incrementAndGet();
            WeeklyService.applyReload(candidateWeekly, nextVersion);
            EventService.applyReload(candidateEvents, nextVersion);
            return ReloadResult.ok(nextVersion);
        } catch (RuntimeException exception) {
            return ReloadResult.failed(current, "datapack", "", exception.getMessage());
        }
    }

    public static ReloadResult validate(WeeklyDefinitions previousWeekly, WeeklyDefinitions candidateWeekly,
                                        WeeklySavedData weeklyData, EventDefinitions previousEvents,
                                        EventDefinitions candidateEvents, EventSavedData eventData,
                                        LocalDate currentWeek) {
        long current = VERSION.get();
        ReloadResult weeklyCheck = ReloadValidator.validateWeekly(previousWeekly, candidateWeekly, weeklyData,
                currentWeek);
        if (!weeklyCheck.success()) {
            return ReloadResult.failed(current, weeklyCheck.file(), weeklyCheck.field(), weeklyCheck.reason());
        }
        ReloadResult eventCheck = ReloadValidator.validateEvents(previousEvents, candidateEvents, eventData);
        if (!eventCheck.success()) {
            return ReloadResult.failed(current, eventCheck.file(), eventCheck.field(), eventCheck.reason());
        }
        return ReloadResult.ok(current);
    }
}
