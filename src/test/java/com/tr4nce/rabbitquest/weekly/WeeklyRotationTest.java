package com.tr4nce.rabbitquest.weekly;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeeklyRotationTest {
    private static final LocalDate FIRST = LocalDate.parse("2026-11-16");

    @Test
    void firstWeekAnchorsAndRotationIsOrdered() {
        WeeklyDefinitions definitions = definitions(order("rabbitquest:a", 1, true),
                order("rabbitquest:b", 2, true), order("rabbitquest:c", 3, true));
        WeeklySavedData data = new WeeklySavedData();
        assertNull(data.anchor());

        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(definitions, data, FIRST));
        assertEquals(FIRST.toString(), data.anchor());
        assertEquals("rabbitquest:b", WeeklyEngine.materializeOrder(definitions, data, FIRST.plusWeeks(1)));
        assertEquals("rabbitquest:c", WeeklyEngine.materializeOrder(definitions, data, FIRST.plusWeeks(2)));
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(definitions, data, FIRST.plusWeeks(3)));
    }

    @Test
    void anchoredOrderSurvivesRestartAndListEditsWithinTheWeek() {
        WeeklyDefinitions definitions = definitions(order("rabbitquest:a", 1, true),
                order("rabbitquest:b", 2, true));
        WeeklySavedData data = new WeeklySavedData();
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(definitions, data, FIRST));

        WeeklyDefinitions edited = definitions(order("rabbitquest:b", 1, true),
                order("rabbitquest:c", 2, true));
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(edited, data, FIRST));

        WeeklySavedData reloaded = WeeklySavedData.load(data.save(new CompoundTag(), registries()), registries());
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(edited, reloaded, FIRST));
        assertEquals(FIRST.toString(), reloaded.anchor());
        assertEquals("rabbitquest:a", reloaded.selectedOrder(FIRST.toString()));
    }

    @Test
    void disabledOrdersDoNotRotate() {
        WeeklyDefinitions definitions = definitions(order("rabbitquest:a", 1, true),
                order("rabbitquest:b", 2, false));
        WeeklySavedData data = new WeeklySavedData();
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(definitions, data, FIRST));
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(definitions, data, FIRST.plusWeeks(1)));
    }

    @Test
    void skippedWeeksStillAdvanceRotationFromTheAnchor() {
        WeeklyDefinitions definitions = definitions(order("rabbitquest:a", 1, true),
                order("rabbitquest:b", 2, true), order("rabbitquest:c", 3, true));
        WeeklySavedData data = new WeeklySavedData();
        assertEquals("rabbitquest:a", WeeklyEngine.materializeOrder(definitions, data, FIRST));
        assertEquals("rabbitquest:c", WeeklyEngine.materializeOrder(definitions, data, FIRST.plusWeeks(5)));
    }

    private static WeeklyDefinitions definitions(EngineeringOrder... orders) {
        WeeklyTaskDefinition task = new WeeklyTaskDefinition(
                ResourceLocation.parse("rabbitquest:task"), "Task", "", "Description", "",
                ResourceLocation.parse("minecraft:stone"), 1, true,
                List.of(new WeeklyTarget(ResourceLocation.parse("rabbitquest:target"), TargetKind.HARVEST,
                        ResourceLocation.parse("minecraft:stone"), 1, null, null)));
        EngineeringOrder[] withTask = new EngineeringOrder[orders.length];
        for (int index = 0; index < orders.length; index++) {
            EngineeringOrder order = orders[index];
            withTask[index] = new EngineeringOrder(order.id(), order.name(), order.nameKey(), order.description(),
                    order.descriptionKey(), order.icon(), order.order(), order.enabled(), task.id(), order.reward());
        }
        return new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task), List.of(withTask));
    }

    private static EngineeringOrder order(String id, int order, boolean enabled) {
        return new EngineeringOrder(ResourceLocation.parse(id), id, "", "", "", ResourceLocation.parse("minecraft:chest"),
                order, enabled, ResourceLocation.parse("rabbitquest:task"),
                new WeeklyReward(WeeklyReward.Mode.FIXED,
                        List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:diamond"), 1)),
                        List.of()));
    }

    private static HolderLookup.Provider registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
