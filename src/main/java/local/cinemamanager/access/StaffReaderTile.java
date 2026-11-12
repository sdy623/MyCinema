package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Ownership/identities are separate from the public physical card. No fake-player authority. */
public final class StaffReaderTile extends OwnedTile {
    public BlockPos controllerPos, doorPos;
    public UUID controllerId, controllerOwner, doorId, revision=UUID.randomUUID(), editRevision=UUID.randomUUID();
    public StaffRole required=StaffRole.ENTRY;
    private final Map<UUID,Long> swipes=new HashMap<>();
    private static final Set<String> OWNERSHIP_KEYS=Set.of("owner","identity","accessRevision","accessEditRevision","requiredRole","accessControllerPos","accessController","accessControllerOwner","accessDoorPos","accessDoor");
    private CompoundTag preservedInvalidOwnership;
    public boolean ownershipFault(){return preservedInvalidOwnership!=null;}
    static boolean rawOwnershipFault(CompoundTag tag){
        if(!tag.hasUUID("identity")||!tag.hasUUID("accessRevision")||!tag.hasUUID("accessEditRevision")||(tag.contains("owner")&&!tag.hasUUID("owner"))||!tag.contains("requiredRole",8))return true;
        try{StaffRole.valueOf(tag.getString("requiredRole"));}catch(RuntimeException bad){return true;}
        boolean controller=tag.contains("accessControllerPos")||tag.contains("accessController")||tag.contains("accessControllerOwner");
        boolean door=tag.contains("accessDoorPos")||tag.contains("accessDoor");
        if(controller&&(!tag.contains("accessControllerPos",4)||!tag.hasUUID("accessController")||!tag.hasUUID("accessControllerOwner")||!tag.hasUUID("owner")))return true;
        return door&&(!controller||!tag.contains("accessDoorPos",4)||!tag.hasUUID("accessDoor"));
    }

