package com.tr4nce.rabbitquest.network;

import com.tr4nce.rabbitquest.client.QuestBookClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server-to-client signals for opening the Quest Book and displaying completion toasts. */
public final class QuestBookNetwork {
    private QuestBookNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(OpenBook.TYPE, OpenBook.CODEC,
                (packet, context) -> QuestBookClient.openFromBook());
        registrar.playToClient(CompletionToast.TYPE, CompletionToast.CODEC,
                (packet, context) -> QuestBookClient.showToast(
                        packet.titleKey(), packet.subtitle(), packet.subtitleKey(), packet.icon()));
    }

    public static void sendOpen(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, OpenBook.INSTANCE);
    }

    public static void sendToast(ServerPlayer player, String titleKey, String subtitle,
                                 String subtitleKey, String icon) {
        PacketDistributor.sendToPlayer(player, new CompletionToast(
                titleKey == null ? "" : titleKey,
                subtitle == null ? "" : subtitle,
                subtitleKey == null ? "" : subtitleKey,
                icon == null ? "" : icon));
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

    private record CompletionToast(String titleKey, String subtitle, String subtitleKey, String icon)
            implements CustomPacketPayload {
        private static final Type<CompletionToast> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("rabbitquest", "completion_toast"));
        private static final StreamCodec<RegistryFriendlyByteBuf, CompletionToast> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, CompletionToast::titleKey,
                        ByteBufCodecs.STRING_UTF8, CompletionToast::subtitle,
                        ByteBufCodecs.STRING_UTF8, CompletionToast::subtitleKey,
                        ByteBufCodecs.STRING_UTF8, CompletionToast::icon,
                        CompletionToast::new);

        @Override
        public Type<CompletionToast> type() {
            return TYPE;
        }
    }
}
