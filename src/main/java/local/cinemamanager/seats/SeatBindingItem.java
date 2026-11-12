package local.cinemamanager.seats;

import local.cinemamanager.ManagerTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Bind at the manager, then click only builder-selected existing seat blocks. */
public final class SeatBindingItem extends Item {
    static final String CONTINUOUS="mycinema_continuous_seats",CARD="mycinema_seat_card",ROW_SELECTION="mycinema_row_selection";
    public SeatBindingItem(Properties p){super(p);}
    private static CompoundTag data(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
    private static void write(ItemStack stack,CompoundTag data){stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));}
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){
        if(!context.getLevel().hasChunkAt(context.getClickedPos()))return InteractionResult.PASS;
        var target=context.getLevel().getBlockEntity(context.getClickedPos());
        return target instanceof ManagerTile||target instanceof SeatGuideTile||data(stack).hasUUID("identity")?useOn(context):InteractionResult.PASS;
    }
    public static boolean boundTo(ServerPlayer actor,ManagerTile manager){return matches(actor.getMainHandItem(),actor,manager)||matches(actor.getOffhandItem(),actor,manager);}
    static boolean matches(ItemStack stack,ServerPlayer actor,ManagerTile manager){
        if(!(stack.getItem() instanceof SeatBindingItem))return false;CompoundTag data=data(stack);
        return data.hasUUID("identity")&&data.getUUID("identity").equals(manager.identity)&&data.getLong("manager")==manager.getBlockPos().asLong()
                &&data.getString("dimension").equals(actor.level().dimension().location().toString());
    }
    static String card(ItemStack stack){CompoundTag data=data(stack);return data.hasUUID(CARD)?data.getUUID(CARD).toString():"";}
    static String ensureCard(ItemStack stack){CompoundTag data=data(stack);if(!data.hasUUID(CARD)){data.putUUID(CARD,UUID.randomUUID());write(stack,data);}return data.getUUID(CARD).toString();}
    static ItemStack held(ServerPlayer actor,ManagerTile manager,String card){
        for(ItemStack stack:new ItemStack[]{actor.getMainHandItem(),actor.getOffhandItem()})
            if(matches(stack,actor,manager)&&!card.isEmpty()&&card.equals(card(stack)))return stack;
        throw SeatRegistry.error("continuous_card");
    }
    static boolean configured(ItemStack stack){return data(stack).contains(CONTINUOUS);}
    static ContinuousSeatState continuous(ItemStack stack,ServerPlayer actor,ManagerTile manager){
        if(!matches(stack,actor,manager))throw SeatRegistry.error("continuous_card");
        CompoundTag data=data(stack);if(!data.contains(CONTINUOUS))return null;
        ContinuousSeatState state=ContinuousSeatState.decode(data.getString(CONTINUOUS));
        state.requireScope(actor.getUUID().toString(),manager.identity.toString(),actor.level().dimension().location().toString(),manager.getBlockPos().asLong());return state;
    }
    static void continuous(ItemStack stack,ContinuousSeatState state){CompoundTag data=data(stack);if(state==null)data.remove(CONTINUOUS);else data.putString(CONTINUOUS,state.encode());write(stack,data);}
    static RowSelectionState rowSelection(ItemStack stack,ServerPlayer actor,ManagerTile manager){
        if(!matches(stack,actor,manager))throw SeatRegistry.error("batch_card");
        CompoundTag data=data(stack);if(!data.contains(ROW_SELECTION))return null;
        RowSelectionState state=RowSelectionState.decode(data.getString(ROW_SELECTION));
        state.requireScope(actor.getUUID().toString(),manager.identity.toString(),actor.level().dimension().location().toString(),manager.getBlockPos().asLong(),card(stack));
        return state;
    }
    static void rowSelection(ItemStack stack,RowSelectionState state){CompoundTag data=data(stack);if(state==null)data.remove(ROW_SELECTION);else data.putString(ROW_SELECTION,state.encode());write(stack,data);}
    static ManagerTile controller(ServerPlayer actor,ItemStack stack){
        CompoundTag data=data(stack);
        if(!data.hasUUID("identity")||!data.getString("dimension").equals(actor.level().dimension().location().toString()))throw SeatRegistry.error("bind_first");
        BlockPos source=BlockPos.of(data.getLong("manager"));
        if(!actor.level().hasChunkAt(source)||!(actor.level().getBlockEntity(source) instanceof ManagerTile manager)||!matches(stack,actor,manager))throw SeatRegistry.error("bind_first");
        if(!SeatNetwork.allowed(actor,manager))throw SeatRegistry.error("permission");return manager;
    }
    private static void failure(ServerPlayer actor,RuntimeException failure){actor.displayClientMessage(Component.translatable(failure.getMessage()!=null&&failure.getMessage().startsWith("mycinema.seats.")?failure.getMessage():"mycinema.seats.invalid"),true);}
    @Override public InteractionResult useOn(UseOnContext context){
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;if(!(context.getPlayer() instanceof ServerPlayer actor))return InteractionResult.FAIL;
        ItemStack stack=context.getItemInHand();CompoundTag data=data(stack);
        try{
            if(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof SeatGuideTile guide){if(!guide.permits(actor,8))throw SeatRegistry.error("permission");SeatNetwork.openGuide(actor,guide);return InteractionResult.SUCCESS;}
            if(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof ManagerTile manager){
                if(!manager.permits(actor,8))throw SeatRegistry.error("permission");
                if(!matches(stack,actor,manager)){data.remove(CONTINUOUS);data.remove(ROW_SELECTION);RowBatchController.clear(actor);}
                data.putUUID("identity",manager.identity);data.putLong("manager",manager.getBlockPos().asLong());data.putString("dimension",actor.level().dimension().location().toString());write(stack,data);
                ensureCard(stack);SeatNetwork.open(actor,manager,null,stack);return InteractionResult.SUCCESS;
            }
            ManagerTile manager=controller(actor,stack);ensureCard(stack);
            RowSelectionState row=rowSelection(stack,actor,manager);
            if(row!=null&&row.active()&&!actor.isShiftKeyDown())RowBatchController.click(actor,manager,stack,context.getClickedPos());
            else if(configured(stack)&&!actor.isShiftKeyDown())SeatNetwork.bindContinuous(actor,manager,stack,context.getClickedPos());
            else SeatNetwork.open(actor,manager,actor.isShiftKeyDown()?null:context.getClickedPos(),stack);
        }catch(RuntimeException failure){failure(actor,failure);}
        return InteractionResult.SUCCESS;
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(!level.isClientSide&&player instanceof ServerPlayer actor)try{
            // A safe local exit also works when the old controller is unloaded, replaced or unbound.
            if(actor.isShiftKeyDown()){continuous(stack,null);rowSelection(stack,null);RowBatchController.clear(actor);actor.containerMenu.broadcastChanges();actor.displayClientMessage(Component.translatable("mycinema.seats.numbering_stopped"),true);}
            else{ManagerTile manager=controller(actor,stack);ensureCard(stack);if(rowSelection(stack,actor,manager)!=null)RowBatchController.open(actor,manager,stack,"",true,"");else SeatNetwork.open(actor,manager,null,stack);}
        }catch(RuntimeException failure){failure(actor,failure);}
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
    }
}
