package local.cinemamanager.seats.fractional;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;

/** Reimplemented dynamic 64-grid cell with independent host-floor preservation and per-UID ownership. */
public final class FractionalSeatTile extends BlockEntity {
    private FractionalSeatData data=FractionalSeatData.EMPTY;
    private BlockState host;
    private boolean valid=true;
    private VoxelShape cached;
    public FractionalSeatTile(BlockEntityType<?> type,BlockPos pos,BlockState state){
        super(type,pos,state);
        host=state.getValue(FractionalSeatBlock.HOST_SLAB)?FractionalSeatHost.slab(state.getValue(FractionalSeatBlock.HOST_MATERIAL)):Blocks.AIR.defaultBlockState();
    }
    public FractionalSeatData data(){return data;}
    public BlockState host(){return host;}
    public boolean hasValidData(){
        if(!valid||!FractionalSeatHost.allowedAt(host,worldPosition)||!FractionalSeatHost.matchesCellState(host,getBlockState()))return false;
        if(host.is(Blocks.GRAY_CARPET))try{
            return (!(level instanceof net.minecraft.server.level.ServerLevel server)||FractionalSeatHost.allowedInWorld(host,worldPosition,server))
                &&Original368CarpetContract.validOwners(worldPosition,data);
        }catch(RuntimeException invalidContract){return false;}
        return true;
    }
    public VoxelShape collisionShape(){
        if(isRemoved()||!hasValidData())return FractionalSeatBlock.hostShape(getBlockState());
        if(cached==null)cached=buildShape(data,host);return cached;
    }
    /** A seated rider ignores only their UID; shared neighbours and the physical floor remain solid. */
    public VoxelShape collisionShapeExcluding(UUID uid){
        if(isRemoved()||!hasValidData())return FractionalSeatBlock.hostShape(getBlockState());
        return uid==null?collisionShape():buildShape(data.without(uid),host);
    }
    public Optional<UUID> firstOwnerOnRay(Vec3 from,Vec3 to){
        if(isRemoved()||!hasValidData())return Optional.empty();
        Vec3 p=Vec3.atLowerCornerOf(worldPosition),a=from.subtract(p),b=to.subtract(p);
        return data.firstOwnerOnRay(a.x,a.y,a.z,b.x,b.y,b.z);
    }
    void replace(FractionalSeatData value,BlockState savedHost){
        if(level==null||level.isClientSide||isRemoved())throw new IllegalStateException("Requires live server cell");
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)||!FractionalSeatHost.allowedInWorld(savedHost,worldPosition,server)
            ||!value.validAt(worldPosition.getX(),worldPosition.getY(),worldPosition.getZ())||!FractionalSeatHost.matchesCellState(savedHost,getBlockState()))
            throw new IllegalArgumentException("Wrong host/home");
        FractionalSeatHost.validateData(value,savedHost);
        if(savedHost.is(Blocks.GRAY_CARPET)&&!Original368CarpetContract.validOwners(worldPosition,value))throw new IllegalArgumentException("Foreign carpet owner");
        data=value;host=savedHost;valid=true;cached=null;setChanged();
        level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_ALL);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registry){
        super.loadAdditional(tag,registry);cached=null;
        try{
            host=FractionalSeatHost.read(tag);data=FractionalSeatNbt.read(tag,worldPosition);
            FractionalSeatHost.validateData(data,host);
            if(host.is(Blocks.GRAY_CARPET)&&!Original368CarpetContract.validOwners(worldPosition,data))throw new IllegalArgumentException("Foreign saved carpet owner");
            if(!FractionalSeatHost.allowedAt(host,worldPosition)||!FractionalSeatHost.matchesCellState(host,getBlockState()))throw new IllegalArgumentException("Saved material mismatch");
            valid=true;
        }catch(IllegalArgumentException malformed){data=FractionalSeatData.EMPTY;valid=false;}
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registry){
        super.saveAdditional(tag,registry);FractionalSeatNbt.write(tag,worldPosition,data);FractionalSeatHost.write(tag,host);
        if(!hasValidData())tag.putInt("fractional_version",-1);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registry){return saveWithoutMetadata(registry);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
    @Override public void setRemoved(){super.setRemoved();cached=null;}
    @Override public void clearRemoved(){super.clearRemoved();cached=null;}
    public static VoxelShape buildShape(FractionalSeatData data,BlockState host){
        FractionalSeatHost.validateData(data,host);BitSet bits=data.voxelsCopy();
        bits.or(FractionalSeatHost.hostVoxels(host));
        if(bits.isEmpty())return Shapes.empty();
        BitSetDiscreteVoxelShape grid=new BitSetDiscreteVoxelShape(64,64,64);
        for(int p=bits.nextSetBit(0);p>=0;p=bits.nextSetBit(p+1))grid.fill(p/4096,p/64%64,p%64);
        return new GridShape(grid);
    }
    private static final class GridShape extends ArrayVoxelShape{
        GridShape(BitSetDiscreteVoxelShape grid){super(grid,coordinates(),coordinates(),coordinates());}
        private static double[] coordinates(){double[] a=new double[65];for(int i=0;i<=64;i++)a[i]=i/64.0;return a;}
    }
}
