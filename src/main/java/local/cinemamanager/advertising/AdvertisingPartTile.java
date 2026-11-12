package local.cinemamanager.advertising;
import local.cinemamanager.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;
public final class AdvertisingPartTile extends BlockEntity {
    public BlockPos anchor; public UUID rootIdentity;
    public AdvertisingGeometry geometry=AdvertisingGeometry.DEFAULT;
    public AdvertisingShape shape=AdvertisingShape.RECTANGLE;
    public AdvertisingPartTile(BlockPos p,BlockState s){super(CinemaManager.AD_PART_TILE.get(),p,s);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
    public InfoTile root(){
        if(level==null||anchor==null||rootIdentity==null||anchor.equals(worldPosition)||!level.hasChunkAt(anchor)
                ||!(level.getBlockEntity(anchor) instanceof InfoTile t)||t.isRemoved()
                ||!(t.getBlockState().getBlock() instanceof AdvertisingBlock)||!matches(t))return null;
        var state=t.getBlockState();Direction facing=state.getValue(AdvertisingBlock.FACING);
        return getBlockState().getValue(AdvertisingBlock.FACING)==facing
                &&getBlockState().getValue(AdvertisingBlock.FLOOR)==state.getValue(AdvertisingBlock.FLOOR)
                &&geometry.equals(t.advertising)&&shape.equals(t.advertisingShape)&&geometry.contains(anchor,facing,worldPosition)?t:null;
    }
    public boolean matches(InfoTile t){return t.getBlockPos().equals(anchor)&&t.identity.equals(rootIdentity);}
    public void link(InfoTile t){anchor=t.getBlockPos().immutable();rootIdentity=t.identity;geometry=t.advertising;shape=t.advertisingShape;setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    /** Deferred loaded-only reconciliation is safe whichever side of a chunk boundary loads first. */
    public void reconcile(){
        if(!(level instanceof ServerLevel server)||isRemoved()||AdvertisingLifecycle.mutating(level,anchor))return;
        if(anchor!=null&&!level.hasChunkAt(anchor))return;
        InfoTile root=root();
        if(root==null){if(level.getBlockEntity(worldPosition)==this)server.setBlock(worldPosition,Blocks.AIR.defaultBlockState(),3);}
        // The floor root ticker proves the complete downward graph once; do not repeat it per proxy.
        else if(!getBlockState().getValue(AdvertisingBlock.FLOOR)
                &&!AdvertisingLifecycle.supportedCell(level,root.getBlockPos(),root.getBlockState(),root.advertising,worldPosition,false))server.destroyBlock(root.getBlockPos(),true);
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);if(anchor!=null&&rootIdentity!=null){t.putLong("anchor",anchor.asLong());t.putUUID("root_identity",rootIdentity);t.put("geometry",geometry.save());t.put("shape",shape.save());}}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);anchor=t.contains("anchor")?BlockPos.of(t.getLong("anchor")):null;rootIdentity=t.hasUUID("root_identity")?t.getUUID("root_identity"):null;geometry=AdvertisingGeometry.read(t.getCompound("geometry"));shape=AdvertisingShape.read(t.getCompound("shape"));}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){return saveWithoutMetadata(r);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
