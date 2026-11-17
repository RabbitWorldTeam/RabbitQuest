package com.tr4nce.rabbitquest.weekly;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyDefinitionsTest {
    @Test
    void shippedSamplesParseAndStayDisabled() throws IOException {
        Map<String, String> tasks = new LinkedHashMap<>();
        tasks.put("gather_logs.json", resource("/data/rabbitquest/weekly/tasks/gather_logs.json"));
        tasks.put("frontier_patrol.json", resource("/data/rabbitquest/weekly/tasks/frontier_patrol.json"));
        tasks.put("field_survey.json", resource("/data/rabbitquest/weekly/tasks/field_survey.json"));
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("supply_convoy.json", resource("/data/rabbitquest/weekly/orders/supply_convoy.json"));
        orders.put("frontier_guard.json", resource("/data/rabbitquest/weekly/orders/frontier_guard.json"));
        orders.put("quartermaster.json", resource("/data/rabbitquest/weekly/orders/quartermaster.json"));

        WeeklyDefinitions definitions = WeeklyDefinitions.parse(WeeklyCycle.DEFAULT, tasks, orders);
        assertEquals(3, definitions.tasks().size());
        assertEquals(3, definitions.orders().size());
        assertTrue(definitions.enabledOrders().isEmpty());
        definitions.tasks().values().forEach(task -> assertFalse(task.enabled()));
        definitions.orders().values().forEach(order -> assertFalse(order.enabled()));
        assertTrue(definitions.ordinaryTasks().isEmpty());

        WeeklyTaskDefinition survey = definitions.task(ResourceLocation.parse("rabbitquest:field_survey"));
        assertNotNull(survey);
        assertNotNull(survey.reward());
        assertEquals(WeeklyReward.Mode.FIXED, survey.reward().mode());

        WeeklyTaskDefinition gather = definitions.task(ResourceLocation.parse("rabbitquest:gather_logs"));
        assertNotNull(gather);
        assertEquals(2, gather.targets().size());
        EngineeringOrder supply = definitions.order(ResourceLocation.parse("rabbitquest:supply_convoy"));
        assertNotNull(supply);
        assertEquals(WeeklyReward.Mode.FIXED, supply.reward().mode());
        assertEquals(List.of("rabbitquest:building_material_voucher", "rabbitquest:weapon_tool_voucher"),
                supply.reward().items().stream().limit(2).map(item -> item.item().toString()).toList());
    }

    @Test
    void invalidFileNamesTheSourceAndAbortsTheWholeLoad() {
        Map<String, String> tasks = Map.of("broken.json", "{\"id\":\"rabbitquest:broken\"}");
        WeeklyConfigException error = assertThrows(WeeklyConfigException.class,
                () -> WeeklyDefinitions.parse(WeeklyCycle.DEFAULT, tasks, Map.of()));
        assertTrue(error.getMessage().startsWith("broken.json:"));
    }

    @Test
    void orderReferencingMissingTaskIsRejected() {
        WeeklyReward reward = new WeeklyReward(WeeklyReward.Mode.FIXED,
                List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:diamond"), 1)), List.of());
        EngineeringOrder order = new EngineeringOrder(ResourceLocation.parse("rabbitquest:order"), "Order", "", "", "",
                ResourceLocation.parse("minecraft:chest"), 1, true,
                ResourceLocation.parse("rabbitquest:missing"), reward);
        WeeklyConfigException error = assertThrows(WeeklyConfigException.class,
                () -> new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(), List.of(order)));
        assertTrue(error.getMessage().contains("missing task"));
    }

    @Test
    void taskWithDirectRewardParsesAsOrdinary() {
        String json = """
                {
                  "id": "rabbitquest:ordinary",
                  "name": "Ordinary",
                  "icon": "minecraft:stone",
                  "enabled": true,
                  "targets": [
                    { "id": "rabbitquest:mine", "kind": "HARVEST", "resource": "minecraft:stone", "amount": 1 }
                  ],
                  "reward": {
                    "mode": "FIXED",
                    "items": [ { "item": "minecraft:diamond", "count": 1 } ]
                  }
                }
                """;
        WeeklyDefinitions definitions = WeeklyDefinitions.parse(WeeklyCycle.DEFAULT,
                Map.of("ordinary.json", json), Map.of());
        WeeklyTaskDefinition task = definitions.task(ResourceLocation.parse("rabbitquest:ordinary"));
        assertNotNull(task);
        assertNotNull(task.reward());
        assertEquals(1, definitions.ordinaryTasks().size());
    }

    @Test
    void orderCannotReferenceAnOrdinaryTaskWithItsOwnReward() {
        WeeklyTaskDefinition task = new WeeklyTaskDefinition(ResourceLocation.parse("rabbitquest:ordinary"),
                "Ordinary", "", "", "", ResourceLocation.parse("minecraft:stone"), 1, true,
                List.of(new WeeklyTarget(ResourceLocation.parse("rabbitquest:mine"), TargetKind.HARVEST,
                        ResourceLocation.parse("minecraft:stone"), 1, null, null)),
                new WeeklyReward(WeeklyReward.Mode.FIXED,
                        List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:emerald"), 1)),
                        List.of()));
        EngineeringOrder order = new EngineeringOrder(ResourceLocation.parse("rabbitquest:order"), "Order", "",
                "", "", ResourceLocation.parse("minecraft:chest"), 1, true, task.id(),
                new WeeklyReward(WeeklyReward.Mode.FIXED,
                        List.of(new WeeklyReward.RewardItem(ResourceLocation.parse("minecraft:diamond"), 1)),
                        List.of()));
        WeeklyConfigException error = assertThrows(WeeklyConfigException.class,
                () -> new WeeklyDefinitions(WeeklyCycle.DEFAULT, List.of(task), List.of(order)));
        assertTrue(error.getMessage().contains("exclusive"));
    }

    @Test
    void arrivalAmountMustBeOne() {
        assertThrows(WeeklyConfigException.class, () -> new WeeklyTarget(
                ResourceLocation.parse("rabbitquest:arrive"), TargetKind.ARRIVAL, null, 2, null, null));
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = WeeklyDefinitionsTest.class.getResourceAsStream(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
