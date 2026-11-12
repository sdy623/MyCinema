package local.cinemamanager.seats;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invisible, non-pickable occupied-only vehicle. It is never saved, summoned, or a collision proxy. */
public final class CinemaSeatMount extends Entity {
    private BlockPos seatPos=BlockPos.ZERO;
    private UUID seatUid;
    private UUID expectedRider;
    private Direction facing=Direction.NORTH;
    private Vec3 chosenExit;
    private CinemaSeatBlock seatStyle;
    private boolean releasing;
    private int nextBlockedMessage;

    public CinemaSeatMount(EntityType<? extends CinemaSeatMount> type,Level level) {
        super(type,level);
        noPhysics=true;
        setNoGravity(true);
        setInvisible(true);
        setInvulnerable(true);
    }
    void initialize(CinemaSeatTile seat,ServerPlayer rider) {
        initializeRecovery(seat.getBlockPos(),seat.seatUid(),seat.getBlockState().getValue(CinemaSeatBlock.FACING),rider);
    }
    void initializeRecovery(BlockPos pos,UUID uid,Direction direction,ServerPlayer rider) {
        seatPos=pos.immutable();
        seatUid=uid;
        expectedRider=rider.getUUID();
        facing=direction;
        seatStyle=level().getBlockState(pos).getBlock() instanceof CinemaSeatBlock chair ? chair : SeatsModule.CHAIR.get();
        setPos(seatStyle.mountPosition(seatPos,facing));
        setYRot(facing.toYRot());
    }
    public BlockPos seatPos() { return seatPos; }
    public UUID seatUid() { return seatUid; }
    public Direction facing() { return facing; }
    public boolean belongsTo(BlockPos pos) { return seatPos.equals(pos); }
    boolean releasing() { return releasing; }
    boolean accepts(Entity passenger) {
        return !isRemoved() && passenger instanceof Player && (level().isClientSide ||
            passenger.getUUID().equals(expectedRider) && CinemaSeatBlock.isValidSeat(level(),seatPos,seatUid));
    }
    @Override protected boolean canAddPassenger(Entity passenger) { return getPassengers().isEmpty() && accepts(passenger); }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (chosenExit!=null && passenger instanceof ServerPlayer player) {
            player.setPos(chosenExit);
            CinemaSeatEvents.clearSession(player);
        }
        if (!level().isClientSide && getPassengers().isEmpty()) {
            // LivingEntity's removed-vehicle fallback uses max(playerY, vehicleY). Move the empty
            // vehicle to the already validated exit before discarding, so that fallback cannot
            // lift the player back to the cushion. No empty cleanup-tick vehicle is left behind.
            setPos(chosenExit!=null ? chosenExit : passenger.position());
            discard();
        }
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        // Even injected legacy vehicle NBT cannot recreate an occupied or idle chair vehicle.
        seatUid=null;
        expectedRider=null;
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean save(CompoundTag tag) { return false; }
    @Override public boolean saveAsPassenger(CompoundTag tag) { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean canCollideWith(Entity other) { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean hurt(DamageSource source,float amount) { return false; }
    @Override protected Vec3 getPassengerAttachmentPoint(Entity passenger,EntityDimensions dimensions,float scale) {
        return Vec3.ZERO;
    }
    @Override public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        if (level().isClientSide) return;
        if (getPassengers().isEmpty()) { discard(); return; }
        if (!CinemaSeatBlock.isValidSeat(level(),seatPos,seatUid)
            || level().getBlockState(seatPos).getBlock()!=seatStyle
            || level().getBlockState(seatPos).getValue(CinemaSeatBlock.FACING)!=facing
            || getPassengers().size()!=1 || !(getFirstPassenger() instanceof Player rider) || !rider.isAlive()
            || !rider.getUUID().equals(expectedRider)) { releaseAndDiscard(); return; }
        setPos(seatStyle.mountPosition(seatPos,facing));
    }
    Optional<Vec3> safeExit(LivingEntity passenger) {
        if (!(level() instanceof ServerLevel server)) return Optional.empty();
        return CinemaSeatSafety.findExit(server,passenger,this,!CinemaSeatBlock.isValidSeat(server,seatPos,seatUid));
    }
    boolean prepareDismount(LivingEntity passenger) {
        chosenExit=safeExit(passenger).orElse(null);
        if (chosenExit!=null) return true;
        if (passenger instanceof ServerPlayer player && tickCount>=nextBlockedMessage) {
            CinemaSeatTile.feedback(player,"chair_no_exit");
            nextBlockedMessage=tickCount+20;
        }
        return false;
    }
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // The event gate has checked this exact candidate synchronously on the server thread.
        if (chosenExit!=null) return chosenExit;
        return safeExit(passenger).orElseGet(passenger::position);
    }
    public void releaseAndDiscard() {
        if (releasing || isRemoved()) return;
        releasing=true;
        for (Entity passenger:List.copyOf(getPassengers())) {
            if (passenger instanceof LivingEntity living) prepareDismount(living);
            passenger.stopRiding();
        }
        if (!isRemoved()) discard();
    }
}
