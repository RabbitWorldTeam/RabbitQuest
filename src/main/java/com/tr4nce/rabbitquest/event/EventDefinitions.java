package com.tr4nce.rabbitquest.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyConfigException;
import com.tr4nce.rabbitquest.weekly.WeeklyReward;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable timed event configuration loaded once per server start. Invalid definitions abort the
 * whole load so a partially enabled event set never runs.
 */
public final class EventDefinitions {
    private static final String EVENT_PREFIX = "event/events";

    private final Map<ResourceLocation, TimedEventDefinition> events;
    private final List<TimedEventDefinition> enabledEvents;

    public EventDefinitions(Collection<TimedEventDefinition> events) {
        Map<ResourceLocation, TimedEventDefinition> map = new LinkedHashMap<>();
        for (TimedEventDefinition event : events) {
            if (map.put(event.id(), event) != null) {
                throw new EventConfigException("Duplicate event id: " + event.id());
            }
        }
        this.events = Map.copyOf(map);
        this.enabledEvents = map.values().stream()
                .filter(TimedEventDefinition::enabled)
                .sorted(Comparator.comparingInt(TimedEventDefinition::order)
                        .thenComparing(event -> event.id().toString()))
                .toList();
    }

    public Map<ResourceLocation, TimedEventDefinition> events() {
        return events;
    }

    public TimedEventDefinition event(ResourceLocation id) {
        return events.get(id);
    }

    public List<TimedEventDefinition> enabledEvents() {
        return enabledEvents;
    }

    public List<TimedEventDefinition> visibleEvents(Instant now) {
        return enabledEvents.stream().filter(event -> event.visibleAt(now)).toList();
    }

    public static EventDefinitions load(ResourceManager manager) {
        Map<String, String> files = readFolder(manager, EVENT_PREFIX);
        EventDefinitions definitions = parse(files);
        definitions.validateRegistry();
        return definitions;
    }

    public static EventDefinitions parse(Map<String, String> eventFiles) {
        List<TimedEventDefinition> parsed = new ArrayList<>();
        for (var entry : eventFiles.entrySet()) {
            try {
                parsed.add(parseEvent(json(entry.getKey(), entry.getValue())));
            } catch (EventConfigException | WeeklyConfigException exception) {
                throw new EventConfigException(entry.getKey() + ": " + exception.getMessage(), exception);
            }
        }
        return new EventDefinitions(parsed);
    }

