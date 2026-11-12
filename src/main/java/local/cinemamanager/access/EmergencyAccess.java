package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.doors.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Dimension-local emergency authority, independent of controller/reader chunk availability. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class EmergencyAccess extends SavedData {
    // One marker per live dimension, not one task/coordinate per chunk. Values never retain worlds.
    private static final Map<ServerLevel,Integer> PENDING_LOADS = new WeakHashMap<>();
    private final EmergencyAccessBook book = new EmergencyAccessBook();
    public static EmergencyAccess get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(EmergencyAccess::new,EmergencyAccess::load),"mycinema_emergency_access");
    }
    public static EmergencyAccessBook.Scope scope(ManagerTile manager) {
        return manager.owner==null?null:new EmergencyAccessBook.Scope(manager.identity,manager.owner,manager.getBlockPos().asLong());
    }
    private static EmergencyAccessBook.Scope scope(StaffAccessRegistry.Door door) {
        return door==null||door.controller==null||door.controllerOwner==null?null:
                new EmergencyAccessBook.Scope(door.controller,door.controllerOwner,door.controllerPos);
    }
    public EmergencyAccessBook.State state(ManagerTile manager) { return book.state(scope(manager)); }
    public List<EmergencyAccessBook.Audit> audit() { return book.audit(); }
    public static boolean allowed(ServerPlayer actor,ManagerTile manager) {
        return StaffReaderTile.real(actor)&&actor.isAlive()&&manager!=null&&manager.getLevel() instanceof ServerLevel level
                &&actor.level()==level&&!manager.isRemoved()&&manager.owner!=null
                &&level.hasChunkAt(manager.getBlockPos())&&level.getBlockEntity(manager.getBlockPos())==manager
                &&(manager.permits(actor,8)||TabletAccess.registeredHeld(actor,manager));
    }
    /** Called after the packet's manager identity check, and repeated here for all direct callers. */
    public static void change(ServerPlayer actor,ManagerTile manager,boolean active,long expected,boolean confirmed) {
        if(!allowed(actor,manager))throw new IllegalArgumentException("mycinema.emergency.permission");
        if(!active&&!confirmed)throw new IllegalArgumentException("mycinema.emergency.confirm_required");
        ServerLevel level=(ServerLevel)manager.getLevel();EmergencyAccess data=get(level);
        migrateLoaded(level);
        boolean changed=data.book.change(scope(manager),actor.getUUID(),active,expected,System.currentTimeMillis(),level.getGameTime());
        if(changed){
            data.setDirty();synchronize(level,scope(manager));
            var status=data.state(manager);
            org.slf4j.LoggerFactory.getLogger("MyCinema/EmergencyAccess").info(
                    "access_override controller={} owner={} position={} actor={} active={} revision={} time={}",
                    manager.identity,manager.owner,manager.getBlockPos().asLong(),actor.getUUID(),active,status.revision(),status.changedAt());
        }
        describe(manager,manager.state,actor,false);
        manager.notice(active?"mycinema.emergency.activated":"mycinema.emergency.restored");
    }
    /** Presentation is derived from the dimension authority; client/BE snapshot fields never authorize. */
    public static void describe(ManagerTile manager,ManagerState target,ServerPlayer actor,boolean readOnly) {
        target.emergencyAllowed=false;
        if(!(manager.getLevel() instanceof ServerLevel level))return;
        migrateLoaded(level);
        var data=get(level);var scope=scope(manager);var status=data.book.state(scope);
        target.emergencyActive=status.active();target.emergencyRevision=status.revision();target.emergencyChangedAt=status.changedAt();
        target.emergencyDoors=0;target.emergencyPendingDoors=0;
        for(var door:StaffAccessRegistry.get(level).allDoors())if(Objects.equals(scope,scope(door))&&scope!=null){
            target.emergencyDoors+=door.leaves.size();
            for(var leaf:door.leaves)if(!level.hasChunkAt(BlockPos.of(leaf.pos())))target.emergencyPendingDoors++;
        }
        target.emergencyAllowed=!readOnly&&!status.retired()&&allowed(actor,manager);
    }
    /** Old saves acquire a scope only from the original reader and a live, identity-checked controller. */
    public static void migrateLoaded(ServerLevel level) {
        var registry=StaffAccessRegistry.get(level);
        for(var door:registry.allDoors()){
            if(door.controller!=null)continue;BlockPos readerPos=BlockPos.of(door.readerPos);
            if(!(loadedBlockEntity(level,readerPos) instanceof StaffReaderTile reader)||reader.isRemoved()
                    ||!door.reader.equals(reader.identity)||!door.token.equals(reader.doorId)
                    ||reader.controllerPos==null||reader.controllerId==null||reader.controllerOwner==null
                    ||!(loadedBlockEntity(level,reader.controllerPos) instanceof ManagerTile manager)||manager.isRemoved()
                    ||!reader.controllerId.equals(manager.identity)||!reader.controllerOwner.equals(manager.owner))continue;
            if(registry.captureScope(door,manager.identity,manager.owner,manager.getBlockPos().asLong()))
                for(var leaf:door.leaves){BlockPos pos=BlockPos.of(leaf.pos());
                    var chunk=loadedChunk(level,pos);
                    if(chunk!=null&&chunk.getBlockState(pos).getBlock() instanceof CinemaDoorBlock block)level.scheduleTick(pos,block,1);
                }
        }
    }
    /** Neither helper waits for FULL admission, creates a chunk ticket, or instantiates a pending BE. */
    private static LevelChunk loadedChunk(ServerLevel level,BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
    }
    private static BlockEntity loadedBlockEntity(ServerLevel level,BlockPos pos) {
        var chunk=loadedChunk(level,pos);return chunk==null?null:chunk.getBlockEntities().get(pos);
    }
    /** Only this registered leaf may inherit this cinema's override, even beside another cinema. */
    public static boolean heldOpen(Level world,BlockPos base) {
        if(!(world instanceof ServerLevel level)||!level.hasChunkAt(base))return false;
        var binding=StaffAccessRegistry.get(level).door(base.asLong());var scope=scope(binding);
        if(scope==null||!get(level).book.state(scope).active())return false;
        BlockPos controllerPos=BlockPos.of(scope.pos());
        if(level.hasChunkAt(controllerPos)&&(!(level.getBlockEntity(controllerPos) instanceof ManagerTile manager)
                ||manager.isRemoved()||!scope.controller().equals(manager.identity)||!scope.owner().equals(manager.owner))){
            retireScope(level,scope);
            return false;
        }
        if(FixedIronDoorAccess.candidate(level,base))return FixedIronDoorAccess.capturedLeaf(level,binding,base);
        var state=level.getBlockState(base);
        if(!(state.getBlock() instanceof CinemaDoorBlock door)||!door.isComplete(level,base))return false;
        var captured=binding.leaves.stream().filter(leaf->leaf.pos()==base.asLong()).findFirst().orElse(null);
        if(captured==null)return false;
        StringBuilder shape=new StringBuilder(BuiltInRegistries.BLOCK.getKey(door).toString()).append('|')
                .append(state.getValue(CinemaDoorBlock.FACING).getSerializedName()).append('|')
                .append(state.getValue(CinemaDoorBlock.HINGE).getSerializedName());
        for(DoorPart part:DoorPart.values())shape.append('|').append(level.getBlockState(base.above(part.offset())).getValue(CinemaDoorBlock.ACCESS).getSerializedName());
        return shape.toString().equals(captured.shape());
    }
    /** Call on actual controller removal, including when no registered door chunk is loaded. */
    public static void retireController(ManagerTile manager) {
        var scope=scope(manager);
        if(scope!=null&&manager.getLevel() instanceof ServerLevel level)retireScope(level,scope);
    }
    private static void retireScope(ServerLevel level,EmergencyAccessBook.Scope scope) {
        EmergencyAccess data=get(level);
        if(!data.book.retire(scope,System.currentTimeMillis(),level.getGameTime()))return;
        data.setDirty();
        // Defer block updates: this may run inside an existing assembly reconciliation/removal.
        for(var related:StaffAccessRegistry.get(level).allDoors())if(scope.equals(scope(related)))
            for(var leaf:related.leaves){BlockPos pos=BlockPos.of(leaf.pos());
                if(level.hasChunkAt(pos)&&level.getBlockState(pos).getBlock() instanceof CinemaDoorBlock door)level.scheduleTick(pos,door,1);
            }
        org.slf4j.LoggerFactory.getLogger("MyCinema/EmergencyAccess").warn(
                "access_override_retired controller={} owner={} position={} reason=CONTROLLER_INVALID",scope.controller(),scope.owner(),scope.pos());
    }
    /** Emergency use neither creates a swipe lease nor spends a credential/ticket. */
    public static boolean keepOpen(Player actor,Level world,BlockPos base) {
        if(actor==null||actor.level()!=world||actor.isSpectator()||!world.mayInteract(actor,base)||!heldOpen(world,base))return false;
        if(world instanceof ServerLevel server&&FixedIronDoorAccess.candidate(world,base))return FixedIronDoorAccess.reconcile(server,StaffAccessRegistry.get(server).door(base.asLong()));
        var door=(CinemaDoorBlock)world.getBlockState(base).getBlock();
        door.reconcileAccess(world,base);world.scheduleTick(base,door,1);return true;
    }
    private static void synchronize(ServerLevel level,EmergencyAccessBook.Scope scope) {
        for(var binding:StaffAccessRegistry.get(level).allDoors())if(scope.equals(scope(binding)))
            for(var leaf:binding.leaves)synchronizeLeaf(level,BlockPos.of(leaf.pos()));
    }
    private static void synchronizeLeaf(ServerLevel level,BlockPos base) {
        var chunk=loadedChunk(level,base);
        if(chunk!=null&&chunk.getBlockState(base).getBlock() instanceof CinemaDoorBlock door){
            door.reconcileAccess(level,base);level.scheduleTick(base,door,1);
        }else if(chunk!=null&&FixedIronDoorAccess.candidate(level,base))FixedIronDoorAccess.reconcile(level,StaffAccessRegistry.get(level).door(base.asLong()));
    }
    /** Load fires before FULL promotion. MinecraftServer.execute may run inline here: only enqueue. */
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        synchronized(PENDING_LOADS){PENDING_LOADS.putIfAbsent(level,level.getServer().getTickCount()+1);}
    }
    /** Drain only after a later server tick, when the chunk-admission call stack has returned. */
    @SubscribeEvent public static void postTick(ServerTickEvent.Post event) {
        var ready=new ArrayList<ServerLevel>();
        synchronized(PENDING_LOADS){
            var pending=PENDING_LOADS.entrySet().iterator();
            while(pending.hasNext()){
                var entry=pending.next();
                if(entry.getKey().getServer()==event.getServer()&&event.getServer().getTickCount()-entry.getValue()>=0){
                    ready.add(entry.getKey());pending.remove();
                }
            }
        }
        for(var level:ready){
            if(event.getServer().getLevel(level.dimension())!=level)continue;
            migrateLoaded(level);
            // A bounded registry scan also handles readers/controllers admitted after their doors.
            for(var binding:StaffAccessRegistry.get(level).allDoors())for(var leaf:binding.leaves)
                synchronizeLeaf(level,BlockPos.of(leaf.pos()));
        }
    }
    @SubscribeEvent public static void levelUnloaded(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level)synchronized(PENDING_LOADS){PENDING_LOADS.remove(level);}
    }
    @SubscribeEvent public static void serverStopped(ServerStoppedEvent event) {
        synchronized(PENDING_LOADS){PENDING_LOADS.keySet().removeIf(level->level.getServer()==event.getServer());}
    }
    private static void writeScope(CompoundTag tag,EmergencyAccessBook.Scope scope) {
        tag.putUUID("controller",scope.controller());tag.putUUID("owner",scope.owner());tag.putLong("pos",scope.pos());
    }
    private static EmergencyAccessBook.Scope readScope(CompoundTag tag) {
        if(!tag.contains("pos",Tag.TAG_LONG))throw new IllegalArgumentException();
        return new EmergencyAccessBook.Scope(tag.getUUID("controller"),tag.getUUID("owner"),tag.getLong("pos"));
    }
    public static EmergencyAccess load(CompoundTag tag,HolderLookup.Provider registries) {
        EmergencyAccess data=new EmergencyAccess();var states=new ArrayList<Map.Entry<EmergencyAccessBook.Scope,EmergencyAccessBook.State>>();
        int seen=0;for(Tag raw:tag.getList("cinemas",Tag.TAG_COMPOUND)){
            if(++seen>EmergencyAccessBook.MAX_CINEMAS*2)break;
            try{CompoundTag entry=(CompoundTag)raw;
                if(!entry.contains("active",Tag.TAG_BYTE)||!entry.contains("revision",Tag.TAG_LONG)||!entry.contains("changedAt",Tag.TAG_LONG))continue;
                var status=new EmergencyAccessBook.State(entry.getBoolean("active"),entry.getLong("revision"),entry.getLong("changedAt"),entry.getBoolean("retired"));
                if(status.revision()<1)continue;states.add(Map.entry(readScope(entry),status));
            }catch(RuntimeException malformed){/* malformed state cannot unlock */}
        }
        var audit=new ArrayList<EmergencyAccessBook.Audit>();seen=0;
        for(Tag raw:tag.getList("audit",Tag.TAG_COMPOUND)){
            if(++seen>EmergencyAccessBook.MAX_AUDIT*2)break;
            try{CompoundTag entry=(CompoundTag)raw;var reason=entry.contains("reason")?EmergencyAccessBook.Reason.valueOf(entry.getString("reason")):EmergencyAccessBook.Reason.USER;
                audit.add(new EmergencyAccessBook.Audit(readScope(entry),entry.hasUUID("actor")?entry.getUUID("actor"):null,entry.getBoolean("active"),entry.getLong("revision"),entry.getLong("changedAt"),entry.getLong("gameTime"),reason));}
            catch(RuntimeException malformed){/* invalid log records grant no authority */}
        }
        data.book.restore(states,audit);return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        ListTag states=new ListTag();book.states().forEach((scope,status)->{
            CompoundTag entry=new CompoundTag();writeScope(entry,scope);entry.putBoolean("active",status.active());entry.putBoolean("retired",status.retired());entry.putLong("revision",status.revision());entry.putLong("changedAt",status.changedAt());states.add(entry);
        });tag.put("cinemas",states);
        ListTag audit=new ListTag();for(var action:book.audit()){
            CompoundTag entry=new CompoundTag();writeScope(entry,action.scope());if(action.actor()!=null)entry.putUUID("actor",action.actor());entry.putString("reason",action.reason().name());entry.putBoolean("active",action.active());entry.putLong("revision",action.revision());entry.putLong("changedAt",action.changedAt());entry.putLong("gameTime",action.gameTime());audit.add(entry);
        }tag.put("audit",audit);return tag;
    }
}
