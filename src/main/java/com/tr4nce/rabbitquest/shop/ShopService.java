package com.tr4nce.rabbitquest.shop;

import com.tr4nce.rabbitquest.network.ShopNetwork;
import com.tr4nce.rabbitquest.storage.InventorySnapshot;
import com.tr4nce.rabbitquest.storage.RecoveryService;
import com.tr4nce.rabbitquest.storage.TransactionJournal;
import com.tr4nce.rabbitquest.weekly.WeeklyCycle;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ShopService {
    private static final String RESPONSE_PREFIX = "rabbitquest.shop.result.";
    // Leave room for per-player fields and the network payload envelope.
    private static final int MAX_CATALOG_BYTES = 1024 * 1024;

    private ShopService() {}

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rabbitquest")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ShopSavedData data = ShopSavedData.get(player.server);
                    if (data == null) {
                        context.getSource().sendFailure(Component.translatable(RESPONSE_PREFIX + "storage"));
                        return 0;
                    }
                    send(player, data, true, "", "");
                    return 1;
                })
                .then(Commands.literal("shop")
                        .then(Commands.literal("preset").requires(source -> source.hasPermission(2))
                                .then(Commands.literal("materials")
                                        .executes(context -> installMaterials(context.getSource()))))));
    }

    private static int installMaterials(net.minecraft.commands.CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ShopSavedData data = ShopSavedData.get(server);
        if (data == null) {
            source.sendFailure(Component.translatable(RESPONSE_PREFIX + "storage"));
            return 0;
        }
        int count = ShopPresets.installMaterials(data, server.registryAccess());
        push(server, data, null);
        source.sendSuccess(() -> Component.translatable("rabbitquest.shop.preset.ok", count), true);
        return 1;
    }

    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ShopSavedData data = ShopSavedData.get(player.server);
            if (data != null) {
                send(player, data, false, "", "");
            }
        }
    }

    public static void handle(ServerPlayer player, CompoundTag request) {
        ShopSavedData data = ShopSavedData.get(player.server);
        String action = request.getString("action");
        String requestId = request.getString("requestId");
        if (data == null) {
            if (action.equals("open")) {
                player.sendSystemMessage(Component.translatable(RESPONSE_PREFIX + "storage"));
            } else {
                send(player, new ShopSavedData(), false, "storage", isRequestId(requestId) ? requestId : "");
            }
            return;
        }
        if (action.equals("open")) {
            send(player, data, true, "", "");
            return;
        }
        if (!isRequestId(requestId)) {
            send(player, data, false, "invalid", "");
            return;
        }

        long revision = data.revision;
        String outcome = switch (action) {
            case "buy" -> {
                TransactionJournal wal = RecoveryService.journal();
                String beforeHash = wal == null ? ""
                        : InventorySnapshot.fingerprint(player.getInventory().items, player.registryAccess());
                int previousSales = data.transactions.size();
                String result = buy(data, player.getStringUUID(), request, requestId, Instant.now(),
                        player.registryAccess(), player.getInventory().items);
                if (previousSales != data.transactions.size()) {
                    if (wal != null) {
                        CompoundTag tx = data.transactions.getCompound(data.transactions.size() - 1);
                        String afterHash = InventorySnapshot.fingerprint(
                                player.getInventory().items, player.registryAccess());
                        wal.begin(requestId, TransactionJournal.Domain.SHOP, "BUY", player.getUUID(),
                                beforeHash, afterHash,
                                InventorySnapshot.saveSlots(player.getInventory().items, player.registryAccess()), tx);
                        wal.markCommitted(requestId);
                    }
                    player.getInventory().setChanged();
                    player.containerMenu.broadcastChanges();
                }
                yield result;
            }
            case "save_category", "save_product" -> edit(player, data, request, action.equals("save_category"));
            default -> "invalid";
        };
        send(player, data, false, outcome, requestId);
        if (revision != data.revision) {
            push(player.server, data, player);
        }
    }

    private static String edit(ServerPlayer player, ShopSavedData data, CompoundTag request, boolean category) {
        if (!player.hasPermissions(2)) {
            return "denied";
        }
        if (!request.contains("revision", Tag.TAG_LONG) || request.getLong("revision") != data.revision) {
            return "stale";
        }
        CompoundTag entry;
        try {
            ShopEdits edits = new ShopEdits(player, data);
            entry = category ? edits.category(request.getCompound("category"))
                    : edits.product(request.getCompound("product"));
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
        if (!category) {
            entry.putLong("version", data.revision + 1);
        }
        ListTag entries = category ? data.categories : data.products;
        boolean exists = ShopSavedData.find(entries, entry.getString("id")) != null;
        if (!request.contains("create", Tag.TAG_BYTE)) {
            return "invalid";
        }
        if (request.getBoolean("create") == exists) {
            return exists ? "duplicate_id" : "stale";
        }
        ListTag candidate = entries.copy();
        ShopSavedData.put(candidate, entry);
        int otherSize = (category ? data.products : data.categories).sizeInBytes();
        if (candidate.sizeInBytes() + otherSize > MAX_CATALOG_BYTES) {
            return "invalid";
        }
        ShopSavedData.put(entries, entry);
        data.revision++;
        if (category) {
            for (Tag tag : data.products) {
                CompoundTag product = (CompoundTag) tag;
                if (product.getString("category").equals(entry.getString("id"))) {
                    product.putLong("version", data.revision);
                }
            }
        }
        data.setDirty();
        return "ok";
    }

    static String buy(ShopSavedData data, String uuid, CompoundTag request, String requestId,
                      Instant now, HolderLookup.Provider registries, List<ItemStack> inventory) {
        CompoundTag playerData = data.player(uuid);
        CompoundTag receipts = playerData.getCompound("receipts");
        if (receipts.contains(requestId, Tag.TAG_STRING)) {
            return receipts.getString(requestId);
        }
        String outcome = executePurchase(data, uuid, request, requestId, now, registries, inventory, playerData);
        receipts.putString(requestId, outcome);
        playerData.put("receipts", receipts);
        data.updatePlayer(uuid, playerData);
        return outcome;
    }

    private static String executePurchase(ShopSavedData data, String uuid, CompoundTag request, String requestId,
                                          Instant now, HolderLookup.Provider registries, List<ItemStack> inventory,
                                          CompoundTag playerData) {
        String id = request.getString("id");
        CompoundTag product = ShopSavedData.find(data.products, id);
        if (product == null || !request.contains("version", Tag.TAG_LONG)
                || product.getLong("version") != request.getLong("version")) {
            return "stale";
        }
        if (!available(data, product, now)) {
            return "unavailable";
        }

        int amount = request.getInt("amount");
        int selected = request.getInt("option");
        ListTag options = product.getList("options", Tag.TAG_COMPOUND);
        if (!request.contains("amount", Tag.TAG_INT) || !request.contains("option", Tag.TAG_INT)
                || amount < 1 || amount > 1 && !product.getBoolean("batch")
                || selected < 0 || selected >= options.size()) {
            return "invalid_quantity";
        }

        String week = WeeklyCycle.startDate(now).toString();
        CompoundTag purchases = playerData.getCompound("purchases");
        CompoundTag counter = purchases.getCompound(id);
        long total = counter.getLong("total");
        int weekly = weeklyPurchases(counter, week);
        if (exceeds(total, amount, product.getInt("totalLimit"))
                || exceeds(weekly, amount, product.getInt("weeklyLimit"))) {
            return "limit";
        }
        int stock = product.getInt("stock");
        if (stock >= 0 && amount > stock) {
            return "stock";
        }

        List<TradeInventory.Cost> costs = new ArrayList<>();
        for (Tag row : product.getList("costs", Tag.TAG_COMPOUND)) {
            CompoundTag cost = (CompoundTag) row;
            ItemStack item = parse(registries, cost.getCompound("item"));
            if (item.isEmpty()) {
                return "unavailable";
            }
            costs.add(new TradeInventory.Cost(item, cost.getInt("count"), cost.getBoolean("exact")));
        }
        List<ItemStack> rewards = new ArrayList<>();
        for (Tag item : options.getCompound(selected).getList("items", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) item;
            ItemStack stack = parse(registries, entry);
            if (stack.isEmpty()) {
                return "unavailable";
            }
            int rewardTotal = entry.contains("rewardCount", Tag.TAG_INT)
                    ? entry.getInt("rewardCount") : stack.getCount();
            if (rewardTotal <= 0) {
                return "unavailable";
            }
            int max = Math.max(1, stack.getMaxStackSize());
            while (rewardTotal > 0) {
                int chunk = Math.min(rewardTotal, max);
                rewards.add(stack.copyWithCount(chunk));
                rewardTotal -= chunk;
            }
        }

        var result = TradeInventory.plan(inventory, costs, rewards, amount);
        if (!result.success()) {
            return switch (result.failure()) {
                case MISSING_COST -> "missing_cost";
                case INVENTORY_FULL -> "inventory_full";
                case INVALID_QUANTITY -> "invalid_quantity";
            };
        }

        CompoundTag transaction = new CompoundTag();
        transaction.putString("requestId", requestId);
        transaction.putString("player", uuid);
        transaction.putLong("time", now.toEpochMilli());
        transaction.putString("product", id);
        transaction.putLong("version", product.getLong("version"));
        transaction.putInt("amount", amount);
        transaction.putInt("option", selected);
        transaction.put("removed", serialize(registries, result.removed()));
        transaction.put("granted", serialize(registries, result.granted()));

        // Everything above reads current state or constructs copies. Commit on the server thread.
        for (int slot = 0; slot < result.slots().size(); slot++) {
            inventory.set(slot, result.slots().get(slot));
        }
        if (stock >= 0) {
            product.putInt("stock", stock - amount);
            data.revision++;
        }
        counter.putLong("total", total + amount);
        counter.putString("week", week);
        counter.putInt("weekly", (int) Math.min(Integer.MAX_VALUE, (long) weekly + amount));
        purchases.put(id, counter);
        playerData.put("purchases", purchases);
        data.transactions.add(transaction);
        return "ok";
    }

    private static ItemStack parse(HolderLookup.Provider registries, Tag tag) {
        return ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
    }

    private static ListTag serialize(HolderLookup.Provider registries, List<ItemStack> items) {
        ListTag result = new ListTag();
        for (ItemStack stack : items) {
            result.add(stack.save(registries));
        }
        return result;
    }

    private static void push(MinecraftServer server, ShopSavedData data, ServerPlayer requester) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != requester) {
                send(player, data, false, "", "");
            }
        }
    }

    private static void send(ServerPlayer player, ShopSavedData data, boolean open, String message, String requestId) {
        boolean admin = player.hasPermissions(2);
        ListTag categories = new ListTag();
        for (Tag category : data.categories) {
            if (admin || ((CompoundTag) category).getBoolean("visible")) {
                categories.add(category.copy());
            }
        }
        Instant now = Instant.now();
        String week = WeeklyCycle.startDate(now).toString();
        CompoundTag counters = data.player(player.getStringUUID()).getCompound("purchases");
        ListTag products = new ListTag();
        for (Tag tag : data.products) {
            CompoundTag product = (CompoundTag) tag;
            boolean available = available(data, product, now);
            boolean listed = listed(data, product);
            if (admin || listed) {
                CompoundTag view = product.copy();
                CompoundTag counter = counters.getCompound(view.getString("id"));
                view.putBoolean("available", available);
                if (!available && listed) {
                    view.putString("windowStatus", product.contains("start")
                            && now.getEpochSecond() < product.getLong("start") ? "upcoming" : "ended");
                }
                view.putInt("purchased", (int) Math.min(counter.getLong("total"), Integer.MAX_VALUE));
                view.putInt("weeklyPurchased", weeklyPurchases(counter, week));
                products.add(view);
            }
        }
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("type", "snapshot");
        snapshot.putBoolean("open", open);
        snapshot.putBoolean("admin", admin);
        snapshot.putLong("revision", data.revision);
        snapshot.put("categories", categories);
        snapshot.put("products", products);
        if (!message.isEmpty()) {
            snapshot.putString("message", RESPONSE_PREFIX + message);
        }
        if (!requestId.isEmpty()) {
            snapshot.putString("requestId", requestId);
        }
        ShopNetwork.send(player, snapshot);
    }

    private static int weeklyPurchases(CompoundTag counter, String week) {
        return counter.getString("week").equals(week) ? counter.getInt("weekly") : 0;
    }

    private static boolean exceeds(long purchased, int amount, int limit) {
        return limit > 0 && purchased > (long) limit - amount;
    }

    private static boolean listed(ShopSavedData data, CompoundTag product) {
        CompoundTag category = ShopSavedData.find(data.categories, product.getString("category"));
        return category != null && category.getBoolean("visible") && product.getBoolean("enabled");
    }

    private static boolean available(ShopSavedData data, CompoundTag product, Instant now) {
        long second = now.getEpochSecond();
        return listed(data, product)
                && (!product.contains("start") || second >= product.getLong("start"))
                && (!product.contains("end") || second < product.getLong("end"));
    }

    public static RecoveryService.DomainHandler recoveryHandler(MinecraftServer server) {
        return recoveryHandler(ShopSavedData.get(server));
    }

    public static RecoveryService.DomainHandler recoveryHandler(ShopSavedData data) {
        return new RecoveryService.DomainHandler() {
            @Override
            public boolean isApplied(UUID playerId, String txId) {
                if (data == null) {
                    return false;
                }
                return data.player(playerId.toString()).getCompound("receipts").contains(txId, Tag.TAG_STRING);
            }

            @Override
            public boolean replayWorld(UUID playerId, String txId, String action, CompoundTag payload,
                                       HolderLookup.Provider registries) {
                if (data == null || !"BUY".equals(action)) {
                    return false;
                }
                String uuid = playerId.toString();
                CompoundTag playerData = data.player(uuid);
                CompoundTag receipts = playerData.getCompound("receipts");
                if (receipts.contains(txId, Tag.TAG_STRING)) {
                    return true;
                }
                String productId = payload.getString("product");
                int amount = Math.max(1, payload.getInt("amount"));
                long time = payload.getLong("time");
                CompoundTag product = ShopSavedData.find(data.products, productId);
                if (product != null && product.getInt("stock") >= 0) {
                    product.putInt("stock", Math.max(0, product.getInt("stock") - amount));
                    data.revision++;
                }
                String week = WeeklyCycle.startDate(Instant.ofEpochMilli(time)).toString();
                CompoundTag purchases = playerData.getCompound("purchases");
                CompoundTag counter = purchases.getCompound(productId);
                int prevWeekly = counter.getString("week").equals(week) ? counter.getInt("weekly") : 0;
                counter.putLong("total", counter.getLong("total") + amount);
                counter.putString("week", week);
                counter.putInt("weekly", (int) Math.min(Integer.MAX_VALUE, (long) prevWeekly + amount));
                purchases.put(productId, counter);
                playerData.put("purchases", purchases);
                receipts.putString(txId, "ok");
                playerData.put("receipts", receipts);
                data.updatePlayer(uuid, playerData);
                data.transactions.add(payload.copy());
                data.setDirty();
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
