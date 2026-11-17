package com.tr4nce.rabbitquest.weekly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable weekly configuration loaded once per server start. Invalid definitions abort the
 * whole load so a partially enabled rotation never runs.
 */
public final class WeeklyDefinitions {
    private static final String CYCLE_PATH = "weekly/cycle.json";
    private static final String TASK_PREFIX = "weekly/tasks";
    private static final String ORDER_PREFIX = "weekly/orders";

    private final WeeklyCycle cycle;
    private final Map<ResourceLocation, WeeklyTaskDefinition> tasks;
    private final Map<ResourceLocation, EngineeringOrder> orders;
    private final List<EngineeringOrder> enabledOrders;

    public WeeklyDefinitions(WeeklyCycle cycle, Collection<WeeklyTaskDefinition> tasks,
                             Collection<EngineeringOrder> orders) {
        this.cycle = cycle;
        Map<ResourceLocation, WeeklyTaskDefinition> taskMap = new LinkedHashMap<>();
        for (WeeklyTaskDefinition task : tasks) {
            if (taskMap.put(task.id(), task) != null) {
                throw new WeeklyConfigException("Duplicate task id: " + task.id());
            }
        }
        Map<ResourceLocation, EngineeringOrder> orderMap = new LinkedHashMap<>();
        for (EngineeringOrder order : orders) {
            if (orderMap.put(order.id(), order) != null) {
                throw new WeeklyConfigException("Duplicate order id: " + order.id());
            }
            if (!taskMap.containsKey(order.task())) {
                throw new WeeklyConfigException("Order " + order.id() + " references missing task " + order.task());
            }
            if (taskMap.get(order.task()).reward() != null) {
                throw new WeeklyConfigException("Order " + order.id() + " references task " + order.task()
                        + " with a direct reward; ordinary task and engineering order rewards are exclusive");
            }
        }
        this.tasks = Map.copyOf(taskMap);
        this.orders = Map.copyOf(orderMap);
        this.enabledOrders = orderMap.values().stream()
                .filter(order -> order.enabled() && taskMap.get(order.task()).enabled())
                .sorted(Comparator.comparingInt(EngineeringOrder::order).thenComparing(order -> order.id().toString()))
                .toList();
    }

    public WeeklyCycle cycle() {
        return cycle;
    }

    public Map<ResourceLocation, WeeklyTaskDefinition> tasks() {
        return tasks;
    }

    public Map<ResourceLocation, EngineeringOrder> orders() {
        return orders;
    }

    public WeeklyTaskDefinition task(ResourceLocation id) {
        return tasks.get(id);
    }

    public EngineeringOrder order(ResourceLocation id) {
        return orders.get(id);
    }

    public List<EngineeringOrder> enabledOrders() {
        return enabledOrders;
    }

    /** Enabled tasks that carry their own reward and therefore auto-open without an order. */
    public List<WeeklyTaskDefinition> ordinaryTasks() {
        return tasks.values().stream()
                .filter(task -> task.enabled() && task.reward() != null)
                .sorted(Comparator.comparingInt(WeeklyTaskDefinition::order)
                        .thenComparing(task -> task.id().toString()))
                .toList();
    }

    public static WeeklyDefinitions load(ResourceManager manager) {
        WeeklyCycle cycle = readCycle(manager);
        Map<String, String> tasks = readFolder(manager, TASK_PREFIX);
        Map<String, String> orders = readFolder(manager, ORDER_PREFIX);
        WeeklyDefinitions definitions = parse(cycle, tasks, orders);
        definitions.validateRegistry();
        return definitions;
    }

    /** Testable parser; keys are file names used in actionable error messages. */
    public static WeeklyDefinitions parse(WeeklyCycle cycle, Map<String, String> taskFiles, Map<String, String> orderFiles) {
        List<WeeklyTaskDefinition> tasks = new ArrayList<>();
        for (var entry : taskFiles.entrySet()) {
            try {
                tasks.add(parseTask(json(entry.getKey(), entry.getValue())));
            } catch (WeeklyConfigException exception) {
                throw new WeeklyConfigException(entry.getKey() + ": " + exception.getMessage(), exception);
            }
        }
        List<EngineeringOrder> orders = new ArrayList<>();
        for (var entry : orderFiles.entrySet()) {
            try {
                orders.add(parseOrder(json(entry.getKey(), entry.getValue())));
            } catch (WeeklyConfigException exception) {
                throw new WeeklyConfigException(entry.getKey() + ": " + exception.getMessage(), exception);
            }
        }
        return new WeeklyDefinitions(cycle, tasks, orders);
    }

