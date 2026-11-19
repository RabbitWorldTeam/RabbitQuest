package com.tr4nce.rabbitquest.block;

import com.tr4nce.rabbitquest.RabbitQuest;
import com.tr4nce.rabbitquest.event.EventService;
import com.tr4nce.rabbitquest.network.ChestNetwork;
import com.tr4nce.rabbitquest.weekly.TargetKind;
import com.tr4nce.rabbitquest.weekly.WeeklyService;
import com.tr4nce.rabbitquest.weekly.WeeklyTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RabbitChest extends ChestBlock {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RabbitQuest.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RabbitQuest.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RabbitQuest.MOD_ID);
    private static final String ID = "rabbit_chest";

    public static final net.neoforged.neoforge.registries.DeferredBlock<RabbitChest> BLOCK =
            BLOCKS.register(ID, RabbitChest::new);
    public static final net.neoforged.neoforge.registries.DeferredHolder<BlockEntityType<?>, BlockEntityType<ChestEntity>>
            ENTITY = ENTITIES.register(ID, () -> BlockEntityType.Builder.of(ChestEntity::new, BLOCK.get()).build(null));
    public static final net.neoforged.neoforge.registries.DeferredItem<BlockItem> ITEM =
            ITEMS.register(ID, () -> new BlockItem(BLOCK.get(), new Item.Properties()) {
                @Override
                public InteractionResult place(BlockPlaceContext context) {
                    Player player = context.getPlayer();
                    return player != null && player.hasPermissions(2) ? super.place(context) : InteractionResult.FAIL;
                }
            });

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, false,
                BlockEvent.BreakEvent.class, RabbitChest::protectBreak);
        NeoForge.EVENT_BUS.addListener(RabbitChest::protectExplosion);
    }

    private RabbitChest() {
        super(BlockBehaviour.Properties.ofFullCopy(Blocks.CHEST).pushReaction(PushReaction.BLOCK), ENTITY::get);
    }

    @Override
    public ChestBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChestEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(TYPE, ChestType.SINGLE)
                .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).is(Fluids.WATER));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ChestEntity chest && chest.binding != null) {
            chest.binding = null;
            chest.setChanged();
        }
        if (placer instanceof ServerPlayer player && player.hasPermissions(2)) {
            show(player, pos, "");
        }
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                              BlockHitResult hit) {
        if (player.isShiftKeyDown() && player.hasPermissions(2)) {
            if (player instanceof ServerPlayer serverPlayer) {
                show(serverPlayer, pos, "");
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        var previousMenu = player.containerMenu;
        InteractionResult result = super.useWithoutItem(state, level, pos, player, hit);
        if (player instanceof ServerPlayer serverPlayer && player.containerMenu != previousMenu
                && level.getBlockEntity(pos) instanceof ChestEntity chest && chest.binding != null) {
            Vec3 location = Vec3.atCenterOf(pos);
            WeeklyService.recordEvent(serverPlayer, TargetKind.OPEN_RABBIT_CHEST, chest.binding,
                    1, level.dimension(), location);
            EventService.recordEvent(serverPlayer, TargetKind.OPEN_RABBIT_CHEST, chest.binding,
                    1, level.dimension(), location);
        }
        return result;
    }

    @Override
    public boolean canEntityDestroy(BlockState state, net.minecraft.world.level.BlockGetter level,
                                    BlockPos pos, net.minecraft.world.entity.Entity entity) {
        return !(level.getBlockEntity(pos) instanceof ChestEntity chest && chest.binding != null);
    }

    private static void protectBreak(BlockEvent.BreakEvent event) {
        if (!event.getPlayer().hasPermissions(2)
                && event.getLevel().getBlockEntity(event.getPos()) instanceof ChestEntity chest
                && chest.binding != null) {
            event.setCanceled(true);
        }
    }

    private static void protectExplosion(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> event.getLevel().getBlockEntity(pos) instanceof ChestEntity chest
                && chest.binding != null);
    }

    public static void handle(ServerPlayer player, CompoundTag request) {
        if (!player.hasPermissions(2) || !request.contains("pos", net.minecraft.nbt.Tag.TAG_LONG)
                || !request.getString("dimension").equals(player.level().dimension().location().toString())) {
            return;
        }
        BlockPos pos = BlockPos.of(request.getLong("pos"));
        if (!player.level().hasChunkAt(pos) || !player.canInteractWithBlock(pos, 1.0)
                || !(player.level().getBlockEntity(pos) instanceof ChestEntity chest)) {
            return;
        }

        String action = request.getString("action");
        if ("check".equals(action)) {
            show(player, pos, "checked");
        } else if ("bind".equals(action)) {
            ResourceLocation id = ResourceLocation.tryParse(request.getString("binding"));
            ListTag targets = targets(player, pos);
            for (var tag : targets) {
                if (id != null && ((CompoundTag) tag).getString("id").equals(id.toString())) {
                    chest.binding = id;
                    chest.setChanged();
                    show(player, pos, "saved");
                    return;
                }
            }
            show(player, pos, "invalid");
        }
    }

    private static ListTag targets(ServerPlayer player, BlockPos pos) {
        ListTag targets = new ListTag();
        WeeklyService.chestTargets(player, pos, targets);
        EventService.chestTargets(player, pos, targets);
        return targets;
    }

    private static void show(ServerPlayer player, BlockPos pos, String message) {
        if (!(player.level().getBlockEntity(pos) instanceof ChestEntity chest)) {
            return;
        }
        CompoundTag snapshot = new CompoundTag();
        snapshot.putLong("pos", pos.asLong());
        snapshot.putString("dimension", player.level().dimension().location().toString());
        snapshot.putString("binding", chest.binding == null ? "" : chest.binding.toString());
        snapshot.putString("message", message);
        snapshot.put("targets", targets(player, pos));
        ChestNetwork.send(player, snapshot);
    }

    public static Component title(String name, String nameKey) {
        return nameKey.isBlank() ? Component.literal(name) : Component.translatable(nameKey);
    }

    public static void addTarget(ServerPlayer player, BlockPos pos, ListTag rows, WeeklyTarget target,
                                 Component title, String status) {
        if (target.kind() != TargetKind.OPEN_RABBIT_CHEST) {
            return;
        }
        CompoundTag row = new CompoundTag();
        row.putString("id", target.resource().toString());
        row.putString("title", Component.Serializer.toJson(title, player.registryAccess()));
        if ("ready".equals(status) && !target.matches(target.resource(), player.level().dimension().location(),
                Vec3.atCenterOf(pos))) {
            status = "location";
        }
        row.putString("status", status);
        rows.add(row);
    }

    public static final class ChestEntity extends ChestBlockEntity {
        private ResourceLocation binding;

        private ChestEntity(BlockPos pos, BlockState state) {
            super(ENTITY.get(), pos, state);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            binding = tag.contains("Binding") ? ResourceLocation.tryParse(tag.getString("Binding")) : null;
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (binding != null) {
                tag.putString("Binding", binding.toString());
            }
        }
    }
}
