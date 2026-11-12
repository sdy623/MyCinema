package local.cinemamanager;
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
import java.util.List;
/** Every packet rechecks the exact tablet currently held and the live controller identity. */
public final class TabletItem extends Item {
    public TabletItem(Properties p){super(p);}
    public static boolean boundTo(Player player,ManagerTile manager){return player.level()==manager.getLevel()&&boundTo(player,manager.getBlockPos(),manager.identity.toString());}
    public static boolean boundTo(Player player,BlockPos manager,String identity){
        for(InteractionHand hand:InteractionHand.values()){
            ItemStack stack=player.getItemInHand(hand);if(!(stack.getItem() instanceof TabletItem))continue;
            CompoundTag d=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            if((!d.hasUUID("tabletPlayer")||d.getUUID("tabletPlayer").equals(player.getUUID()))&&d.hasUUID("identity")&&identity.equals(d.getUUID("identity").toString())&&d.contains("manager")&&BlockPos.of(d.getLong("manager")).equals(manager)
                    &&d.getString("dimension").equals(player.level().dimension().location().toString()))return true;
        }
        return false;
    }
    /** Route explicit door clicks before vanilla secondary-use skips the block interaction. */
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext c){
        InteractionResult door=useDoor(c);
        return door==null?InteractionResult.PASS:door;
    }
    private InteractionResult useDoor(UseOnContext c){
        Level level=c.getLevel();BlockPos pos=c.getClickedPos();
        if(!level.hasChunkAt(pos)||!(level.getBlockState(pos).getBlock() instanceof local.cinemamanager.doors.CinemaDoorBlock))return null;
        // Keep the door's shared emergency, registered-inventory authority and free-egress logic.
        // No item type alone grants access, and no door click falls through to tablet unbinding.
        if(level.isClientSide)return InteractionResult.SUCCESS;
        if(!(c.getPlayer() instanceof ServerPlayer actor)||!local.cinemamanager.access.StaffReaderTile.real(actor)
                ||!actor.isAlive()||!c.getItemInHand().is(CinemaManager.TABLET.get())
                ||actor.getItemInHand(c.getHand())!=c.getItemInHand()
                ||!actor.canInteractWithBlock(pos,1.0)||!level.mayInteract(actor,pos))return InteractionResult.FAIL;
        return level.getBlockState(pos).useWithoutItem(level,actor,
                new net.minecraft.world.phys.BlockHitResult(c.getClickLocation(),c.getClickedFace(),pos,false));
    }
    @Override public InteractionResult useOn(UseOnContext c){
        InteractionResult door=useDoor(c);if(door!=null)return door;
        if(c.getPlayer()!=null&&c.getPlayer().isShiftKeyDown()&&c.getLevel().getBlockEntity(c.getClickedPos()) instanceof ManagerTile m){
            if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;
            ServerPlayer actor=(ServerPlayer)c.getPlayer();
            if(!m.permits(actor,8)){actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m062")),false);return InteractionResult.FAIL;}
            CompoundTag d=new CompoundTag();d.putLong("manager",m.getBlockPos().asLong());d.putUUID("identity",m.identity);d.putString("dimension",c.getLevel().dimension().location().toString());d.putString("name",m.state.name);
            try{local.cinemamanager.access.TabletAccess.activate(actor,m,c.getItemInHand(),d);}catch(IllegalArgumentException denied){actor.displayClientMessage(Component.translatable(denied.getMessage()),true);return InteractionResult.FAIL;}
            c.getItemInHand().set(DataComponents.CUSTOM_DATA,CustomData.of(d));actor.getInventory().setChanged();actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m063")+m.state.name+local.cinemamanager.MessageText.key("message.m064")),false);return InteractionResult.SUCCESS;
        }
        if(c.getPlayer()!=null&&c.getPlayer().isShiftKeyDown()){
            if(!c.getLevel().isClientSide)c.getPlayer().displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m065")),true);
            return InteractionResult.sidedSuccess(c.getLevel().isClientSide);
        }
        return use(c.getLevel(),c.getPlayer(),c.getHand()).getResult();
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);if(level.isClientSide)return InteractionResultHolder.success(stack);
        ServerPlayer actor=(ServerPlayer)player;
        if(player.isShiftKeyDown()){local.cinemamanager.access.TabletAccess.revoke(actor,stack);stack.remove(DataComponents.CUSTOM_DATA);actor.getInventory().setChanged();actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m066")),false);return InteractionResultHolder.success(stack);}
        CompoundTag d=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(!d.hasUUID("identity")){actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m067")),false);return InteractionResultHolder.fail(stack);}
        if(!d.getString("dimension").equals(level.dimension().location().toString())){actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m068")),false);return InteractionResultHolder.fail(stack);}
        BlockPos pos=BlockPos.of(d.getLong("manager"));
        if(!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof ManagerTile m)||!m.identity.equals(d.getUUID("identity"))){actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m069")),false);return InteractionResultHolder.fail(stack);}
        if(!m.permits(actor,Double.POSITIVE_INFINITY)||!boundTo(actor,m)||!local.cinemamanager.policy.CinemaPermissions.canInteractRemote(actor)){actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m070")),false);return InteractionResultHolder.fail(stack);}
        ManagerNetwork.openRemote(actor,m);return InteractionResultHolder.success(stack);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        CompoundTag d=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(d.hasUUID("identity")){tooltip.add(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m071")+d.getString("name")));tooltip.add(local.cinemamanager.MessageText.component(d.getString("dimension")+" "+BlockPos.of(d.getLong("manager")).toShortString()));tooltip.add(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m072")));}
        else tooltip.add(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m073")));
        if(d.hasUUID("tabletAccess")&&d.hasUUID("tabletPlayer")){tooltip.add(Component.translatable("mycinema.tablet.access.player",d.getString("tabletPlayerName")));tooltip.add(Component.translatable("mycinema.tablet.access.inventory"));}
        else if(d.hasUUID("identity"))tooltip.add(Component.translatable("mycinema.tablet.access.reactivate"));
        tooltip.add(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m074")));
    }
}
