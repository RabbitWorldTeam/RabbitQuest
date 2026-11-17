package com.tr4nce.rabbitquest.shop;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeInventoryTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void protectsExactCostWhenGenericCostCanMatchTheSameStack() {
        ItemStack namedIron = namedIron("Marked");
        ItemStack ordinaryIron = new ItemStack(Items.IRON_INGOT);
        var result = TradeInventory.plan(
            List.of(namedIron, ordinaryIron),
            List.of(new TradeInventory.Cost(ordinaryIron, 1, false),
                new TradeInventory.Cost(namedIron, 1, true)),
            List.of(new ItemStack(Items.DIAMOND)),
            1
        );

        assertTrue(result.success());
        assertEquals(2, result.removed().size());
        assertEquals(1, result.slots().stream().filter(stack -> stack.is(Items.DIAMOND)).count());
        assertEquals(1, namedIron.getCount());
        assertEquals(1, ordinaryIron.getCount());
    }

    @Test
    void exactCostRejectsDifferentComponents() {
        var result = TradeInventory.plan(
            List.of(namedIron("Different")),
            List.of(new TradeInventory.Cost(namedIron("Marked"), 1, true)),
            List.of(new ItemStack(Items.DIAMOND)),
            1
        );

        assertEquals(TradeInventory.Failure.MISSING_COST, result.failure());
    }

    @Test
    void matchesCannotDebitTheSameItemTwice() {
        ItemStack namedIron = namedIron("Marked");
        var result = TradeInventory.plan(
            List.of(namedIron),
            List.of(new TradeInventory.Cost(namedIron, 1, false),
                new TradeInventory.Cost(namedIron, 1, true)),
            List.of(new ItemStack(Items.DIAMOND)),
            1
        );

        assertEquals(TradeInventory.Failure.MISSING_COST, result.failure());
        assertEquals(1, namedIron.getCount());
    }

    @Test
    void costsCanFreeSlotsForMultipleRewardItems() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Reward"));
        var result = TradeInventory.plan(
            List.of(iron, gold),
            List.of(new TradeInventory.Cost(iron, 1, false), new TradeInventory.Cost(gold, 1, false)),
            List.of(sword, new ItemStack(Items.DIAMOND)),
            1
        );

        assertTrue(result.success());
        assertTrue(result.slots().stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(sword, stack)));
        assertTrue(result.slots().stream().anyMatch(stack -> stack.is(Items.DIAMOND)));
        assertEquals(2, result.granted().size());
        assertEquals(1, sword.getCount());
    }

    @Test
    void fullInventoryRejectsTradeWithoutConsumingAnyItems() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 3);
        var result = TradeInventory.plan(List.of(iron),
            List.of(new TradeInventory.Cost(iron, 1, false)),
            List.of(new ItemStack(Items.DIAMOND)), 1);

        assertEquals(TradeInventory.Failure.INVENTORY_FULL, result.failure());
        assertEquals(3, iron.getCount());
        assertTrue(result.removed().isEmpty());
        assertTrue(result.granted().isEmpty());
    }

    @Test
    void consumesSmallerMatchingStacksToFreeSpaceWhenPossible() {
        ItemStack large = new ItemStack(Items.IRON_INGOT, 50);
        ItemStack small = new ItemStack(Items.IRON_INGOT);
        var result = TradeInventory.plan(List.of(large, small),
            List.of(new TradeInventory.Cost(large, 1, false)),
            List.of(new ItemStack(Items.DIAMOND)), 1);

        assertTrue(result.success());
        assertEquals(50, result.slots().get(0).getCount());
        assertTrue(result.slots().get(1).is(Items.DIAMOND));
    }

    @Test
    void choosesGenericDebitThatLeavesRoomForAllRewardVariants() {
        ItemStack plain = new ItemStack(Items.IRON_INGOT);
        ItemStack named = namedIron("Marked");
        named.setCount(2);
        var result = TradeInventory.plan(List.of(plain, named),
            List.of(new TradeInventory.Cost(plain, 2, false)),
            List.of(plain, new ItemStack(Items.DIAMOND)), 1);

        assertTrue(result.success());
        assertEquals(2, result.slots().get(0).getCount());
        assertTrue(result.slots().get(1).is(Items.DIAMOND));
        assertEquals(1, result.removed().size());
        assertTrue(ItemStack.isSameItemSameComponents(named, result.removed().getFirst()));
    }

    @Test
    void batchScalesCostsAndRewardsAndMergesMatchingStacks() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 10);
        ItemStack diamond = new ItemStack(Items.DIAMOND, 58);
        var result = TradeInventory.plan(List.of(iron, diamond),
            List.of(new TradeInventory.Cost(iron, 2, false)),
            List.of(new ItemStack(Items.DIAMOND, 2)), 3);

        assertTrue(result.success());
        assertEquals(4, result.slots().get(0).getCount());
        assertEquals(64, result.slots().get(1).getCount());
        assertEquals(6, result.removed().getFirst().getCount());
        assertEquals(6, result.granted().getFirst().getCount());
        assertEquals(58, diamond.getCount());
    }

    @Test
    void oversizedBatchReturnsFailureBeforeChangingStacks() {
        ItemStack iron = new ItemStack(Items.IRON_INGOT, 64);
        var result = TradeInventory.plan(List.of(iron),
            List.of(new TradeInventory.Cost(iron, Integer.MAX_VALUE, false)),
            List.of(new ItemStack(Items.DIAMOND)), 2);

        assertEquals(TradeInventory.Failure.INVALID_QUANTITY, result.failure());
        assertEquals(64, iron.getCount());
    }

    private static ItemStack namedIron(String name) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }
}
