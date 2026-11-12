package local.cinemamanager.seats;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.UUID;
import java.util.Optional;

/** Identity belongs to the installed chair, not its item, label or transient occupied mount. No ticker. */
public final class CinemaSeatTile extends BlockEntity {
    private UUID seatUid=UUID.randomUUID();
    private UUID boundController;
    private boolean invalidControllerClaim;
    public CinemaSeatTile(BlockPos pos,BlockState state) { super(SeatsModule.CHAIR_TILE.get(),pos,state); }
    public UUID seatUid() { return seatUid; }
    public Optional<UUID> boundController() { return Optional.ofNullable(boundController); }
    public boolean canBind(UUID manager) {
        return manager!=null && !invalidControllerClaim && (boundController==null || boundController.equals(manager));
    }
    /** The batch/card layer owns editor authorization and validates all claims before committing. */
    public void bindController(UUID manager) {
        if (!canBind(manager)) throw new IllegalStateException("Physical chair already claimed or malformed controller claim");
        boundController=manager;
        changed();
    }
    public void unbindController(UUID expected) {
        if (expected!=null && expected.equals(boundController) && !invalidControllerClaim) {
            boundController=null;
            changed();
        }
    }
    private void changed() {
        setChanged();
        if (level!=null) level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
    }
    @Override public boolean onlyOpCanSetNbt() { return true; }

    void initializeFresh() {
        releaseOccupant();
        seatUid=UUID.randomUUID();
        boundController=null;
        invalidControllerClaim=false;
        changed();
    }
    public boolean occupied() {
        return level instanceof ServerLevel server && !server.getEntitiesOfClass(CinemaSeatMount.class,
            new AABB(worldPosition).inflate(1), mount->mount.belongsTo(worldPosition) && !mount.isRemoved()).isEmpty();
    }
    public boolean sit(ServerPlayer player,BlockHitResult hit) {
        if (!(level instanceof ServerLevel server) || player.level()!=level || player.isSpectator() || !player.isAlive()
            || player.isPassenger() || player.isSleeping() || !CinemaSeatBlock.isValidSeat(level,worldPosition,seatUid)) return false;
        if (hit==null || !hit.getBlockPos().equals(worldPosition) || !player.canInteractWithBlock(worldPosition,0))
            return feedback(player,"chair_unreachable");
        // Recompute line of sight on the server; packet coordinates never authorize sitting through a wall.
        Vec3 eye=player.getEyePosition();
        Vec3 point=hit.getLocation();
        if (!new AABB(worldPosition).inflate(.001).contains(point)) return feedback(player,"chair_unreachable");
        Vec3 delta=point.subtract(eye);
        BlockHitResult actual=level.clip(new ClipContext(eye,point.add(delta.normalize().scale(.01)),
            ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        if (actual.getType()!=HitResult.Type.BLOCK || !actual.getBlockPos().equals(worldPosition)) return feedback(player,"chair_unreachable");
        if (occupied()) return feedback(player,"chair_occupied");
        CinemaSeatMount mount=new CinemaSeatMount(SeatsModule.CHAIR_MOUNT.get(),server);
        mount.initialize(this,player);
        if (!CinemaSeatSafety.canSit(server,player,mount)) return feedback(player,"chair_no_headroom");
        if (mount.safeExit(player).isEmpty()) return feedback(player,"chair_no_exit");
        if (!server.addFreshEntity(mount)) return false;
        if (!player.startRiding(mount,true)) { mount.discard(); return false; }
        CinemaSeatEvents.rememberSession(player,mount);
        mount.positionRider(player);
        player.setYRot(getBlockState().getValue(CinemaSeatBlock.FACING).toYRot());
        player.setYHeadRot(player.getYRot());
        player.setYBodyRot(player.getYRot());
        player.connection.teleport(player.getX(),player.getY(),player.getZ(),player.getYRot(),0);
        return true;
    }
    static boolean feedback(ServerPlayer player,String key) {
        player.displayClientMessage(Component.translatable("mycinema.seats."+key),true);
        return false;
    }
    public void releaseOccupant() {
        if (level instanceof ServerLevel server)
            for (CinemaSeatMount mount:server.getEntitiesOfClass(CinemaSeatMount.class,new AABB(worldPosition).inflate(1),
                entity->entity.belongsTo(worldPosition))) mount.releaseAndDiscard();
    }
    @Override public void setRemoved() { releaseOccupant(); super.setRemoved(); }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.saveAdditional(tag,registries);
        tag.putUUID("seat_uid",seatUid);
        tag.putLong("seat_home",worldPosition.asLong());
        if (boundController!=null) tag.putUUID("seat_controller",boundController);
        if (invalidControllerClaim) tag.putBoolean("seat_controller_invalid",true);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries) {
        super.loadAdditional(tag,registries);
        // Native chunk reload preserves identity; a clone moved to another cell is a different physical chair.
        boolean sameChair=tag.hasUUID("seat_uid") && (!tag.contains("seat_home")
            || tag.contains("seat_home",Tag.TAG_LONG) && tag.getLong("seat_home")==worldPosition.asLong());
        seatUid=sameChair ? tag.getUUID("seat_uid") : UUID.randomUUID();
        boundController=sameChair && tag.hasUUID("seat_controller") ? tag.getUUID("seat_controller") : null;
        invalidControllerClaim=sameChair && (tag.contains("seat_controller_invalid")
            && (!tag.contains("seat_controller_invalid",Tag.TAG_BYTE) || tag.getBoolean("seat_controller_invalid"))
            || tag.contains("seat_controller") && !tag.hasUUID("seat_controller"));
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
