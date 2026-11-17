package com.tr4nce.rabbitquest.weekly;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Overworld-saved weekly state: first-week anchor, selected order per cycle, per-player progress
 * keyed by UUID/definition/cycle, claim receipts and admin audit entries.
 */
public final class WeeklySavedData extends SavedData {
    static final int FORMAT = 1;
    static final String ID = "rabbitquest_weekly";

    private static final Factory<WeeklySavedData> FACTORY = new Factory<>(
            WeeklySavedData::new, WeeklySavedData::load);

    private final CompoundTag orders;
    private final CompoundTag players;
    private final ListTag audit;
    private String anchor;

    WeeklySavedData() {
        this(new CompoundTag());
    }

    private WeeklySavedData(CompoundTag data) {
        anchor = data.getString("anchor");
        orders = data.getCompound("orders");
        players = data.getCompound("players");
        audit = data.getList("audit", Tag.TAG_COMPOUND);
    }

    public static WeeklySavedData get(MinecraftServer server) {
        return get(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static WeeklySavedData get(DimensionDataStorage storage, Path folder) {
        WeeklySavedData data = storage.get(FACTORY, ID);
        if (data != null || !Files.notExists(folder.resolve(ID + ".dat"))) {
            return data;
        }
        data = new WeeklySavedData();
        storage.set(ID, data);
        return data;
    }

    static WeeklySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported RabbitQuest weekly format: " + tag.getInt("format"));
        }
        return new WeeklySavedData(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.putString("anchor", anchor == null ? "" : anchor);
        tag.put("orders", orders.copy());
        tag.put("players", players.copy());
        tag.put("audit", audit.copy());
        return tag;
    }

    public String anchor() {
        return anchor == null || anchor.isEmpty() ? null : anchor;
    }

    public void ensureAnchor(String week) {
        if (anchor == null || anchor.isEmpty()) {
            anchor = week;
            setDirty();
        }
    }

    public String selectedOrder(String week) {
        String id = orders.getString(week);
        return id.isEmpty() ? null : id;
    }

    public void selectOrder(String week, String order) {
        orders.putString(week, order);
        setDirty();
    }

    CompoundTag player(UUID player) {
        return players.getCompound(player.toString());
    }

    private CompoundTag playerOrCreate(UUID player) {
        String key = player.toString();
        CompoundTag tag = players.getCompound(key);
        if (!players.contains(key, Tag.TAG_COMPOUND)) {
            players.put(key, tag);
        }
        return tag;
    }

    public CompoundTag task(UUID player, String week, ResourceLocation definition) {
        return player(player).getCompound("tasks").getCompound(week).getCompound(definition.toString());
    }

    void putTask(UUID player, String week, ResourceLocation definition, CompoundTag task) {
        CompoundTag tasks = playerOrCreate(player).getCompound("tasks");
        CompoundTag cycle = tasks.getCompound(week);
        cycle.put(definition.toString(), task);
        tasks.put(week, cycle);
        playerOrCreate(player).put("tasks", tasks);
        setDirty();
    }

    void removeTask(UUID player, String week, ResourceLocation definition) {
        CompoundTag playerTag = player(player);
        if (playerTag.isEmpty()) {
            return;
        }
        CompoundTag tasks = playerTag.getCompound("tasks");
        CompoundTag cycle = tasks.getCompound(week);
        if (cycle.contains(definition.toString())) {
            cycle.remove(definition.toString());
            if (cycle.isEmpty()) {
                tasks.remove(week);
            } else {
                tasks.put(week, cycle);
            }
            playerTag.put("tasks", tasks);
            players.put(player.toString(), playerTag);
            setDirty();
        }
    }

    List<CompoundTag> tasks(UUID player) {
        List<CompoundTag> result = new ArrayList<>();
        CompoundTag tasks = player(player).getCompound("tasks");
        for (String week : tasks.getAllKeys()) {
            CompoundTag cycle = tasks.getCompound(week);
            for (String definition : cycle.getAllKeys()) {
                CompoundTag task = cycle.getCompound(definition);
                if (!task.isEmpty()) {
                    result.add(task);
                }
            }
        }
        return result;
    }

    String receipt(UUID player, String requestId) {
        CompoundTag receipts = player(player).getCompound("receipts");
        return receipts.contains(requestId, Tag.TAG_STRING) ? receipts.getString(requestId) : null;
    }

    void putReceipt(UUID player, String requestId, String outcome) {
        CompoundTag playerTag = playerOrCreate(player);
        CompoundTag receipts = playerTag.getCompound("receipts");
        receipts.putString(requestId, outcome);
        playerTag.put("receipts", receipts);
        players.put(player.toString(), playerTag);
        setDirty();
    }

    ListTag audit() {
        return audit;
    }

    void addAudit(CompoundTag entry) {
        audit.add(entry);
        setDirty();
    }
}