    private static WeeklyCycle readCycle(ResourceManager manager) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("rabbitquest", CYCLE_PATH);
        var resource = manager.getResource(id);
        if (resource.isEmpty()) {
            return WeeklyCycle.DEFAULT;
        }
        try (var reader = resource.get().openAsReader()) {
            JsonObject json = json(CYCLE_PATH, reader);
            String zone = string(json, "zone", WeeklyCycle.DEFAULT.zone().getId(), CYCLE_PATH);
            String dayName = string(json, "day", WeeklyCycle.DEFAULT.day().name(), CYCLE_PATH);
            String timeName = string(json, "time", WeeklyCycle.DEFAULT.time().toString(), CYCLE_PATH);
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(dayName.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new WeeklyConfigException(CYCLE_PATH + ": invalid day " + dayName);
            }
            return new WeeklyCycle(ZoneId.of(zone), day, LocalTime.parse(timeName));
        } catch (WeeklyConfigException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WeeklyConfigException(CYCLE_PATH + ": " + exception.getMessage(), exception);
        }
    }

    private static Map<String, String> readFolder(ResourceManager manager, String prefix) {
        Map<String, String> files = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> resources = manager.listResources(prefix,
                id -> id.getPath().endsWith(".json"));
        for (var entry : resources.entrySet()) {
            try (var reader = entry.getValue().openAsReader()) {
                files.put(entry.getKey().toString(), read(reader));
            } catch (IOException exception) {
                throw new WeeklyConfigException(entry.getKey() + ": unreadable (" + exception.getMessage() + ")",
                        exception);
            }
        }
        return files;
    }

    private static String read(java.io.Reader reader) throws IOException {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            result.append(buffer, 0, read);
        }
        return result.toString();
    }

    private static JsonObject json(String file, String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new WeeklyConfigException(file + ": root must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (WeeklyConfigException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WeeklyConfigException(file + ": invalid JSON (" + exception.getMessage() + ")", exception);
        }
    }

    private static JsonObject json(String file, java.io.Reader reader) throws IOException {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            result.append(buffer, 0, read);
        }
        return json(file, result.toString());
    }

    private static WeeklyTaskDefinition parseTask(JsonObject json) {
        ResourceLocation id = identifier(json, "id", "task");
        String name = string(json, "name", null, id + " name");
        String nameKey = string(json, "nameKey", "", id + " nameKey");
        String description = string(json, "description", "", id + " description");
        String descriptionKey = string(json, "descriptionKey", "", id + " descriptionKey");
        ResourceLocation icon = identifier(json, "icon", "task " + id);
        int order = integer(json, "order", 0);
        boolean enabled = bool(json, "enabled", false);
        JsonArray targets = array(json, "targets", id + " targets");
        List<WeeklyTarget> parsed = new ArrayList<>();
        for (JsonElement element : targets) {
            if (!element.isJsonObject()) {
                throw new WeeklyConfigException("Task " + id + " has a non-object target");
            }
            parsed.add(parseTarget(element.getAsJsonObject(), id));
        }
        WeeklyReward reward = json.has("reward") ? parseReward(object(json, "reward", id + " reward")) : null;
        return new WeeklyTaskDefinition(id, name, nameKey, description, descriptionKey, icon, order, enabled, parsed,
                reward);
    }

    private static WeeklyTarget parseTarget(JsonObject json, ResourceLocation task) {
        ResourceLocation id = identifier(json, "id", "target in " + task);
        TargetKind kind = TargetKind.parse(string(json, "kind", null, id + " kind"));
        ResourceLocation resource = optionalIdentifier(json, "resource", id + " resource");
        int amount = integer(json, "amount", 0);
        if (amount <= 0) {
            throw new WeeklyConfigException("Target " + id + " amount must be positive");
        }
        if (kind == TargetKind.DELIVER && resource == null) {
            throw new WeeklyConfigException("DELIVER target " + id + " requires an item resource");
        }
        ResourceLocation dimension = optionalIdentifier(json, "dimension", id + " dimension");
        WeeklyTarget.Region region = null;
        if (json.has("region")) {
            JsonObject box = object(json, "region", id + " region");
            region = new WeeklyTarget.Region(coordinate(box, "min", 0, id), coordinate(box, "min", 1, id),
                    coordinate(box, "min", 2, id), coordinate(box, "max", 0, id), coordinate(box, "max", 1, id),
                    coordinate(box, "max", 2, id));
        }
        return new WeeklyTarget(id, kind, resource, amount, dimension, region);
    }

    private static EngineeringOrder parseOrder(JsonObject json) {
        ResourceLocation id = identifier(json, "id", "order");
        String name = string(json, "name", null, id + " name");
        String nameKey = string(json, "nameKey", "", id + " nameKey");
        String description = string(json, "description", "", id + " description");
        String descriptionKey = string(json, "descriptionKey", "", id + " descriptionKey");
        ResourceLocation icon = identifier(json, "icon", "order " + id);
        int order = integer(json, "order", 0);
        boolean enabled = bool(json, "enabled", false);
        ResourceLocation task = identifier(json, "task", "order " + id);
        return new EngineeringOrder(id, name, nameKey, description, descriptionKey, icon, order, enabled, task,
                parseReward(object(json, "reward", id + " reward")));
    }

    private static WeeklyReward parseReward(JsonObject json) {
        String modeName = string(json, "mode", null, "reward mode");
        WeeklyReward.Mode mode;
        try {
            mode = WeeklyReward.Mode.valueOf(modeName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new WeeklyConfigException("Unknown reward mode: " + modeName);
        }
        List<WeeklyReward.RewardItem> items = new ArrayList<>();
        if (json.has("items")) {
            for (JsonElement element : array(json, "items", "reward items")) {
                items.add(parseRewardItem(element));
            }
        }
        List<WeeklyReward.Group> groups = new ArrayList<>();
        if (json.has("groups")) {
            for (JsonElement element : array(json, "groups", "reward groups")) {
                if (!element.isJsonObject()) {
                    throw new WeeklyConfigException("Reward group must be an object");
                }
                JsonObject group = element.getAsJsonObject();
                List<WeeklyReward.RewardItem> groupItems = new ArrayList<>();
                for (JsonElement item : array(group, "items", "reward group items")) {
                    groupItems.add(parseRewardItem(item));
                }
                groups.add(new WeeklyReward.Group(integer(group, "weight", 1), groupItems));
            }
        }
        return new WeeklyReward(mode, items, groups);
    }

    private static WeeklyReward.RewardItem parseRewardItem(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new WeeklyConfigException("Reward item must be an object");
        }
        JsonObject json = element.getAsJsonObject();
        return new WeeklyReward.RewardItem(identifier(json, "item", "reward item"),
                integer(json, "count", 0));
    }

    private void validateRegistry() {
        for (WeeklyTaskDefinition task : tasks.values()) {
            requireItem(task.icon(), "icon of task " + task.id());
            if (task.reward() != null) {
                validateReward(task.reward(), "task " + task.id());
            }
            for (WeeklyTarget target : task.targets()) {
                if (target.resource() == null) {
                    continue;
                }
                if (target.kind() == TargetKind.DELIVER || target.kind() == TargetKind.HARVEST) {
                    requireItem(target.resource(), target.kind() + " resource of target " + target.id());
                } else if (target.kind() == TargetKind.KILL) {
                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(target.resource())) {
                        throw new WeeklyConfigException("Unknown entity type " + target.resource()
                                + " in target " + target.id());
                    }
                }
            }
        }
        for (EngineeringOrder order : orders.values()) {
            requireItem(order.icon(), "icon of order " + order.id());
            validateReward(order.reward(), "order " + order.id());
        }
    }

    private static void validateReward(WeeklyReward reward, String context) {
        for (WeeklyReward.RewardItem item : reward.items()) {
            requireItem(item.item(), "reward of " + context);
        }
        for (WeeklyReward.Group group : reward.groups()) {
            for (WeeklyReward.RewardItem item : group.items()) {
                requireItem(item.item(), "reward of " + context);
            }
        }
    }

    private static void requireItem(ResourceLocation id, String context) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new WeeklyConfigException("Unknown item " + id + " in " + context);
        }
    }

    private static ResourceLocation identifier(JsonObject json, String key, String context) {
        String value = string(json, key, null, context + " " + key);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) {
            throw new WeeklyConfigException("Invalid resource id in " + context + " " + key + ": " + value);
        }
        return id;
    }

    private static ResourceLocation optionalIdentifier(JsonObject json, String key, String context) {
        if (!json.has(key)) {
            return null;
        }
        return identifier(json, key, context);
    }

    private static String string(JsonObject json, String key, String fallback, String context) {
        if (!json.has(key)) {
            if (fallback == null) {
                throw new WeeklyConfigException(context + " is required");
            }
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new WeeklyConfigException(context + " must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new WeeklyConfigException(context + " must not be blank");
        }
        return value;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new WeeklyConfigException(key + " must be an integer");
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException exception) {
            throw new WeeklyConfigException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new WeeklyConfigException(key + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static JsonArray array(JsonObject json, String key, String context) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new WeeklyConfigException(context + " must be an array");
        }
        return json.getAsJsonArray(key);
    }

    private static JsonObject object(JsonObject json, String key, String context) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            throw new WeeklyConfigException(context + " must be an object");
        }
        return json.getAsJsonObject(key);
    }

    private static double coordinate(JsonObject json, String key, int index, ResourceLocation target) {
        JsonArray array = array(json, key, "region " + key + " of target " + target);
        if (array.size() != 3 || !array.get(index).isJsonPrimitive()
                || !array.get(index).getAsJsonPrimitive().isNumber()) {
            throw new WeeklyConfigException("region " + key + " of target " + target + " must have 3 numbers");
        }
        return array.get(index).getAsDouble();
    }
}
