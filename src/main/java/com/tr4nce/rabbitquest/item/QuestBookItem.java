package com.tr4nce.rabbitquest.item;

import com.tr4nce.rabbitquest.network.QuestBookNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

public final class QuestBookItem extends Item {
    private static volatile Consumer<List<Component>> clientTooltipHook;

    public QuestBookItem(Properties properties) {
        super(properties);
    }

    public static void setClientTooltipHook(Consumer<List<Component>> hook) {
        clientTooltipHook = hook;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            QuestBookNetwork.sendOpen(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.rabbitquest.quest_book.tooltip"));
        Consumer<List<Component>> hook = clientTooltipHook;
        if (hook != null && context.level() != null && context.level().isClientSide()) {
            hook.accept(tooltip);
        }
    }
}
