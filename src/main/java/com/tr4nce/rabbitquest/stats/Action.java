package com.tr4nce.rabbitquest.stats;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Objects;

public record Action(ServerPlayer player, ActionType type, ResourceLocation target, double amount,
                     ResourceKey<Level> dimension, BlockPos position, boolean matureCrop) {
    public Action(ServerPlayer player, ActionType type, ResourceLocation target, double amount,
                  ResourceKey<Level> dimension, BlockPos position) {
        this(player, type, target, amount, dimension, position, false);
    }

    public Action {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be positive and finite");
        }
    }
}
