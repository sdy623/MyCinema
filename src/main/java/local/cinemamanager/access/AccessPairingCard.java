package local.cinemamanager.access;

import local.cinemamanager.ManagerTile;
import local.cinemamanager.doors.CinemaDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/** Explicit in-game pairing: own controller, own reader, then door from the restricted entry side. */
public final class AccessPairingCard extends Item {
    public AccessPairingCard(Properties properties){super(properties);}
    @Override public InteractionResult onItemUseFirst(net.minecraft.world.item.ItemStack stack,UseOnContext context){
        if(!context.getLevel().hasChunkAt(context.getClickedPos()))return InteractionResult.PASS;
        var block=context.getLevel().getBlockState(context.getClickedPos()).getBlock();
        return block instanceof local.cinemamanager.ManagerBlock||block instanceof StaffReaderBlock||block instanceof CinemaDoorBlock||FixedIronDoorAccess.candidate(context.getLevel(),context.getClickedPos())?useOn(context):InteractionResult.PASS;
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        if(!(context.getPlayer() instanceof ServerPlayer actor)||!StaffReaderTile.real(actor))return InteractionResult.FAIL;
        if(!context.getLevel().hasChunkAt(context.getClickedPos()))return InteractionResult.FAIL;
        var level=actor.serverLevel();var clicked=level.getBlockEntity(context.getClickedPos());
        CompoundTag data=context.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        try {
            if(clicked instanceof ManagerTile manager){
                if(!manager.permits(actor,8)||manager.owner==null)throw new IllegalArgumentException("mycinema.access.permission");
                data=new CompoundTag();data.putString("dimension",level.dimension().location().toString());data.putLong("controllerPos",manager.getBlockPos().asLong());data.putUUID("controller",manager.identity);
                actor.displayClientMessage(Component.translatable("mycinema.access.selected_controller"),false);
            } else {
                if(!data.getString("dimension").equals(level.dimension().location().toString())||!data.hasUUID("controller"))throw new IllegalArgumentException("mycinema.access.pair_first");
                BlockPos managerPos=BlockPos.of(data.getLong("controllerPos"));
                if(!level.hasChunkAt(managerPos)||!(level.getBlockEntity(managerPos) instanceof ManagerTile manager)||!manager.identity.equals(data.getUUID("controller"))||!manager.permits(actor,Double.POSITIVE_INFINITY))throw new IllegalArgumentException("mycinema.access.stale");
                if(clicked instanceof StaffReaderTile reader){reader.bindController(actor,manager);data.putLong("readerPos",reader.getBlockPos().asLong());data.putUUID("reader",reader.identity);actor.displayClientMessage(Component.translatable("mycinema.access.selected_reader"),false);}
                else if(level.getBlockState(context.getClickedPos()).getBlock() instanceof CinemaDoorBlock||FixedIronDoorAccess.candidate(level,context.getClickedPos())){
                    if(!data.hasUUID("reader"))throw new IllegalArgumentException("mycinema.access.pair_first");
                    BlockPos pos=BlockPos.of(data.getLong("readerPos"));
                    if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof StaffReaderTile reader)||!reader.identity.equals(data.getUUID("reader"))||!manager.identity.equals(reader.controllerId))throw new IllegalArgumentException("mycinema.access.stale");
                    AccessDoors.bind(actor,reader,context.getClickedPos());actor.displayClientMessage(Component.translatable("mycinema.access.paired"),false);
                }else throw new IllegalArgumentException("mycinema.access.pair_first");
            }
            context.getItemInHand().set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        }catch(IllegalArgumentException denied){actor.displayClientMessage(Component.translatable(denied.getMessage()),false);}
        return InteractionResult.SUCCESS;
    }
}
