package com.tr4nce.rabbitquest.storage;

import com.tr4nce.rabbitquest.event.EventService;
import com.tr4nce.rabbitquest.shop.ShopService;
import com.tr4nce.rabbitquest.weekly.WeeklyService;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.List;
import java.util.UUID;

/**
 * Reconciles pending write-ahead transactions on player login across the four crash outcomes:
 * neither saved, player-only saved, world-only saved, and both saved.
 */
public final class RecoveryService {
    public interface DomainHandler {
        boolean isApplied(UUID playerId, String txId);

        boolean replayWorld(UUID playerId, String txId, String action, CompoundTag payload,
                            HolderLookup.Provider registries);
    }

    private static volatile TransactionJournal journal;

    private RecoveryService() {}

    public static void serverStarted(ServerStartedEvent event) {
        journal = TransactionJournal.open(event.getServer());
    }

    public static void serverStopped(ServerStoppedEvent event) {
        journal = null;
    }

    public static TransactionJournal journal() {
        return journal;
    }

    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        TransactionJournal current = journal;
        if (current == null) {
            return;
        }
        boolean inventoryChanged = recoverPlayer(
                player.getUUID(),
                player.getInventory().items,
                player.registryAccess(),
                current,
                ShopService.recoveryHandler(player.server),
                WeeklyService.recoveryHandler(player.server),
                EventService.recoveryHandler(player.server)
        );
        if (inventoryChanged) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
    }

    public static boolean recoverPlayer(UUID playerId, List<ItemStack> inventory,
                                        HolderLookup.Provider registries, TransactionJournal wal,
                                        DomainHandler shop, DomainHandler weekly, DomainHandler event) {
        if (wal == null) {
            return false;
        }
        boolean inventoryMutated = false;
        for (CompoundTag entry : wal.pendingFor(playerId)) {
            String txId = entry.getString("txId");
            TransactionJournal.Domain domain = TransactionJournal.Domain.valueOf(entry.getString("domain"));
            DomainHandler handler = switch (domain) {
                case SHOP -> shop;
                case WEEKLY -> weekly;
                case EVENT -> event;
            };
            if (handler == null) {
                continue;
            }
            boolean worldApplied = handler.isApplied(playerId, txId);
            String currentHash = InventorySnapshot.fingerprint(inventory, registries);
            String beforeHash = entry.getString("beforeHash");
            String afterHash = entry.getString("afterHash");

            if (!worldApplied && currentHash.equals(beforeHash)) {
                // Case 1: WAL written, neither player inventory nor world SavedData was saved.
                wal.markAborted(txId);
            } else if (!worldApplied && currentHash.equals(afterHash)) {
                // Case 2: Only player inventory was saved; replay world-state commit idempotently.
                if (handler.replayWorld(playerId, txId, entry.getString("action"),
                        entry.getCompound("payload"), registries)) {
                    wal.markCommitted(txId);
                }
            } else if (worldApplied && currentHash.equals(beforeHash)) {
                // Case 3: Only world SavedData was saved; replay inventory slots from WAL.
                InventorySnapshot.applySlots(inventory, entry.getList("afterSlots", Tag.TAG_COMPOUND), registries);
                inventoryMutated = true;
                wal.markCommitted(txId);
            } else if (worldApplied) {
                // Case 4: Both sides already persisted.
                wal.markCommitted(txId);
            } else {
                // Inventory diverged externally before world save; abort safely without duplicating items.
                wal.markAborted(txId);
            }
        }
        return inventoryMutated;
    }
}
