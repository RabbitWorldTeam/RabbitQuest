package com.tr4nce.rabbitquest.stats;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsSavedDataTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    private static final ResourceLocation ZOMBIE = ResourceLocation.parse("minecraft:zombie");
    private static final ResourceLocation SKELETON = ResourceLocation.parse("minecraft:skeleton");

    @Test
    void newDataStartsAtZero() {
        var data = new StatsSavedData();

        assertEquals(new StatsSavedData.Totals(0, 0, 0.0), data.totals(PLAYER));
        assertEquals(new StatsSavedData.EntityTotals(0, 0.0), data.entity(PLAYER, ZOMBIE));
        assertEquals(0, data.harvested(PLAYER, STONE));
    }

    @Test
    void countsHarvestsAndKillsPerTargetAcrossSerialization() {
        var data = new StatsSavedData();
        data.record(PLAYER, ActionType.HARVEST, STONE, 1.0);
        data.record(PLAYER, ActionType.HARVEST, STONE, 1.0);
        data.record(PLAYER, ActionType.HARVEST, DIRT, 1.0);
        data.record(PLAYER, ActionType.KILL, ZOMBIE, 1.0);
        data.record(PLAYER, ActionType.KILL, ZOMBIE, 1.0);
        data.record(PLAYER, ActionType.KILL, SKELETON, 1.0);

        var loaded = reload(data);

        assertEquals(new StatsSavedData.Totals(3, 3, 0.0), loaded.totals(PLAYER));
        assertEquals(2, loaded.harvested(PLAYER, STONE));
        assertEquals(1, loaded.harvested(PLAYER, DIRT));
        assertEquals(2, loaded.entity(PLAYER, ZOMBIE).kills());
        assertEquals(1, loaded.entity(PLAYER, SKELETON).kills());
        assertEquals(new StatsSavedData.Totals(0, 0, 0.0), loaded.totals(OTHER));
    }

    @Test
    void keepsDamageAsUnroundedDoubleAcrossSerialization() {
        var data = new StatsSavedData();
        double first = 1.0 / 3.0;
        double second = 0.1;
        data.record(PLAYER, ActionType.DAMAGE, ZOMBIE, first);
        data.record(PLAYER, ActionType.DAMAGE, ZOMBIE, second);
        data.record(PLAYER, ActionType.DAMAGE, SKELETON, 2.5);
        double expected = first + second;

        var loaded = reload(data);

        assertEquals(expected + 2.5, loaded.totals(PLAYER).damage());
        assertEquals(expected, loaded.entity(PLAYER, ZOMBIE).damage());
        assertEquals(2.5, loaded.entity(PLAYER, SKELETON).damage());
        assertEquals(expected + 2.5, data.totals(PLAYER).damage());
    }

    @Test
    void rejectsNonFiniteDamage() {
        var data = new StatsSavedData();

        assertThrows(IllegalArgumentException.class,
                () -> data.record(PLAYER, ActionType.DAMAGE, ZOMBIE, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> data.record(PLAYER, ActionType.DAMAGE, ZOMBIE, Double.POSITIVE_INFINITY));
        assertEquals(new StatsSavedData.Totals(0, 0, 0.0), data.totals(PLAYER));
    }

    @Test
    void rejectsUnsupportedFormat() {
        var tag = new CompoundTag();
        tag.putInt("format", 2);

        var exception = assertThrows(IllegalArgumentException.class,
                () -> StatsSavedData.load(tag, registries()));
        assertEquals("Unsupported RabbitQuest stats format: 2", exception.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StatsSavedData.load(new CompoundTag(), registries()));
    }

    @Test
    void factoryLoadsSerializedWorldData(@TempDir Path folder) throws IOException {
        var data = new StatsSavedData();
        data.record(PLAYER, ActionType.HARVEST, STONE, 1.0);
        data.record(PLAYER, ActionType.DAMAGE, ZOMBIE, 4.25);

        Path saveFile = folder.resolve("rabbitquest_stats.dat");
        var saved = new CompoundTag();
        saved.put("data", data.save(new CompoundTag(), registries()));
        NbtUtils.addCurrentDataVersion(saved);
        NbtIo.writeCompressed(saved, saveFile);

        var storage = new DimensionDataStorage(folder.toFile(), DataFixers.getDataFixer(), registries());
        var loaded = StatsSavedData.get(storage, folder);

        assertNotNull(loaded);
        assertEquals(new StatsSavedData.Totals(1, 0, 4.25), loaded.totals(PLAYER));
        assertEquals(1, loaded.harvested(PLAYER, STONE));
    }

    private static StatsSavedData reload(StatsSavedData data) {
        return StatsSavedData.load(data.save(new CompoundTag(), registries()), registries());
    }

    private static HolderLookup.Provider registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
