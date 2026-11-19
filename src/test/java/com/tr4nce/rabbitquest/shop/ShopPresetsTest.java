package com.tr4nce.rabbitquest.shop;

import com.tr4nce.rabbitquest.item.VoucherItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPresetsTest {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test
    void materialsPresetPreservesEditsAndTradesExactlyTenStacks(@TempDir Path folder) throws java.io.IOException {
        ShopSavedData data = new ShopSavedData();
        assertEquals(197, ShopPresets.installMaterials(data, REGISTRIES));
        assertEquals(7, data.categories.size());
        for (Tag tag : data.products) {
            CompoundTag product = (CompoundTag) tag;
            var cost = product.getList("costs", Tag.TAG_COMPOUND).getCompound(0);
            assertEquals(1, cost.getInt("count"));
            assertEquals("rabbitquest:building_material_voucher", cost.getCompound("item").getString("id"));
            var reward = product.getList("options", Tag.TAG_COMPOUND).getCompound(0)
                    .getList("items", Tag.TAG_COMPOUND).getCompound(0);
            assertEquals(640, reward.getInt("rewardCount"));
            assertTrue(product.getBoolean("batch"));
        }

        Path saveFile = folder.resolve("rabbitquest_shop.dat");
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), REGISTRIES));
        NbtUtils.addCurrentDataVersion(root);
        NbtIo.writeCompressed(root, saveFile);
        data = ShopSavedData.load(NbtIo.readCompressed(saveFile, net.minecraft.nbt.NbtAccounter.unlimitedHeap())
                .getCompound("data"), REGISTRIES);

        var product = ShopSavedData.find(data.products, "rabbitquest:materials_blue_concrete");
        product.putString("name", "管理员自定义名称");
        long revision = data.revision;
        assertEquals(0, ShopPresets.installMaterials(data, REGISTRIES));
        assertEquals("管理员自定义名称", product.getString("name"));
        assertEquals(revision, data.revision);

        NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
        inventory.set(0, new ItemStack(VoucherItems.BUILDING_MATERIAL_VOUCHER.get(), 1));
        CompoundTag request = new CompoundTag();
        request.putString("id", product.getString("id"));
        request.putLong("version", product.getLong("version"));
        request.putInt("amount", 1);
        request.putInt("option", 0);
        NonNullList<ItemStack> crowded = NonNullList.withSize(36, ItemStack.EMPTY);
        crowded.set(0, new ItemStack(VoucherItems.BUILDING_MATERIAL_VOUCHER.get(), 1));
        for (int slot = 1; slot <= 27; slot++) {
            crowded.set(slot, new ItemStack(Items.DIRT, 64));
        }
        assertEquals("inventory_full", ShopService.buy(data, UUID.randomUUID().toString(), request,
                UUID.randomUUID().toString(), Instant.now(), REGISTRIES, crowded));
        assertEquals(1, crowded.getFirst().getCount());
        assertTrue(crowded.getFirst().is(VoucherItems.BUILDING_MATERIAL_VOUCHER.get()));

        assertEquals("ok", ShopService.buy(data, UUID.randomUUID().toString(), request,
                UUID.randomUUID().toString(), Instant.now(), REGISTRIES, inventory));
        long stacks = inventory.stream().filter(stack -> stack.is(Items.BLUE_CONCRETE) && stack.getCount() == 64).count();
        assertEquals(10, stacks);
        assertTrue(inventory.stream().noneMatch(stack -> stack.is(VoucherItems.BUILDING_MATERIAL_VOUCHER.get())));
    }
}
