package local.cinemamanager.theater;

import java.util.UUID;
import local.cinemamanager.policy.CinemaPermissions;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** MyCinema-owned identity and authority shared by cinema terminals and projectors. */
public abstract class CinemaDeviceTile extends DisplayTile {
    public static final int CONTROL_RANGE = 8;
    private UUID owner;

    protected CinemaDeviceTile(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public final void owner(UUID next) {
        if (java.util.Objects.equals(owner, next)) return;
        owner = next;
        onOwnerChanged();
        setChanged();
    }

    protected void onOwnerChanged() { local.cinemamanager.mirror.CinemaMirrors.rotate(this); }

    public final boolean ownedBy(UUID identity) {
        return identity != null && identity.equals(owner);
    }

    public final UUID theaterOwner() {
        return owner;
    }

    public boolean allows(Player actor) {
        return actor != null && actor.level() == getLevel() && !actor.isSpectator()
                && (owner == null || owner.equals(actor.getUUID()))
                && actor.level().mayInteract(actor, getBlockPos())
                && CinemaPermissions.canInteractBlock(actor, getBlockState().getBlock());
    }

    public boolean canControl(Player actor) {
        return allows(actor) && actor.distanceToSqr(getBlockPos().getCenter()) <= CONTROL_RANGE * CONTROL_RANGE;
    }

    public boolean metadataOwner(UUID identity, boolean allowUnclaimed) {
        return identity != null && (ownedBy(identity) || allowUnclaimed && owner == null);
    }

    public boolean canReportMetadata(Player actor) {
        return canControl(actor) && ownedBy(actor.getUUID());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (owner != null) tag.putUUID("theater_owner", owner);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        owner = tag.hasUUID("theater_owner") ? tag.getUUID("theater_owner") : null;
    }
}
