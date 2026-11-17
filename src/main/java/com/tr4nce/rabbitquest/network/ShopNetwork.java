package com.tr4nce.rabbitquest.network;

import com.tr4nce.rabbitquest.client.ShopClient;
import com.tr4nce.rabbitquest.shop.ShopService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ShopNetwork {
    private ShopNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ShopService.handle(player, packet.data());
            }
        });
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> ShopClient.receive(packet.data()));
    }

    public static void send(CompoundTag request) {
        PacketDistributor.sendToServer(new Request(request));
    }

    public static void send(ServerPlayer player, CompoundTag snapshot) {
        PacketDistributor.sendToPlayer(player, new Snapshot(snapshot));
    }

    private record Request(CompoundTag data) implements CustomPacketPayload {
        private static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("rabbitquest", "shop_request"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, Request::data, Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    private record Snapshot(CompoundTag data) implements CustomPacketPayload {
        private static final Type<Snapshot> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("rabbitquest", "shop_snapshot"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
                StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, Snapshot::data, Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }
}
