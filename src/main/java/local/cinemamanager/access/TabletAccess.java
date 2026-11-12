package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.doors.CinemaDoorBlock;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Inventory-carried, player-bound TMS authority. Only an explicit door click can open entry. */
public final class TabletAccess {
    private TabletAccess(){}
    private static UUID token(ItemStack stack,ServerPlayer actor){
        if(stack.isEmpty()||!stack.is(CinemaManager.TABLET.get()))return null;
        CompoundTag data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        return data.hasUUID("tabletAccess")&&data.hasUUID("tabletPlayer")&&data.getUUID("tabletPlayer").equals(actor.getUUID())
                &&data.getString("dimension").equals(actor.level().dimension().location().toString())?data.getUUID("tabletAccess"):null;
    }
    public static void activate(ServerPlayer actor,ManagerTile manager,ItemStack stack,CompoundTag binding){
        if(!StaffReaderTile.real(actor)||!actor.isAlive()||manager.getLevel()!=actor.level()||manager.isRemoved()||manager.owner==null
                ||!actor.level().hasChunkAt(manager.getBlockPos())||actor.level().getBlockEntity(manager.getBlockPos())!=manager
                ||!manager.permits(actor,8)||!stack.is(CinemaManager.TABLET.get())
                ||(actor.getMainHandItem()!=stack&&actor.getOffhandItem()!=stack))throw new IllegalArgumentException("mycinema.access.permission");
        var registry=StaffAccessRegistry.get(actor.serverLevel());
        // Reserve first: a failed destination activation must preserve the old item and grant.
        UUID previousToken=token(stack,actor);
        var grant=registry.tablets.activate(actor.getUUID(),new TabletAccessBook.Scope(manager.identity,manager.owner,manager.getBlockPos().asLong()),previousToken);
        revoke(actor,stack);registry.setDirty();
        binding.putLong("manager",manager.getBlockPos().asLong());binding.putUUID("identity",manager.identity);
        binding.putString("dimension",actor.level().dimension().location().toString());binding.putString("name",manager.state.name);
        binding.putUUID("tabletAccess",grant.token());binding.putUUID("tabletPlayer",actor.getUUID());binding.putString("tabletPlayerName",actor.getGameProfile().getName());
        stampVersion(binding,grant.scope());
    }
    private static void stampVersion(CompoundTag data,TabletAccessBook.Scope scope){
        data.putInt("tabletAccessVersion",TabletAccessBook.CURRENT_VERSION);data.putUUID("tabletControllerOwner",scope.owner());
    }
    /** Unbinding revokes all copies of this activation, including from another dimension. */
    public static void revoke(ServerPlayer actor,ItemStack stack){
        if(!stack.is(CinemaManager.TABLET.get()))return;
        CompoundTag data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(!data.hasUUID("tabletAccess"))return;
        ResourceLocation dimension=ResourceLocation.tryParse(data.getString("dimension"));if(dimension==null)return;
        ServerLevel level=actor.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,dimension));if(level==null)return;
        var registry=StaffAccessRegistry.get(level);if(registry.tablets.revoke(data.getUUID("tabletAccess"),actor.getUUID()))registry.setDirty();
    }
    private static boolean registered(ServerPlayer actor,ItemStack stack,StaffAccessRegistry registry,TabletAccessBook.Scope expected){
        UUID token=token(stack,actor);if(token==null)return false;
        CompoundTag data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(!data.hasUUID("identity")||!expected.controller().equals(data.getUUID("identity"))
                ||!data.contains("manager",Tag.TAG_LONG)||data.getLong("manager")!=expected.pos())return false;
        boolean legacy=!data.contains("tabletAccessVersion")&&!data.contains("tabletControllerOwner");
        int version=legacy?TabletAccessBook.CURRENT_VERSION:data.getInt("tabletAccessVersion");
        if(!legacy&&(!data.contains("tabletAccessVersion",Tag.TAG_INT)||!data.hasUUID("tabletControllerOwner")
                ||!expected.owner().equals(data.getUUID("tabletControllerOwner"))))return false;
        if(!registry.tablets.authorize(token,actor.getUUID(),expected,version))return false;
        // Only an already valid server registration can migrate pre-version item NBT.
        if(legacy){stampVersion(data,expected);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));actor.getInventory().setChanged();}
        return true;
    }
    /** Emergency remote control requires live access authority, not the legacy media binding alone. */
    public static boolean registeredHeld(ServerPlayer actor,ManagerTile manager){
        if(!StaffReaderTile.real(actor)||!actor.isAlive()||manager==null||manager.getLevel()!=actor.level()
                ||manager.isRemoved()||manager.owner==null||!actor.level().hasChunkAt(manager.getBlockPos())
                ||actor.level().getBlockEntity(manager.getBlockPos())!=manager||!manager.permits(actor,Double.POSITIVE_INFINITY))return false;
        var registry=StaffAccessRegistry.get(actor.serverLevel());
        var expected=new TabletAccessBook.Scope(manager.identity,manager.owner,manager.getBlockPos().asLong());
        return registered(actor,actor.getMainHandItem(),registry,expected)||registered(actor,actor.getOffhandItem(),registry,expected);
    }
    private static boolean carried(ServerPlayer actor,AccessAuthorization.Context context){
        if(!context.manager().permits(actor,Double.POSITIVE_INFINITY))return false;
        var scope=context.scope();var expected=new TabletAccessBook.Scope(scope.controller(),scope.controllerOwner(),scope.controllerPos());
        // Inventory includes the offhand, but never traverses chests, bundles or dropped entities.
        for(int slot=0;slot<actor.getInventory().getContainerSize();slot++)
            if(registered(actor,actor.getInventory().getItem(slot),context.registry(),expected))return true;
        return false;
    }
    /** Explicit block interaction only, with the normal server block-interaction reach check. */
    public static boolean click(ServerPlayer actor,BlockPos clicked){
        if(!StaffReaderTile.real(actor)||!actor.isAlive()||clicked==null||!actor.canInteractWithBlock(clicked,1.0)
                ||!actor.level().hasChunkAt(clicked)||!actor.level().mayInteract(actor,clicked)
                ||(!(actor.level().getBlockState(clicked).getBlock() instanceof CinemaDoorBlock)&&!FixedIronDoorAccess.candidate(actor.level(),clicked)))return false;
        BlockPos base=FixedIronDoorAccess.candidate(actor.level(),clicked)?FixedIronDoorAccess.base(clicked):CinemaDoorBlock.lowerPos(clicked,actor.level().getBlockState(clicked));
        var binding=StaffAccessRegistry.get(actor.serverLevel()).door(base.asLong());
        return AccessAuthorization.open(actor,binding,context->carried(actor,context));
    }
}
