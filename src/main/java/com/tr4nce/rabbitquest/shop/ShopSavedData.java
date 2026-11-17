package com.tr4nce.rabbitquest.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ShopSavedData extends SavedData {
    private static final int FORMAT = 1;
    private static final String ID = "rabbitquest_shop";
    private static final Factory<ShopSavedData> FACTORY = new Factory<>(
            ShopSavedData::new, ShopSavedData::load);

    final ListTag categories;
    final ListTag products;
    final CompoundTag players;
    final ListTag transactions;
    long revision;

    public ShopSavedData() {
        this(new CompoundTag());
    }

    private ShopSavedData(CompoundTag data) {
        categories = data.getList("categories", Tag.TAG_COMPOUND);
        products = data.getList("products", Tag.TAG_COMPOUND);
        players = data.getCompound("players");
        transactions = data.getList("transactions", Tag.TAG_COMPOUND);
        revision = data.getLong("revision");
    }

    static ShopSavedData get(MinecraftServer server) {
        return get(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static ShopSavedData get(DimensionDataStorage storage, Path folder) {
        ShopSavedData data = storage.get(FACTORY, ID);
        if (data != null || !Files.notExists(folder.resolve(ID + ".dat"))) {
            return data;
        }
        data = new ShopSavedData();
        storage.set(ID, data);
        return data;
    }

    static ShopSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported RabbitQuest shop format: " + tag.getInt("format"));
        }
        return new ShopSavedData(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.putLong("revision", revision);
        tag.put("categories", categories.copy());
        tag.put("products", products.copy());
        tag.put("players", players.copy());
        tag.put("transactions", transactions.copy());
        return tag;
    }

    static CompoundTag find(ListTag entries, String id) {
        for (Tag entry : entries) {
            CompoundTag tag = (CompoundTag) entry;
            if (tag.getString("id").equals(id)) {
                return tag;
            }
        }
        return null;
    }

    static void put(ListTag entries, CompoundTag entry) {
        CompoundTag previous = find(entries, entry.getString("id"));
        if (previous == null) {
            entries.add(entry);
        } else {
            entries.set(entries.indexOf(previous), entry);
        }
    }

    CompoundTag player(String uuid) {
        return players.getCompound(uuid);
    }

    void updatePlayer(String uuid, CompoundTag player) {
        players.put(uuid, player);
        setDirty();
    }
}
