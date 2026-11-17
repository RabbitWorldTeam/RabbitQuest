package com.tr4nce.rabbitquest.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous write-ahead transaction journal stored separately from Minecraft SavedData and player
 * files. Entries are written atomically before any dual-file mutation begins so crash recovery can
 * deterministically reconcile partial saves.
 */
public final class TransactionJournal {
    public enum Domain { SHOP, WEEKLY, EVENT }

    public enum State { PENDING, COMMITTED, ABORTED }

    private static final Logger LOGGER = LoggerFactory.getLogger("rabbitquest-wal");
    private static final int FORMAT = 1;
    private static final String FILE_NAME = "rabbitquest_tx.dat";

    private final Path file;
    private final Map<String, CompoundTag> entries = new LinkedHashMap<>();

    public TransactionJournal(Path file) {
        this.file = file;
        load();
    }

    public static TransactionJournal open(MinecraftServer server) {
        return new TransactionJournal(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME));
    }

    public synchronized void begin(String txId, Domain domain, String action, UUID playerId,
                                   String beforeHash, String afterHash, ListTag afterSlots,
                                   CompoundTag domainPayload) {
        CompoundTag entry = new CompoundTag();
        entry.putString("txId", txId);
        entry.putString("domain", domain.name());
        entry.putString("action", action);
        entry.putString("player", playerId.toString());
        entry.putString("state", State.PENDING.name());
        entry.putLong("createdAt", System.currentTimeMillis());
        entry.putString("beforeHash", beforeHash);
        entry.putString("afterHash", afterHash);
        entry.put("afterSlots", afterSlots.copy());
        entry.put("payload", domainPayload.copy());
        entries.put(txId, entry);
        flush();
    }

    public synchronized void markCommitted(String txId) {
        CompoundTag entry = entries.get(txId);
        if (entry != null) {
            entry.putString("state", State.COMMITTED.name());
            entry.putLong("resolvedAt", System.currentTimeMillis());
            flush();
        }
    }

    public synchronized void markAborted(String txId) {
        CompoundTag entry = entries.get(txId);
        if (entry != null) {
            entry.putString("state", State.ABORTED.name());
            entry.putLong("resolvedAt", System.currentTimeMillis());
            flush();
        }
    }

    public synchronized List<CompoundTag> pendingFor(UUID playerId) {
        List<CompoundTag> result = new ArrayList<>();
        String target = playerId.toString();
        for (CompoundTag entry : entries.values()) {
            if (State.PENDING.name().equals(entry.getString("state"))
                    && target.equals(entry.getString("player"))) {
                result.add(entry.copy());
            }
        }
        return result;
    }

    public synchronized State state(String txId) {
        CompoundTag entry = entries.get(txId);
        if (entry == null) {
            return null;
        }
        return State.valueOf(entry.getString("state"));
    }

    private void load() {
        if (file == null || Files.notExists(file)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (root.getInt("format") != FORMAT) {
                return;
            }
            for (Tag tag : root.getList("entries", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) tag;
                entries.put(entry.getString("txId"), entry);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to read transaction journal {}", file, exception);
        }
    }

    private void flush() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.putInt("format", FORMAT);
            ListTag list = new ListTag();
            for (CompoundTag entry : entries.values()) {
                list.add(entry.copy());
            }
            root.put("entries", list);
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            LOGGER.error("Failed to flush transaction journal {}", file, exception);
        }
    }
}
