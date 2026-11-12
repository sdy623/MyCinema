package local.cinemamanager.projection;

import java.util.UUID;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CinemaProjectorPartTile extends BlockEntity {
    private BlockPos anchor;
    private UUID identity;
    public CinemaProjectorPartTile(BlockPos pos, BlockState state) { super(CinemaManager.PROJECTOR_PART_TILE.get(), pos, state); }
    public BlockPos anchor() { return anchor; }
    public UUID identity() { return identity; }
    public void bind(BlockPos anchor, UUID identity) { this.anchor = anchor.immutable(); this.identity = identity; setChanged(); }
    public boolean belongsTo(BlockPos anchor, UUID identity) { return anchor != null && identity != null && anchor.equals(this.anchor) && identity.equals(this.identity); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) { super.saveAdditional(tag, provider); if (anchor != null && identity != null) { tag.putLong("projector_anchor", anchor.asLong()); tag.putUUID("projector_identity", identity); } }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) { super.loadAdditional(tag, provider); anchor = tag.contains("projector_anchor") ? BlockPos.of(tag.getLong("projector_anchor")) : null; identity = tag.hasUUID("projector_identity") ? tag.getUUID("projector_identity") : null; }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider) { return saveWithoutMetadata(provider); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
