package com.tr4nce.rabbitquest.weekly;

import com.tr4nce.rabbitquest.event.EventService;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class TaskEventListeners {
    private static final Map<ServerPlayer, Long> TRAVEL_BASELINES = new WeakHashMap<>();

    private TaskEventListeners() {}

    public static void register(IEventBus bus) {
        bus.addListener(TaskEventListeners::crafted);
        bus.addListener(EventPriority.LOWEST, false, ItemFishedEvent.class, TaskEventListeners::fished);
        bus.addListener(TaskEventListeners::travelled);
        bus.addListener(TaskEventListeners::loggedIn);
        bus.addListener(TaskEventListeners::loggedOut);
    }

    private static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getInventory() instanceof CraftingContainer inputs)
                || !(player.containerMenu instanceof CraftingMenu || player.containerMenu instanceof InventoryMenu)
                || !(player.containerMenu.getSlot(0).container instanceof ResultContainer result)
                || result.getRecipeUsed() == null
                || !(result.getRecipeUsed().value() instanceof CraftingRecipe recipe)) {
            return;
        }
        // Shift-click can pass the remainder after moving the result to inventory.
        // The active recipe and its unconsumed inputs give the whole output for this craft.
        ItemStack output = recipe.assemble(inputs.asCraftInput(), player.registryAccess());
        if (!output.isEmpty()) {
            forward(player, TargetKind.CRAFT, BuiltInRegistries.ITEM.getKey(output.getItem()),
                    output.getCount(), player.position());
        }
    }

    private static void fished(ItemFishedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            if (!drop.isEmpty()) {
                forward(player, TargetKind.FISH, BuiltInRegistries.ITEM.getKey(drop.getItem()),
                        drop.getCount(), event.getHookEntity().position());
            }
        }
    }

    private static long walkingCm(ServerPlayer player) {
        var stats = player.getStats();
        return (long) stats.getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM))
                + stats.getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM))
                + stats.getValue(Stats.CUSTOM.get(Stats.CROUCH_ONE_CM));
    }

    private static void travelled(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        long total = walkingCm(player);
        Long previous = TRAVEL_BASELINES.put(player, total);
        if (previous != null && total > previous) {
            forward(player, TargetKind.TRAVEL, null, (total - previous) / 100.0, player.position());
        }
    }

    private static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TRAVEL_BASELINES.put(player, walkingCm(player));
        }
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TRAVEL_BASELINES.remove(player);
        }
    }

    private static void forward(ServerPlayer player, TargetKind kind, ResourceLocation resource,
                                double amount, Vec3 position) {
        WeeklyService.recordEvent(player, kind, resource, amount, player.level().dimension(), position);
        EventService.recordEvent(player, kind, resource, amount, player.level().dimension(), position);
    }
}
