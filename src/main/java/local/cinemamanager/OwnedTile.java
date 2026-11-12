package local.cinemamanager;

import local.cinemamanager.model.ManagerState;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public abstract class OwnedTile extends BlockEntity {
    public UUID owner, identity = UUID.randomUUID();
    protected OwnedTile(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public boolean permits(Player player, double range) {
        if (level == null || player.level() != level) return false;
        return ManagerState.identityAllowed(owner, player.getUUID(), player.hasPermissions(2), player.isSpectator(),
                level.mayInteract(player, worldPosition), player.distanceToSqr(Vec3.atCenterOf(worldPosition)) * 64.0 / (range * range));
    }
    public void dirty() { setChanged(); if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.saveAdditional(tag, registries); if (owner != null) tag.putUUID("owner", owner); tag.putUUID("identity", identity); }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.loadAdditional(tag, registries); owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null; if (tag.hasUUID("identity")) identity = tag.getUUID("identity"); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
