package com.tr4nce.rabbitquest.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic inventory serialization and fingerprinting used by the write-ahead transaction
 * journal to detect whether player inventory changes survived a crash.
 */
public final class InventorySnapshot {
    private InventorySnapshot() {}

    public static String fingerprint(List<ItemStack> inventory, HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        root.put("slots", saveSlots(inventory, registries));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, new DataOutputStream(bytes));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to fingerprint inventory", exception);
        }
    }

    public static ListTag saveSlots(List<ItemStack> inventory, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", slot);
                entry.put("item", stack.save(registries));
                list.add(entry);
            }
        }
        return list;
    }

    public static void applySlots(List<ItemStack> inventory, ListTag slots, HolderLookup.Provider registries) {
        for (int i = 0; i < inventory.size(); i++) {
            inventory.set(i, ItemStack.EMPTY);
        }
        for (Tag tag : slots) {
            CompoundTag entry = (CompoundTag) tag;
            int slot = entry.getInt("slot");
            if (slot >= 0 && slot < inventory.size()) {
                ItemStack stack = ItemStack.parse(registries, entry.getCompound("item")).orElse(ItemStack.EMPTY);
                inventory.set(slot, stack);
            }
        }
    }
}
