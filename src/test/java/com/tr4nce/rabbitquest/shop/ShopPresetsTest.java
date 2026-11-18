package com.tr4nce.rabbitquest.shop;

import com.tr4nce.rabbitquest.item.VoucherItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPresetsTest {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test
    void installsWeaponAndAllStainedGlassShopsAndSupportsPurchases() {
        ShopSavedData data = new ShopSavedData();
        int count = ShopPresets.install(data, REGISTRIES, ShopPresets.Target.ALL);
        assertEquals(19, count);
        assertNotNull(ShopSavedData.find(data.categories, "rabbitquest:weapon_shop"));
        assertNotNull(ShopSavedData.find(data.categories, "rabbitquest:glass_shop"));
        assertNotNull(ShopSavedData.find(data.products, "rabbitquest:weapon_netherite_sword"));
        assertNotNull(ShopSavedData.find(data.products, "rabbitquest:weapon_netherite_axe"));
        assertNotNull(ShopSavedData.find(data.products, "rabbitquest:weapon_netherite_choice"));
        assertNotNull(ShopSavedData.find(data.products, "rabbitquest:glass_white"));
        assertNotNull(ShopSavedData.find(data.products, "rabbitquest:glass_black"));

        NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
        inventory.set(0, new ItemStack(VoucherItems.WEAPON_TOOL_VOUCHER.get(), 2));
        inventory.set(1, new ItemStack(VoucherItems.BUILDING_MATERIAL_VOUCHER.get(), 2));

        // Buy Netherite Axe via choice option 1
        CompoundTag choiceReq = new CompoundTag();
        choiceReq.putString("id", "rabbitquest:weapon_netherite_choice");
        choiceReq.putLong("version", ShopSavedData.find(data.products, "rabbitquest:weapon_netherite_choice").getLong("version"));
        choiceReq.putInt("amount", 1);
        choiceReq.putInt("option", 1);
        assertEquals("ok", ShopService.buy(data, UUID.randomUUID().toString(), choiceReq,
                UUID.randomUUID().toString(), Instant.now(), REGISTRIES, inventory));
        assertTrue(inventory.stream().anyMatch(s -> s.is(Items.NETHERITE_AXE)));

        // Bulk buy 2 stacks (128) of cyan stained glass
        CompoundTag glassReq = new CompoundTag();
        glassReq.putString("id", "rabbitquest:glass_cyan");
        glassReq.putLong("version", ShopSavedData.find(data.products, "rabbitquest:glass_cyan").getLong("version"));
        glassReq.putInt("amount", 2);
        glassReq.putInt("option", 0);
        assertEquals("ok", ShopService.buy(data, UUID.randomUUID().toString(), glassReq,
                UUID.randomUUID().toString(), Instant.now(), REGISTRIES, inventory));
        assertEquals(128, inventory.stream().filter(s -> s.is(Items.CYAN_STAINED_GLASS))
                .mapToInt(ItemStack::getCount).sum());
    }
}
