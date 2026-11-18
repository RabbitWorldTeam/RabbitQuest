package com.tr4nce.rabbitquest.network;

import com.tr4nce.rabbitquest.client.QuestBookClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Lightweight server-to-client signal to open the player's remembered Quest Book tab. */
public final class QuestBookNetwork {
    private QuestBookNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(OpenBook.TYPE, OpenBook.CODEC,
                (packet, context) -> QuestBookClient.openFromBook());
    }

    public static void sendOpen(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, OpenBook.INSTANCE);
    }

    private record OpenBook() implements CustomPacketPayload {
        private static final OpenBook INSTANCE = new OpenBook();
        private static final Type<OpenBook> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("rabbitquest", "open_quest_book"));
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenBook> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public Type<OpenBook> type() {
            return TYPE;
        }
    }
}
