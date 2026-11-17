package com.tr4nce.rabbitquest.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPurchaseTest {
    private static final Instant BEFORE_RESET = Instant.parse("2026-11-15T19:59:59Z");
    private static final Instant AFTER_RESET = BEFORE_RESET.plusSeconds(1);

    @Test
    void finalStockIsSoldOnceAndSuccessfulRequestCanBeReplayedAfterReload(@TempDir Path folder) throws IOException {
        var data = shop(1, 0, 0);
        var firstInventory = inventory();
        var secondInventory = inventory();
        var request = purchaseRequest();
        assertEquals("ok", buy(data, "first", request, BEFORE_RESET, firstInventory));
        assertEquals("stock", buy(data, "second", purchaseRequest(), BEFORE_RESET, secondInventory));
        assertEquals(1, secondInventory.getFirst().getCount());

        Path saveFile = folder.resolve("rabbitquest_shop.dat");
        var saved = new CompoundTag();
        saved.put("data", data.save(new CompoundTag(), registries()));
        NbtUtils.addCurrentDataVersion(saved);
        NbtIo.writeCompressed(saved, saveFile);

        var storage = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries());
        var loaded = ShopSavedData.get(storage, folder);
        assertNotNull(loaded);
        assertEquals("ok", buy(loaded, "first", request, BEFORE_RESET, firstInventory));
        assertTrue(firstInventory.getFirst().is(Items.DIAMOND));
        assertEquals(1, loaded.transactions.size());
        var transaction = loaded.transactions.getCompound(0);
        assertEquals(request.getString("requestId"), transaction.getString("requestId"));
        assertEquals("first", transaction.getString("player"));
        assertEquals(BEFORE_RESET.toEpochMilli(), transaction.getLong("time"));
        assertEquals("minecraft:emerald", transaction.getList("removed", Tag.TAG_COMPOUND).getCompound(0).getString("id"));
        assertEquals("minecraft:diamond", transaction.getList("granted", Tag.TAG_COMPOUND).getCompound(0).getString("id"));
        assertEquals(0, loaded.products.getCompound(0).getInt("stock"));
        assertEquals("stock", buy(loaded, "second", purchaseRequest(), BEFORE_RESET, secondInventory));
    }

    @Test
    void failedRequestDoesNotExecuteAfterRestockAndReload() {
        var data = shop(0, 0, 0);
        var request = purchaseRequest();
        var inventory = inventory();
        assertEquals("stock", buy(data, "first", request, BEFORE_RESET, inventory));

        data.products.getCompound(0).putInt("stock", 2);
        var loaded = ShopSavedData.load(data.save(new CompoundTag(), registries()), registries());
        assertEquals("stock", buy(loaded, "first", request, BEFORE_RESET, inventory));
        assertTrue(inventory.getFirst().is(Items.EMERALD));
        assertEquals("ok", buy(loaded, "first", purchaseRequest(), BEFORE_RESET, inventory));
        assertTrue(inventory.getFirst().is(Items.DIAMOND));
    }

    @Test
    void weeklyLimitRefreshesButLifetimeLimitSurvivesReload() {
        var data = shop(-1, 2, 1);
        assertEquals("ok", buy(data, "first", purchaseRequest(), BEFORE_RESET, inventory()));
        assertEquals("limit", buy(data, "first", purchaseRequest(), BEFORE_RESET, inventory()));

        var loaded = ShopSavedData.load(data.save(new CompoundTag(), registries()), registries());
        assertEquals("ok", buy(loaded, "first", purchaseRequest(), AFTER_RESET, inventory()));
        assertEquals("limit", buy(loaded, "first", purchaseRequest(), AFTER_RESET.plusSeconds(604800), inventory()));
    }

    @Test
    void staleAndOffSaleRequestsLeaveTheInventoryAndSalesUnchanged() {
        var data = shop(1, 0, 0);
        var request = purchaseRequest();
        var inventory = inventory();
        request.putLong("version", 0);
        assertEquals("stale", buy(data, "first", request, BEFORE_RESET, inventory));
        request = purchaseRequest();
        data.products.getCompound(0).putLong("start", AFTER_RESET.getEpochSecond());
        assertEquals("unavailable", buy(data, "first", request, BEFORE_RESET, inventory));
        data.products.getCompound(0).putLong("end", AFTER_RESET.plusSeconds(20).getEpochSecond());
        assertEquals("unavailable", buy(data, "first", purchaseRequest(), AFTER_RESET.plusSeconds(20), inventory));
        assertTrue(inventory.getFirst().is(Items.EMERALD));
        assertTrue(data.transactions.isEmpty());
        assertEquals(1, data.products.getCompound(0).getInt("stock"));
    }

    @Test
    void chosenRewardIsExclusiveAndCannotBeBoughtInBulk() {
        var data = shop(-1, 0, 0);
        var product = data.products.getCompound(0);
        product.putBoolean("choice", true);
        var alternatives = product.getList("options", Tag.TAG_COMPOUND);
        alternatives.add(reward(new ItemStack(Items.GOLD_INGOT)));
        var request = purchaseRequest();
        request.putInt("option", 1);
        request.putInt("amount", 2);
        var inventory = inventory();
        assertEquals("invalid_quantity", buy(data, "first", request, BEFORE_RESET, inventory));
        assertTrue(inventory.getFirst().is(Items.EMERALD));

        request = purchaseRequest();
        request.putInt("option", 1);
        assertEquals("ok", buy(data, "first", request, BEFORE_RESET, inventory));
        assertTrue(inventory.getFirst().is(Items.GOLD_INGOT));
    }

    @Test
    void batchCountsUnitsAgainstPersonalLimitAndSharedStock() {
        var data = shop(3, 2, 0);
        data.products.getCompound(0).putBoolean("batch", true);
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.EMERALD, 2)));
        var request = purchaseRequest();
        request.putInt("amount", 2);

        assertEquals("ok", buy(data, "first", request, BEFORE_RESET, inventory));
        assertTrue(inventory.getFirst().is(Items.DIAMOND));
        assertEquals(2, inventory.getFirst().getCount());
        assertEquals(1, data.products.getCompound(0).getInt("stock"));
        assertEquals("limit", buy(data, "first", purchaseRequest(), BEFORE_RESET, inventory()));
    }

    @Test
    void unreadableSavedDataIsNotReplacedWithAnEmptyShop(@TempDir Path folder) throws IOException {
        Path saveFile = folder.resolve("rabbitquest_shop.dat");
        Files.writeString(saveFile, "corrupt shop");
        var storage = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries());

        assertNull(ShopSavedData.get(storage, folder));
        assertEquals("corrupt shop", Files.readString(saveFile));
    }

    private static String buy(ShopSavedData data, String player, CompoundTag request, Instant time,
                              List<ItemStack> inventory) {
        return ShopService.buy(data, player, request, request.getString("requestId"), time, registries(), inventory);
    }

    private static HolderLookup.Provider registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static List<ItemStack> inventory() {
        return new ArrayList<>(List.of(new ItemStack(Items.EMERALD)));
    }

    private static CompoundTag purchaseRequest() {
        var request = new CompoundTag();
        request.putString("id", "rabbitquest:demo");
        request.putString("requestId", UUID.randomUUID().toString());
        request.putLong("version", 1);
        request.putInt("amount", 1);
        request.putInt("option", 0);
        return request;
    }

    private static ShopSavedData shop(int stock, int totalLimit, int weeklyLimit) {
        var data = new ShopSavedData();
        var category = new CompoundTag();
        category.putString("id", "rabbitquest:shop");
        category.putBoolean("visible", true);
        data.categories.add(category);

        var product = new CompoundTag();
        product.putString("id", "rabbitquest:demo");
        product.putString("category", "rabbitquest:shop");
        product.putLong("version", 1);
        product.putBoolean("enabled", true);
        product.putInt("stock", stock);
        product.putInt("totalLimit", totalLimit);
        product.putInt("weeklyLimit", weeklyLimit);
        var cost = new CompoundTag();
        cost.put("item", new ItemStack(Items.EMERALD).save(registries()));
        cost.putInt("count", 1);
        var costs = new ListTag();
        costs.add(cost);
        product.put("costs", costs);
        var options = new ListTag();
        options.add(reward(new ItemStack(Items.DIAMOND)));
        product.put("options", options);
        data.products.add(product);
        return data;
    }

    private static CompoundTag reward(ItemStack stack) {
        var items = new ListTag();
        items.add(stack.save(registries()));
        var reward = new CompoundTag();
        reward.put("items", items);
        return reward;
    }
}
