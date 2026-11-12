package local.cinemamanager.access;

import java.util.UUID;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

public final class StaffCardItem extends Item {
    public StaffCardItem(Properties properties){super(properties);}
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){
        return context.getLevel().hasChunkAt(context.getClickedPos())&&context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof StaffReaderBlock?useOn(context):InteractionResult.PASS;
    }
    public static UUID token(ItemStack stack){if(stack==null||!stack.is(AccessModule.CARD.get()))return null;CompoundTag data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();return data.hasUUID("staffCard")?data.getUUID("staffCard"):null;}
    /** Cosmetic only: an icon is never proof of a live server grant. Legacy written cards stay visibly written. */
    public static int visualState(ItemStack stack){
        if(token(stack)==null)return 0;
        String role=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("staffVisualRole");
        return switch(role){case "ENTRY"->1;case "SERVICE"->2;default->3;};
    }
    public static void stamp(ItemStack stack,StaffGrantBook.Grant grant,String targetName){
        CompoundTag data=new CompoundTag();data.putUUID("staffCard",grant.card());data.putString("staffVisualRole",grant.role().name());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        stack.set(DataComponents.CUSTOM_NAME,Component.translatable("mycinema.access.card_label",targetName,Component.translatable("mycinema.access.role."+grant.role().name().toLowerCase(java.util.Locale.ROOT))));
    }
    @Override public void appendHoverText(ItemStack stack,Item.TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        tooltip.add(Component.translatable("mycinema.access.card_visual."+visualState(stack)));
        if(token(stack)!=null)tooltip.add(Component.translatable("mycinema.access.card_visual.authority"));
    }
    /** Same server path used by physical swipes and loaded-world permission tests. */
    public static boolean swipe(ServerPlayer actor,StaffReaderTile reader,ItemStack stack){
        if(!StaffReaderTile.real(actor)||reader==null||actor.level()!=reader.getLevel()||reader.isRemoved()
                ||actor.distanceToSqr(reader.getBlockPos().getCenter())>64||!actor.level().hasChunkAt(reader.getBlockPos())
                ||actor.level().getBlockEntity(reader.getBlockPos())!=reader||!actor.level().mayInteract(actor,reader.getBlockPos())
                ||(actor.getMainHandItem()!=stack&&actor.getOffhandItem()!=stack))return false;
        var scope=reader.currentScope();UUID card=token(stack);var registry=StaffAccessRegistry.get(actor.serverLevel());
        if(scope==null||card==null||!registry.cards.authorize(card,actor.getUUID(),scope,reader.required))return false;
        var binding=registry.door(scope.doorPos());
        return AccessAuthorization.open(actor,binding,context->context.reader()==reader
                &&context.registry().cards.authorize(card,actor.getUUID(),context.scope(),reader.required));
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        if(!(context.getPlayer() instanceof ServerPlayer actor)||!StaffReaderTile.real(actor))return InteractionResult.FAIL;
        if(!context.getLevel().hasChunkAt(context.getClickedPos()))return InteractionResult.FAIL;
        if(!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof StaffReaderTile reader))return InteractionResult.PASS;
        if(actor.isShiftKeyDown())try{reader.requireEditor(actor);AccessNetwork.open(actor,reader);return InteractionResult.SUCCESS;}catch(IllegalArgumentException denied){/* ordinary swipe below */}
        if(!reader.acceptSwipe(actor))return InteractionResult.SUCCESS;
        String notice=swipe(actor,reader,context.getItemInHand())?"mycinema.access.opened":"mycinema.access.denied";
        actor.displayClientMessage(Component.translatable(notice),true);return InteractionResult.SUCCESS;
    }
}