    public StaffReaderTile(BlockPos pos,BlockState state){super(AccessModule.READER_TILE.get(),pos,state);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
    @Override public void onLoad(){super.onLoad();if(level instanceof ServerLevel server&&doorPos!=null&&server.hasChunkAt(doorPos)){
        if(server.getBlockState(doorPos).getBlock() instanceof local.cinemamanager.doors.CinemaDoorBlock door)server.scheduleTick(doorPos,door,1);
        else if(FixedIronDoorAccess.candidate(server,doorPos))FixedStaffDoorLifecycle.arm(server);
    }}
    public void initialize(UUID placedBy){preservedInvalidOwnership=null;owner=placedBy;identity=UUID.randomUUID();controllerPos=null;controllerId=null;controllerOwner=null;doorPos=null;doorId=null;required=StaffRole.ENTRY;revision=UUID.randomUUID();dirty();}
    public static boolean real(ServerPlayer actor){return actor!=null&&!(actor instanceof FakePlayer)&&!actor.isSpectator();}
    public ManagerTile controller(){
        if(ownershipFault()||!(level instanceof ServerLevel)||isRemoved()||!level.hasChunkAt(worldPosition)||level.getBlockEntity(worldPosition)!=this
                ||controllerPos==null||controllerId==null||controllerOwner==null||!level.hasChunkAt(controllerPos)
                ||!(level.getBlockEntity(controllerPos) instanceof ManagerTile manager)||manager.isRemoved()
                ||!controllerId.equals(manager.identity)||!controllerOwner.equals(manager.owner))return null;
        return manager;
    }
    public void requireEditor(ServerPlayer actor){
        ManagerTile manager=controller();
        if(!real(actor)||!permits(actor,8)||manager==null||!manager.permits(actor,Double.POSITIVE_INFINITY))throw new IllegalArgumentException("mycinema.access.permission");
    }
    public void requireRevision(String expected){if(!editRevision.toString().equals(expected))throw new IllegalArgumentException("mycinema.access.stale");}
    public void edited(){editRevision=UUID.randomUUID();dirty();}
    public void bindController(ServerPlayer actor,ManagerTile manager){
        if(ownershipFault()||!real(actor)||!permits(actor,8)||manager.getLevel()!=level||manager.isRemoved()||!level.hasChunkAt(manager.getBlockPos())
                ||level.getBlockEntity(manager.getBlockPos())!=manager||manager.owner==null||!manager.permits(actor,Double.POSITIVE_INFINITY)
                ||manager.getBlockPos().distSqr(worldPosition)>128*128)throw new IllegalArgumentException("mycinema.access.permission");
        if(Objects.equals(controllerId,manager.identity)&&Objects.equals(controllerOwner,manager.owner)&&Objects.equals(controllerPos,manager.getBlockPos()))return;
        invalidatePairing();controllerPos=manager.getBlockPos().immutable();controllerId=manager.identity;controllerOwner=manager.owner;doorPos=null;doorId=null;dirty();
    }
    /** Pairing/configuration changes do not revoke cinema-wide employee credentials. */
    public void invalidatePairing(){revision=UUID.randomUUID();edited();}
    public void changeRole(ServerPlayer actor,String expected,StaffRole role){requireEditor(actor);requireRevision(expected);if(role==null)throw new IllegalArgumentException("mycinema.access.invalid");if(required!=role){required=role;invalidatePairing();}}
    public StaffGrantBook.Scope currentScope(){
        ManagerTile manager=controller();if(manager==null||owner==null||doorPos==null||doorId==null||!(level instanceof ServerLevel server))return null;
        var door=StaffAccessRegistry.get(server).door(doorPos.asLong());
        if(door==null||!door.token.equals(doorId)||!door.reader.equals(identity)||door.readerPos!=worldPosition.asLong()||!AccessDoors.current(server,door))return null;
        return new StaffGrantBook.Scope(manager.identity,manager.owner,manager.getBlockPos().asLong(),identity,owner,worldPosition.asLong(),door.token,door.base(),revision);
    }
    public boolean acceptSwipe(ServerPlayer actor){long now=level.getGameTime(),last=swipes.getOrDefault(actor.getUUID(),Long.MIN_VALUE);if(last!=Long.MIN_VALUE&&now>=last&&now-last<10)return false;swipes.put(actor.getUUID(),now);if(swipes.size()>128)swipes.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>200);return true;}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);
        if(preservedInvalidOwnership!=null){for(String key:OWNERSHIP_KEYS){tag.remove(key);if(preservedInvalidOwnership.contains(key))tag.put(key,preservedInvalidOwnership.get(key).copy());}return;}
        tag.putUUID("accessRevision",revision);tag.putUUID("accessEditRevision",editRevision);tag.putString("requiredRole",required.name());
        if(controllerPos!=null&&controllerId!=null&&controllerOwner!=null){tag.putLong("accessControllerPos",controllerPos.asLong());tag.putUUID("accessController",controllerId);tag.putUUID("accessControllerOwner",controllerOwner);}
        if(doorPos!=null&&doorId!=null){tag.putLong("accessDoorPos",doorPos.asLong());tag.putUUID("accessDoor",doorId);}
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        preservedInvalidOwnership=null;
        if(rawOwnershipFault(tag)){preservedInvalidOwnership=new CompoundTag();for(String key:OWNERSHIP_KEYS)if(tag.contains(key))preservedInvalidOwnership.put(key,tag.get(key).copy());}
        super.loadAdditional(tag,registries);controllerPos=null;controllerId=null;controllerOwner=null;doorPos=null;doorId=null;revision=tag.hasUUID("accessRevision")?tag.getUUID("accessRevision"):UUID.randomUUID();editRevision=tag.hasUUID("accessEditRevision")?tag.getUUID("accessEditRevision"):UUID.randomUUID();
        try{required=StaffRole.valueOf(tag.getString("requiredRole"));}catch(RuntimeException invalid){required=StaffRole.SERVICE;}
        if(tag.hasUUID("accessController")&&tag.hasUUID("accessControllerOwner")&&tag.contains("accessControllerPos",net.minecraft.nbt.Tag.TAG_LONG)){controllerPos=BlockPos.of(tag.getLong("accessControllerPos"));controllerId=tag.getUUID("accessController");controllerOwner=tag.getUUID("accessControllerOwner");}
        if(tag.hasUUID("accessDoor")&&tag.contains("accessDoorPos",net.minecraft.nbt.Tag.TAG_LONG)){doorPos=BlockPos.of(tag.getLong("accessDoorPos"));doorId=tag.getUUID("accessDoor");}
    }
}
