package com.tr4nce.rabbitquest.stats;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class StatsSavedData extends SavedData {
    private static final int FORMAT = 1;
    private static final String ID = "rabbitquest_stats";
    private static final Factory<StatsSavedData> FACTORY = new Factory<>(
            StatsSavedData::new, StatsSavedData::load);

    final CompoundTag players;

    StatsSavedData() {
        this(new CompoundTag());
    }

    private StatsSavedData(CompoundTag data) {
        players = data.getCompound("players");
    }

    static StatsSavedData get(MinecraftServer server) {
        return get(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static StatsSavedData get(DimensionDataStorage storage, Path folder) {
        StatsSavedData data = storage.get(FACTORY, ID);
        if (data != null || !Files.notExists(folder.resolve(ID + ".dat"))) {
            return data;
        }
        data = new StatsSavedData();
        storage.set(ID, data);
        return data;
    }

    static StatsSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported RabbitQuest stats format: " + tag.getInt("format"));
        }
        return new StatsSavedData(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.put("players", players.copy());
        return tag;
    }

    void record(UUID player, ActionType type, ResourceLocation target, double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be positive and finite");
        }
        CompoundTag stats = players.getCompound(player.toString());
        switch (type) {
            case HARVEST -> {
                long count = Math.round(amount);
                stats.putLong("harvest", stats.getLong("harvest") + count);
                CompoundTag blocks = stats.getCompound("blocks");
                blocks.putLong(target.toString(), blocks.getLong(target.toString()) + count);
                stats.put("blocks", blocks);
            }
            case KILL -> {
                long count = Math.round(amount);
                stats.putLong("kills", stats.getLong("kills") + count);
                CompoundTag entities = stats.getCompound("entities");
                CompoundTag entity = entities.getCompound(target.toString());
                entity.putLong("kills", entity.getLong("kills") + count);
                entities.put(target.toString(), entity);
                stats.put("entities", entities);
            }
            case DAMAGE -> {
                stats.putDouble("damage", stats.getDouble("damage") + amount);
                CompoundTag entities = stats.getCompound("entities");
                CompoundTag entity = entities.getCompound(target.toString());
                entity.putDouble("damage", entity.getDouble("damage") + amount);
                entities.put(target.toString(), entity);
                stats.put("entities", entities);
            }
        }
        players.put(player.toString(), stats);
        setDirty();
    }

    Totals totals(UUID player) {
        CompoundTag stats = players.getCompound(player.toString());
        return new Totals(stats.getLong("harvest"), stats.getLong("kills"), stats.getDouble("damage"));
    }

    long harvested(UUID player, ResourceLocation block) {
        return players.getCompound(player.toString()).getCompound("blocks").getLong(block.toString());
    }

    EntityTotals entity(UUID player, ResourceLocation entity) {
        CompoundTag totals = players.getCompound(player.toString()).getCompound("entities")
                .getCompound(entity.toString());
        return new EntityTotals(totals.getLong("kills"), totals.getDouble("damage"));
    }

    record Totals(long harvested, long kills, double damage) {}

    record EntityTotals(long kills, double damage) {}
}
