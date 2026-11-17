package com.tr4nce.rabbitquest.event;

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
import java.util.UUID;

/**
 * Overworld-saved timed event state: per-player enrollment, step progress, frozen rewards,
 * completion claims, idempotent request receipts and admin audit entries.
 */
public final class EventSavedData extends SavedData {
    static final int FORMAT = 1;
    static final String ID = "rabbitquest_event";

    private static final Factory<EventSavedData> FACTORY = new Factory<>(
            EventSavedData::new, EventSavedData::load);

    private final CompoundTag players;
    private final ListTag audit;

    EventSavedData() {
        this(new CompoundTag());
    }

    private EventSavedData(CompoundTag data) {
        players = data.getCompound("players");
        audit = data.getList("audit", Tag.TAG_COMPOUND);
    }

    public static EventSavedData get(MinecraftServer server) {
        return get(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data"));
    }

    static EventSavedData get(DimensionDataStorage storage, Path folder) {
        EventSavedData data = storage.get(FACTORY, ID);
        if (data != null || !Files.notExists(folder.resolve(ID + ".dat"))) {
            return data;
        }
        data = new EventSavedData();
        storage.set(ID, data);
        return data;
    }

    static EventSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported RabbitQuest event format: " + tag.getInt("format"));
        }
        return new EventSavedData(tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT);
        tag.put("players", players.copy());
        tag.put("audit", audit.copy());
        return tag;
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

    public CompoundTag event(UUID player, ResourceLocation eventId) {
        return player(player).getCompound("events").getCompound(eventId.toString());
    }

    void putEvent(UUID player, ResourceLocation eventId, CompoundTag eventTag) {
        CompoundTag playerTag = playerOrCreate(player);
        CompoundTag events = playerTag.getCompound("events");
        events.put(eventId.toString(), eventTag);
        playerTag.put("events", events);
        setDirty();
    }

    void removeEvent(UUID player, ResourceLocation eventId) {
        CompoundTag playerTag = player(player);
        if (playerTag.isEmpty()) {
            return;
        }
        CompoundTag events = playerTag.getCompound("events");
        if (events.contains(eventId.toString())) {
            events.remove(eventId.toString());
            playerTag.put("events", events);
            players.put(player.toString(), playerTag);
            setDirty();
        }
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
