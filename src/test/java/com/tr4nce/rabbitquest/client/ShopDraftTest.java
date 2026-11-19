package com.tr4nce.rabbitquest.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopDraftTest {
    @Test
    void detectsUnsavedTextAndItemCountsIncludingInvalidInput() {
        CompoundTag product = new CompoundTag();
        product.putString("name", "Voucher");
        ListTag costs = new ListTag();
        CompoundTag cost = new CompoundTag();
        cost.putInt("count", 2);
        costs.add(cost);
        product.put("costs", costs);

        ShopDraft draft = new ShopDraft(false, true, "rabbitquest:voucher", product, 1);
        assertFalse(draft.isDirty());

        draft.order = "-";
        assertTrue(draft.isDirty());
        draft.order = "0";
        assertFalse(draft.isDirty());

        CompoundTag row = draft.data.getList("costs", Tag.TAG_COMPOUND).getCompound(0);
        draft.setCount(row, "");
        assertTrue(draft.isDirty());
        draft.setCount(row, "2");
        assertFalse(draft.isDirty());

        draft.data.putString("name", "Updated voucher");
        assertTrue(draft.isDirty());
    }
}
