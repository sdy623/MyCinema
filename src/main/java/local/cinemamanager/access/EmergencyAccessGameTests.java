package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.doors.*;
import local.cinemamanager.model.ManagerState;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.gametest.*;

/** Opt-in isolated world checks. They exercise access only; no media or ticket fixtures are claimed. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class EmergencyAccessGameTests {
    private static final class Actor extends ServerPlayer {
        boolean spectating;
        @Override public boolean isSpectator(){return spectating;}
        Actor(ServerLevel level,UUID id){super(level.getServer(),level,new com.mojang.authlib.GameProfile(id,"EmergencyQA"),ClientInformation.createDefault());}
        @Override public BlockPos adjustSpawnLocation(ServerLevel level,BlockPos pos){return pos;}
        @Override public void displayClientMessage(net.minecraft.network.chat.Component text,boolean overlay){}
    }
    private record Fixture(ManagerTile manager,StaffReaderTile reader,StaffAccessRegistry.Door binding,BlockPos relative,CinemaDoorBlock door,Actor actor){}
    private static void isolated(GameTestHelper h){h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"isolated opt-in QA only");}
    private static ManagerTile manager(GameTestHelper h,BlockPos pos,UUID owner){h.setBlock(pos,CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(pos);manager.initializePlacement(owner);return manager;}
    private static StaffAccessRegistry.Door door(GameTestHelper h,ManagerTile manager,BlockPos pos,BlockPos readerPos,DoorHingeSide hinge,boolean legacy){
        var door=CinemaManager.DOOR_METAL.get();h.setBlock(pos.below(),Blocks.STONE);var state=door.defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.NORTH).setValue(CinemaDoorBlock.HINGE,hinge).setValue(CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
        for(var part:DoorPart.values())h.getLevel().setBlock(h.absolutePos(pos.above(part.offset())),state.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.setBlock(readerPos,AccessModule.READER.get());StaffReaderTile reader=h.getBlockEntity(readerPos);reader.initialize(manager.owner);
        reader.controllerPos=manager.getBlockPos();reader.controllerId=manager.identity;reader.controllerOwner=manager.owner;
        var leaves=List.of(new StaffAccessRegistry.Leaf(h.absolutePos(pos).asLong(),"mycinema:cinema_door_metal|north|"+hinge.getSerializedName()+"|staff_front|staff_front|staff_front"));
        var binding=legacy?new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),leaves):
                new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),leaves,manager.identity,manager.owner,manager.getBlockPos().asLong());
        StaffAccessRegistry.get(h.getLevel()).bind(binding);reader.doorPos=h.absolutePos(pos);reader.doorId=binding.token;return binding;
    }
    private static Fixture fixture(GameTestHelper h,boolean legacy){
        isolated(h);ManagerTile manager=manager(h,new BlockPos(1,1,1),UUID.randomUUID());BlockPos pos=new BlockPos(5,1,5),readerPos=new BlockPos(2,1,3);
        var binding=door(h,manager,pos,readerPos,DoorHingeSide.LEFT,legacy);Actor actor=new Actor(h.getLevel(),manager.owner);actor.setPos(manager.getBlockPos().getCenter());
        return new Fixture(manager,h.getBlockEntity(readerPos),binding,pos,CinemaManager.DOOR_METAL.get(),actor);
    }
    private static void denied(GameTestHelper h,Runnable action,String label){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,label);}
    private static ItemStack activateTablet(GameTestHelper h,Actor actor,ManagerTile manager){
        var tablet=new ItemStack(CinemaManager.TABLET.get());actor.setItemInHand(InteractionHand.MAIN_HAND,tablet);
        actor.setPos(manager.getBlockPos().getCenter());actor.setShiftKeyDown(true);
        var context=new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(manager.getBlockPos().getCenter(),Direction.UP,manager.getBlockPos(),false));
        h.assertTrue(CinemaManager.TABLET.get().useOn(context)==InteractionResult.SUCCESS,"real local terminal activation succeeds");actor.setShiftKeyDown(false);return tablet;
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_scope_does_not_unlock_adjacent_cinema_or_mutate_programme(GameTestHelper h){
        var f=fixture(h,false);var other=manager(h,new BlockPos(3,1,1),f.manager.owner);
        var unrelated=door(h,other,f.relative.east(),new BlockPos(3,1,3),DoorHingeSide.RIGHT,false);
        f.manager.state.schedulerEnabled=true;f.manager.state.timezone="UTC";
        var hall=new ManagerState.Hall();hall.terminalIdentity=UUID.randomUUID().toString();hall.transport=ManagerState.Transport.PLAYING;hall.override=true;f.manager.state.halls.add(hall);
        EmergencyAccess.change(f.actor,f.manager,true,0,false);
        h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,true);h.assertBlockProperty(f.relative.east(),CinemaDoorBlock.OPEN,false);
        h.assertFalse(EmergencyAccess.heldOpen(h.getLevel(),BlockPos.of(unrelated.base())),"same owner does not join two cinemas");
        h.assertTrue(f.manager.state.schedulerEnabled&&hall.transport==ManagerState.Transport.PLAYING&&hall.override,"emergency access leaves programme and schedule state untouched");
        h.assertFalse(f.binding.held(h.getLevel().getGameTime()),"override does not create a staff swipe lease");
        var visitor=h.makeMockPlayer(GameType.SURVIVAL);visitor.setPos(h.absolutePos(f.relative).getCenter());
        h.assertTrue(EmergencyAccess.keepOpen(visitor,h.getLevel(),h.absolutePos(f.relative)),"visitor uses emergency passage without credential");
        h.assertBlockProperty(f.relative.east(),CinemaDoorBlock.OPEN,false);
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,false,1,false),"restore needs explicit confirmation");
        EmergencyAccess.change(f.actor,f.manager,false,1,true);
        h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);h.assertBlockProperty(f.relative.east(),CinemaDoorBlock.OPEN,false);
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,0,false),"old activation cannot replay after restore");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_rechecks_real_manager_authority_and_ignores_snapshot_flags(GameTestHelper h){
        var f=fixture(h,false);Actor outsider=new Actor(h.getLevel(),UUID.randomUUID());outsider.setPos(f.actor.position());
        denied(h,()->EmergencyAccess.change(outsider,f.manager,true,0,false),"outsider cannot unlock");
        var fake=new FakePlayer(h.getLevel(),new com.mojang.authlib.GameProfile(f.manager.owner,"EmergencyFake"));fake.setPos(f.actor.position());
        denied(h,()->EmergencyAccess.change(fake,f.manager,true,0,false),"fake owner cannot unlock");
        f.actor.spectating=true;denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,0,false),"spectator owner cannot unlock");f.actor.spectating=false;
        f.manager.state.emergencyActive=true;f.manager.state.emergencyAllowed=true;
        h.assertFalse(EmergencyAccess.heldOpen(h.getLevel(),h.absolutePos(f.relative)),"forged presentation is not authority");
        var view=new ManagerState();EmergencyAccess.describe(f.manager,view,f.actor,true);h.assertFalse(view.emergencyAllowed,"read-only snapshot has no action permission");
        f.actor.setPos(f.manager.getBlockPos().getCenter().add(10,0,0));denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,0,false),"remote actor without authorized tablet is denied");
        h.assertTrue(EmergencyAccess.get(h.getLevel()).audit().stream().noneMatch(a->a.scope().controller().equals(f.manager.identity)),"denied actions did not record successful changes");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_remote_requires_live_registered_tablet_and_preserves_local_control(GameTestHelper h){
        var f=fixture(h,false);
        h.assertTrue(EmergencyAccess.allowed(f.actor,f.manager),"nearby owner may use the terminal without a tablet");
        var tablet=activateTablet(h,f.actor,f.manager);var original=tablet.get(DataComponents.CUSTOM_DATA).copyTag();
        f.actor.setPos(f.manager.getBlockPos().getCenter().add(10,0,0));
        EmergencyAccess.change(f.actor,f.manager,true,0,false);h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,true);
        EmergencyAccess.change(f.actor,f.manager,false,1,true);h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);
        for(String key:List.of("tabletAccess","tabletPlayer","identity","tabletControllerOwner")){
            var changed=original.copy();changed.putUUID(key,UUID.randomUUID());tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
            denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"remote emergency rejects changed "+key);
        }
        for(int version:new int[]{0,TabletAccessBook.CURRENT_VERSION+1}){
            var changed=original.copy();changed.putInt("tabletAccessVersion",version);tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
            denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"remote emergency rejects unsupported tablet version");
        }
        var changed=original.copy();changed.putString("tabletAccessVersion","1");tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"remote emergency rejects wrong version NBT type");
        changed=original.copy();changed.putString("dimension","minecraft:the_nether");tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"remote emergency rejects another dimension");
        changed=original.copy();changed.putLong("manager",f.manager.getBlockPos().above().asLong());tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"remote emergency rejects another controller position");
        var registry=StaffAccessRegistry.get(h.getLevel());var scope=new TabletAccessBook.Scope(f.manager.identity,f.manager.owner,f.manager.getBlockPos().asLong());
        var otherPlayer=registry.tablets.activate(UUID.randomUUID(),scope);
        changed=original.copy();changed.putUUID("tabletAccess",otherPlayer.token());tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"item holder NBT cannot claim another player's real server registration");
        var otherCinema=registry.tablets.activate(f.actor.getUUID(),new TabletAccessBook.Scope(UUID.randomUUID(),f.manager.owner,scope.pos()+1));
        changed=original.copy();changed.putUUID("tabletAccess",otherCinema.token());tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"item cinema NBT cannot repurpose another controller's real registration");
        var legacy=original.copy();legacy.remove("tabletAccessVersion");legacy.remove("tabletControllerOwner");
        changed=legacy.copy();changed.remove("tabletAccess");changed.remove("tabletPlayer");tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(changed));
        h.assertTrue(TabletItem.boundTo(f.actor,f.manager),"legacy media binding remains compatible");
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"NBT-only media binding cannot authorize emergency unlock");
        tablet.set(DataComponents.CUSTOM_DATA,CustomData.of(legacy));
        f.actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);f.actor.setItemInHand(InteractionHand.OFF_HAND,tablet);
        EmergencyAccess.change(f.actor,f.manager,true,2,false);
        var migrated=tablet.get(DataComponents.CUSTOM_DATA).copyTag();
        h.assertTrue(migrated.getInt("tabletAccessVersion")==TabletAccessBook.CURRENT_VERSION&&migrated.getUUID("tabletControllerOwner").equals(f.manager.owner),"valid registered legacy offhand tablet migrates through remote emergency authority");
        EmergencyAccess.change(f.actor,f.manager,false,3,true);
        var revokedCopy=tablet.copy();TabletAccess.revoke(f.actor,tablet);f.actor.setItemInHand(InteractionHand.OFF_HAND,revokedCopy);
        h.assertTrue(TabletItem.boundTo(f.actor,f.manager),"revoked copy still has its old media binding");
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,4,false),"revoked physical copy cannot remotely reopen the cinema");
        h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);
        h.assertTrue(EmergencyAccess.get(h.getLevel()).state(f.manager).revision()==4,"all rejected credentials leave emergency revision unchanged");
        f.actor.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);f.actor.setPos(f.manager.getBlockPos().getCenter());
        EmergencyAccess.change(f.actor,f.manager,true,4,false);EmergencyAccess.change(f.actor,f.manager,false,5,true);
        h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_restart_persists_state_audit_and_door_scope(GameTestHelper h){
        var f=fixture(h,true);var registry=StaffAccessRegistry.get(h.getLevel());EmergencyAccess.change(f.actor,f.manager,true,0,false);
        h.assertTrue(f.binding.controller.equals(f.manager.identity),"legacy binding migrates only from the live matching reader");
        var data=EmergencyAccess.get(h.getLevel());var restored=EmergencyAccess.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(restored.state(f.manager).active()&&restored.state(f.manager).revision()==1,"active override survives saved-data reload");
        var action=restored.audit().stream().filter(a->a.scope().controller().equals(f.manager.identity)).findFirst().orElseThrow();
        h.assertTrue(action.actor().equals(f.actor.getUUID())&&action.active(),"audit stores real actor and activation");
        var doors=StaffAccessRegistry.load(registry.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(doors.door(f.binding.base()).controller.equals(f.manager.identity),"door cinema scope survives registry reload");
        EmergencyAccess.change(f.actor,f.manager,false,1,true);
        var normal=EmergencyAccess.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertFalse(normal.state(f.manager).active(),"explicit restoration survives reload");h.assertTrue(normal.state(f.manager).revision()==2,"restoration preserves monotonic revision");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_chunk_load_defers_migration_until_tick_and_reconciles_saved_override(GameTestHelper h){
        var f=fixture(h,false);var level=h.getLevel();var registry=StaffAccessRegistry.get(level);
        EmergencyAccess.change(f.actor,f.manager,true,0,false);
        // Model an admitted legacy registry with an independently persisted active override.
        var legacy=new StaffAccessRegistry.Door(f.binding.token,f.binding.reader,f.binding.readerPos,f.binding.leaves);
        registry.bind(legacy);
        for(var part:DoorPart.values()){
            BlockPos pos=h.absolutePos(f.relative.above(part.offset()));
            level.setBlock(pos,level.getBlockState(pos).setValue(CinemaDoorBlock.OPEN,false),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        }
        BlockPos absent=new BlockPos(260000,80,260000);
        var unloaded=new StaffAccessRegistry.Door(UUID.randomUUID(),UUID.randomUUID(),absent.asLong(),List.of(new StaffAccessRegistry.Leaf(absent.asLong(),"mycinema:cinema_door_metal|north|left|staff_front|staff_front|staff_front")));
        registry.bind(unloaded);
        var chunk=level.getChunkSource().getChunkNow(f.reader.getBlockPos().getX()>>4,f.reader.getBlockPos().getZ()>>4);
        h.assertTrue(chunk!=null,"fixture reader chunk is already fully available");
        for(int i=0;i<4096;i++)EmergencyAccess.chunkLoaded(new ChunkEvent.Load(chunk,false));
        h.assertTrue(legacy.controller==null,"load callback only queues; even a loaded reader must not migrate inline");
        h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);
        h.assertTrue(level.getChunkSource().getChunkNow(absent.getX()>>4,absent.getZ()>>4)==null,"enqueueing never acquires an absent chunk");
        h.runAfterDelay(3,()->{
            try{
                h.assertTrue(f.manager.identity.equals(legacy.controller)&&f.manager.owner.equals(legacy.controllerOwner),"later server tick migrates the admitted original reader and controller");
                h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,true);
                h.assertTrue(EmergencyAccess.heldOpen(level,h.absolutePos(f.relative)),"deferred synchronization restores persisted cinema override");
                h.assertTrue(unloaded.controller==null&&level.getChunkSource().getChunkNow(absent.getX()>>4,absent.getZ()>>4)==null,"deferred migration neither guesses unloaded ownership nor force-loads");
                h.assertTrue(EmergencyAccess.get(level).state(f.manager).revision()==1,"coalesced chunk notifications never change override revision");
                EmergencyAccess.change(f.actor,f.manager,false,1,true);h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);h.succeed();
            }finally{registry.invalidate(absent.asLong());}
        });
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_unloaded_bound_doors_never_force_load(GameTestHelper h){
        var f=fixture(h,false);BlockPos absent=new BlockPos(250000,80,250000);h.assertFalse(h.getLevel().hasChunkAt(absent),"remote door starts unloaded");
        var pending=new StaffAccessRegistry.Door(UUID.randomUUID(),UUID.randomUUID(),absent.asLong(),List.of(new StaffAccessRegistry.Leaf(absent.asLong(),"mycinema:cinema_door_metal|north|left|staff_front|staff_front|staff_front")),f.manager.identity,f.manager.owner,f.manager.getBlockPos().asLong());
        var registry=StaffAccessRegistry.get(h.getLevel());registry.bind(pending);
        try{
            EmergencyAccess.change(f.actor,f.manager,true,0,false);h.assertFalse(h.getLevel().hasChunkAt(absent),"activation never loads an absent door chunk");
            var view=new ManagerState();EmergencyAccess.describe(f.manager,view,f.actor,false);h.assertTrue(view.emergencyPendingDoors==1,"pending leaf is explicitly visible");
            EmergencyAccess.change(f.actor,f.manager,false,1,true);h.assertFalse(h.getLevel().hasChunkAt(absent),"restoration never loads an absent door chunk");
        }finally{registry.invalidate(absent.asLong());}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_observed_replacement_cannot_revive_after_reload(GameTestHelper h){
        var f=fixture(h,false);EmergencyAccess.change(f.actor,f.manager,true,0,false);UUID original=f.manager.identity;
        f.manager.identity=UUID.randomUUID();h.assertFalse(EmergencyAccess.heldOpen(h.getLevel(),h.absolutePos(f.relative)),"loaded replacement invalidates original scope");
        f.manager.identity=original;
        h.assertFalse(EmergencyAccess.heldOpen(h.getLevel(),h.absolutePos(f.relative)),"restoring a formerly matching identity cannot revive its retired override");
        var data=EmergencyAccess.get(h.getLevel());var reload=EmergencyAccess.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(reload.state(f.manager).retired()&&!reload.state(f.manager).active(),"retirement persists independently of chunk availability");
        f.door.reconcileAccess(h.getLevel(),h.absolutePos(f.relative));h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);
        denied(h,()->EmergencyAccess.change(f.actor,f.manager,true,2,false),"retired controller identity cannot reauthorize");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void emergency_actual_controller_removal_retires_without_door_observation(GameTestHelper h){
        var f=fixture(h,false);EmergencyAccess.change(f.actor,f.manager,true,0,false);
        h.getLevel().removeBlock(f.manager.getBlockPos(),false);
        var data=EmergencyAccess.get(h.getLevel());
        h.assertTrue(data.state(f.manager).retired()&&!data.state(f.manager).active(),"actual removal retires active scope immediately before any door polling");
        var reload=EmergencyAccess.load(data.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(reload.state(f.manager).retired(),"removal tombstone survives save/reload");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=80)
    public static void emergency_late_loaded_binding_reconciles_and_restore_respects_redstone(GameTestHelper h){
        var f=fixture(h,false);EmergencyAccess.change(f.actor,f.manager,true,0,false);
        BlockPos late=new BlockPos(8,1,5);door(h,f.manager,late,new BlockPos(8,1,3),DoorHingeSide.LEFT,false);
        f.door.reconcileAccess(h.getLevel(),h.absolutePos(late));h.assertBlockProperty(late,CinemaDoorBlock.OPEN,true);
        h.setBlock(f.relative.below(),Blocks.REDSTONE_BLOCK);EmergencyAccess.change(f.actor,f.manager,false,1,true);
        h.assertBlockProperty(late,CinemaDoorBlock.OPEN,false);h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,true);
        h.setBlock(f.relative.below(),Blocks.STONE);h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);
        h.runAfterDelay(25,()->{h.assertBlockProperty(late,CinemaDoorBlock.OPEN,false);h.assertBlockProperty(f.relative,CinemaDoorBlock.OPEN,false);h.succeed();});
    }
}