    private static Map<String, String> readFolder(ResourceManager manager, String prefix) {
        Map<String, String> files = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> resources = manager.listResources(prefix,
                id -> id.getPath().endsWith(".json"));
        for (var entry : resources.entrySet()) {
            try (var reader = entry.getValue().openAsReader()) {
                files.put(entry.getKey().toString(), read(reader));
            } catch (IOException exception) {
                throw new EventConfigException(entry.getKey() + ": unreadable (" + exception.getMessage() + ")",
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
                throw new EventConfigException(file + ": root must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (EventConfigException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new EventConfigException(file + ": invalid JSON (" + exception.getMessage() + ")", exception);
        }
    }

    private static TimedEventDefinition parseEvent(JsonObject json) {
        ResourceLocation id = identifier(json, "id", "event");
        String name = string(json, "name", null, id + " name");
        String nameKey = string(json, "nameKey", "", id + " nameKey");
        String description = string(json, "description", "", id + " description");
        String descriptionKey = string(json, "descriptionKey", "", id + " descriptionKey");
        ResourceLocation icon = identifier(json, "icon", "event " + id);
        int order = integer(json, "order", 0);
        boolean enabled = bool(json, "enabled", false);
        boolean enrollmentRequired = bool(json, "enrollmentRequired", false);
        Instant previewAt = optionalInstant(json, "previewAt", id + " previewAt");
        Instant startAt = instant(json, "startAt", id + " startAt");
        Instant endAt = instant(json, "endAt", id + " endAt");
        Instant claimEndAt = instant(json, "claimEndAt", id + " claimEndAt");
        JsonArray stepArray = array(json, "steps", id + " steps");
        List<EventStep> steps = new ArrayList<>();
        for (JsonElement element : stepArray) {
            if (!element.isJsonObject()) {
                throw new EventConfigException("Event " + id + " has a non-object step");
            }
            steps.add(parseStep(element.getAsJsonObject(), id));
        }
        WeeklyReward completionReward = json.has("completionReward")
                ? parseReward(object(json, "completionReward", id + " completionReward")) : null;
        return new TimedEventDefinition(id, name, nameKey, description, descriptionKey, icon, order, enabled,
                enrollmentRequired, previewAt, startAt, endAt, claimEndAt, steps, completionReward);
    }

    private static EventStep parseStep(JsonObject json, ResourceLocation eventId) {
        ResourceLocation id = identifier(json, "id", "step in " + eventId);
        String name = string(json, "name", null, id + " name");
        String nameKey = string(json, "nameKey", "", id + " nameKey");
        String description = string(json, "description", "", id + " description");
        String descriptionKey = string(json, "descriptionKey", "", id + " descriptionKey");
        String unlockCondition = string(json, "unlockCondition", "", id + " unlockCondition");
        String unlockConditionKey = string(json, "unlockConditionKey", "", id + " unlockConditionKey");
        EventStep.LockedVisibility visibility = EventStep.LockedVisibility.parse(
                string(json, "lockedVisibility", EventStep.LockedVisibility.TITLE_AND_CONDITION.name(),
                        id + " lockedVisibility"));
        String completion = string(json, "completion", "TARGETS", id + " completion");
        boolean click = "CLICK".equals(completion);
        if (!click && !"TARGETS".equals(completion)) {
            throw new EventConfigException("Step " + id + " has invalid completion " + completion);
        }
        List<ResourceLocation> requires = null;
        if (json.has("requires")) {
            requires = new ArrayList<>();
            for (JsonElement element : array(json, "requires", id + " requires")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new EventConfigException("Step " + id + " requires entry must be a step ID");
                }
                ResourceLocation required = ResourceLocation.tryParse(element.getAsString());
                if (required == null) {
                    throw new EventConfigException("Step " + id + " has invalid requires entry " + element);
                }
                requires.add(required);
            }
        }
        String stage = string(json, "stage", "", id + " stage");
        JsonArray targetArray = click && !json.has("targets") ? new JsonArray() : array(json, "targets", id + " targets");
        List<WeeklyTarget> targets = new ArrayList<>();
        for (JsonElement element : targetArray) {
            if (!element.isJsonObject()) {
                throw new EventConfigException("Step " + id + " has a non-object target");
            }
            targets.add(parseTarget(element.getAsJsonObject(), id));
        }
        WeeklyReward reward = json.has("reward") ? parseReward(object(json, "reward", id + " reward")) : null;
        return new EventStep(id, name, nameKey, description, descriptionKey, unlockCondition, unlockConditionKey,
                visibility, targets, reward, requires, click, stage);
    }

    private static WeeklyTarget parseTarget(JsonObject json, ResourceLocation stepId) {
        ResourceLocation id = identifier(json, "id", "target in " + stepId);
        TargetKind kind = TargetKind.parse(string(json, "kind", null, id + " kind"));
        if (kind == TargetKind.MATURE_HARVEST || json.has("matureOnly") && kind != TargetKind.HARVEST) {
            throw new WeeklyConfigException("Target " + id + " must use matureOnly with HARVEST");
        }
        if (kind == TargetKind.HARVEST && bool(json, "matureOnly", false)) {
            kind = TargetKind.MATURE_HARVEST;
        }
        ResourceLocation resource = optionalIdentifier(json, "resource", id + " resource");
        int amount = integer(json, "amount", 0);
        if (amount <= 0) {
            throw new EventConfigException("Target " + id + " amount must be positive");
        }
        if (kind == TargetKind.DELIVER && resource == null) {
            throw new EventConfigException("DELIVER target " + id + " requires an item resource");
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

    private static WeeklyReward parseReward(JsonObject json) {
        String modeName = string(json, "mode", null, "reward mode");
        WeeklyReward.Mode mode;
        try {
            mode = WeeklyReward.Mode.valueOf(modeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new EventConfigException("Unknown reward mode: " + modeName);
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
                    throw new EventConfigException("Reward group must be an object");
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
            throw new EventConfigException("Reward item must be an object");
        }
        JsonObject json = element.getAsJsonObject();
        return new WeeklyReward.RewardItem(identifier(json, "item", "reward item"),
                integer(json, "count", 0));
    }

    private void validateRegistry() {
        for (TimedEventDefinition event : events.values()) {
            requireItem(event.icon(), "icon of event " + event.id());
            if (event.completionReward() != null) {
                validateReward(event.completionReward(), "completionReward of event " + event.id());
            }
            for (EventStep step : event.steps()) {
                if (step.reward() != null) {
                    validateReward(step.reward(), "step " + step.id());
                }
                for (WeeklyTarget target : step.targets()) {
                    if (target.resource() == null) {
                        continue;
                    }
                    if (target.kind() == TargetKind.DELIVER || target.kind() == TargetKind.HARVEST
                            || target.kind() == TargetKind.CRAFT || target.kind() == TargetKind.FISH) {
                        requireItem(target.resource(), target.kind() + " resource of target " + target.id());
                    } else if (target.kind() == TargetKind.MATURE_HARVEST) {
                        if (!BuiltInRegistries.BLOCK.containsKey(target.resource())) {
                            throw new EventConfigException("Unknown block " + target.resource()
                                    + " in target " + target.id());
                        }
                    } else if (target.kind() == TargetKind.KILL) {
                        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(target.resource())) {
                            throw new EventConfigException("Unknown entity type " + target.resource()
                                    + " in target " + target.id());
                        }
                    }
                }
            }
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
            throw new EventConfigException("Unknown item " + id + " in " + context);
        }
    }

    private static Instant instant(JsonObject json, String key, String context) {
        String raw = string(json, key, null, context);
        try {
            return java.time.OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException exception) {
            throw new EventConfigException(context + " must be an ISO-8601 offset timestamp: " + raw,
                    exception);
        }
    }

    private static Instant optionalInstant(JsonObject json, String key, String context) {
        if (!json.has(key)) {
            return null;
        }
        return instant(json, key, context);
    }

    private static ResourceLocation identifier(JsonObject json, String key, String context) {
        String value = string(json, key, null, context + " " + key);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) {
            throw new EventConfigException("Invalid resource id in " + context + " " + key + ": " + value);
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
                throw new EventConfigException(context + " is required");
            }
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new EventConfigException(context + " must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new EventConfigException(context + " must not be blank");
        }
        return value;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new EventConfigException(key + " must be an integer");
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException exception) {
            throw new EventConfigException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new EventConfigException(key + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static JsonArray array(JsonObject json, String key, String context) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new EventConfigException(context + " must be an array");
        }
        return json.getAsJsonArray(key);
    }

    private static JsonObject object(JsonObject json, String key, String context) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            throw new EventConfigException(context + " must be an object");
        }
        return json.getAsJsonObject(key);
    }

    private static double coordinate(JsonObject json, String key, int index, ResourceLocation target) {
        JsonArray array = array(json, key, "region " + key + " of target " + target);
        if (array.size() != 3 || !array.get(index).isJsonPrimitive()
                || !array.get(index).getAsJsonPrimitive().isNumber()) {
            throw new EventConfigException("region " + key + " of target " + target + " must have 3 numbers");
        }
        return array.get(index).getAsDouble();
    }
}
