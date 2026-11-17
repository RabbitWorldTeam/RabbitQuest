package com.tr4nce.rabbitquest.storage;

import com.tr4nce.rabbitquest.event.EventSavedData;
import com.tr4nce.rabbitquest.event.EventService;
import com.tr4nce.rabbitquest.shop.ShopSavedData;
import com.tr4nce.rabbitquest.shop.ShopService;
import com.tr4nce.rabbitquest.weekly.WeeklySavedData;
import com.tr4nce.rabbitquest.weekly.WeeklyService;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryServiceTest {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @TempDir
    Path tempDir;

    @Test
    void recoversAllFourCrashScenariosAcrossShopWeeklyAndEvent() {
        UUID player = UUID.randomUUID();
        Path walFile = tempDir.resolve("rabbitquest_tx.dat");

        NonNullList<ItemStack> beforeInv = NonNullList.withSize(36, ItemStack.EMPTY);
        beforeInv.set(0, new ItemStack(Items.OAK_LOG, 8));
        NonNullList<ItemStack> afterInv = NonNullList.withSize(36, ItemStack.EMPTY);
        afterInv.set(0, new ItemStack(Items.OAK_LOG, 4));
        afterInv.set(1, new ItemStack(Items.EMERALD, 1));

        String beforeHash = InventorySnapshot.fingerprint(beforeInv, REGISTRIES);
        String afterHash = InventorySnapshot.fingerprint(afterInv, REGISTRIES);
        ListTag afterSlots = InventorySnapshot.saveSlots(afterInv, REGISTRIES);

        // 1. Neither player nor world saved -> ABORTED
        TransactionJournal wal = new TransactionJournal(walFile);
        String tx1 = UUID.randomUUID().toString();
        CompoundTag shopPayload = new CompoundTag();
        shopPayload.putString("product", "rabbitquest:demo");
        shopPayload.putInt("amount", 1);
        shopPayload.putLong("time", 1763350000000L);
        wal.begin(tx1, TransactionJournal.Domain.SHOP, "BUY", player, beforeHash, afterHash, afterSlots, shopPayload);

        ShopSavedData shopData = new ShopSavedData();
        WeeklySavedData weeklyData = new WeeklySavedData();
        EventSavedData eventData = new EventSavedData();
        NonNullList<ItemStack> inv1 = copyOf(beforeInv);
        assertFalse(RecoveryService.recoverPlayer(player, inv1, REGISTRIES, new TransactionJournal(walFile),
                ShopService.recoveryHandler(shopData),
                WeeklyService.recoveryHandler(weeklyData),
                EventService.recoveryHandler(eventData)));
        assertEquals(TransactionJournal.State.ABORTED, new TransactionJournal(walFile).state(tx1));
        assertEquals(8, inv1.get(0).getCount());

        // 2. Player-only saved -> replay world state idempotently -> COMMITTED
        String tx2 = UUID.randomUUID().toString();
        CompoundTag weeklyPayload = new CompoundTag();
        weeklyPayload.putString("cycle", "2026-11-16");
        weeklyPayload.putString("task", "rabbitquest:gather");
        CompoundTag taskTag = new CompoundTag();
        taskTag.putString("cycle", "2026-11-16");
        taskTag.putString("definition", "rabbitquest:gather");
        taskTag.putBoolean("claimed", true);
        weeklyPayload.put("taskTag", taskTag);
        wal.begin(tx2, TransactionJournal.Domain.WEEKLY, "CLAIM", player, beforeHash, afterHash, afterSlots,
                weeklyPayload);

        NonNullList<ItemStack> inv2 = copyOf(afterInv);
        assertFalse(RecoveryService.recoverPlayer(player, inv2, REGISTRIES, new TransactionJournal(walFile),
                ShopService.recoveryHandler(shopData),
                WeeklyService.recoveryHandler(weeklyData),
                EventService.recoveryHandler(eventData)));
        assertEquals(TransactionJournal.State.COMMITTED, new TransactionJournal(walFile).state(tx2));
        assertTrue(weeklyData.task(player, "2026-11-16", ResourceLocation.parse("rabbitquest:gather"))
                .getBoolean("claimed"));

        // 3. World-only saved -> replay inventory slots from WAL -> COMMITTED
        String tx3 = UUID.randomUUID().toString();
        CompoundTag eventPayload = new CompoundTag();
        eventPayload.putString("event", "rabbitquest:autumn_s1");
        CompoundTag eventTag = new CompoundTag();
        eventTag.putString("id", "rabbitquest:autumn_s1");
        eventPayload.put("eventTag", eventTag);
        wal.begin(tx3, TransactionJournal.Domain.EVENT, "CLAIM_STEP", player, beforeHash, afterHash, afterSlots,
                eventPayload);
        assertTrue(EventService.recoveryHandler(eventData)
                .replayWorld(player, tx3, "CLAIM_STEP", eventPayload, REGISTRIES));

        NonNullList<ItemStack> inv3 = copyOf(beforeInv);
        assertTrue(RecoveryService.recoverPlayer(player, inv3, REGISTRIES, new TransactionJournal(walFile),
                ShopService.recoveryHandler(shopData),
                WeeklyService.recoveryHandler(weeklyData),
                EventService.recoveryHandler(eventData)));
        assertEquals(TransactionJournal.State.COMMITTED, new TransactionJournal(walFile).state(tx3));
        assertEquals(4, inv3.get(0).getCount());
        assertEquals(1, inv3.get(1).getCount());

        // 4. Both saved -> COMMITTED with zero duplicate changes
        String tx4 = UUID.randomUUID().toString();
        wal.begin(tx4, TransactionJournal.Domain.SHOP, "BUY", player, beforeHash, afterHash, afterSlots, shopPayload);
        assertTrue(ShopService.recoveryHandler(shopData).replayWorld(player, tx4, "BUY", shopPayload, REGISTRIES));
        NonNullList<ItemStack> inv4 = copyOf(afterInv);
        assertFalse(RecoveryService.recoverPlayer(player, inv4, REGISTRIES, new TransactionJournal(walFile),
                ShopService.recoveryHandler(shopData),
                WeeklyService.recoveryHandler(weeklyData),
                EventService.recoveryHandler(eventData)));
        assertEquals(TransactionJournal.State.COMMITTED, new TransactionJournal(walFile).state(tx4));
        assertEquals(4, inv4.get(0).getCount());
        assertEquals(1, inv4.get(1).getCount());
    }

    private static NonNullList<ItemStack> copyOf(NonNullList<ItemStack> source) {
        NonNullList<ItemStack> copy = NonNullList.withSize(source.size(), ItemStack.EMPTY);
        for (int i = 0; i < source.size(); i++) {
            copy.set(i, source.get(i).copy());
        }
        return copy;
    }
}
