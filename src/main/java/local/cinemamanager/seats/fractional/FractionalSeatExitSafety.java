package local.cinemamanager.seats.fractional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** One authoritative, read-only predicate evaluator for entry, exit and operator diagnostics. */
final class FractionalSeatExitSafety {
    enum Reason { OK, BUILD_HEIGHT, UNLOADED, WORLD_BORDER, ENTITY_COLLISION, BLOCK_COLLISION, HAZARD, INVALID_CELL, NO_SUPPORT, HEIGHT_DIFFERENCE, POSE_UNCALIBRATED, CONTACT_DEPTH, SUPPORT_UNVERIFIED, SUPPORT_GAP, SUPPORT_CHANGED }
    record Check(String stage, Reason reason, AABB testedBox, BlockPos block, String state,
                 String retainedHost, boolean retainedHostCollision, List<UUID> blockingOwners,
                 UUID ignoredOwner, String detail) {
        Check { blockingOwners = List.copyOf(blockingOwners); if (block != null) block = block.immutable(); }
        boolean allowed() { return reason == Reason.OK; }
        String code() { return stage + "_" + reason.name(); }
        static Check ok(String stage, AABB box) { return new Check(stage, Reason.OK, box, null, "", "", false, List.of(), null, ""); }
    }
    record EntryAssessment(Vec3 passengerPoint, Vec3 attachmentPoint, Vec3 predictedFeet, AABB predictedBody,
                           Vec3 floorAnchor, AABB floorBody, Vec3 exit, List<Check> checks) {
        EntryAssessment { checks = List.copyOf(checks); }
        boolean allowed() { return checks.stream().allMatch(Check::allowed); }
        Check firstFailure() { return checks.stream().filter(c -> !c.allowed()).findFirst().orElse(checks.get(0)); }
    }
    static EntryAssessment assessEntry(ServerLevel level, ServerPlayer rider, ArmorStand marker, FractionalSeatLayout.Seat seat) {
        return evaluateEntry(level, rider, marker, seat, false);
    }
    static EntryAssessment diagnoseEntry(ServerLevel level, ServerPlayer rider, ArmorStand marker, FractionalSeatLayout.Seat seat) {
        return evaluateEntry(level, rider, marker, seat, true);
    }
    private static EntryAssessment evaluateEntry(ServerLevel level, ServerPlayer rider, ArmorStand marker,
                                                   FractionalSeatLayout.Seat seat, boolean allStages) {
        Vec3 passenger = marker.getPassengerRidingPosition(rider), attachment = rider.getVehicleAttachmentPoint(marker);
        Vec3 feet = passenger.subtract(attachment);
        AABB body = rider.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        Vec3 anchor = new Vec3(seat.anchor().x(), seat.anchor().y(), seat.anchor().z());
        AABB floorBody = rider.getDimensions(Pose.STANDING).makeBoundingBox(anchor);
        List<Check> checks = new ArrayList<>();
        Check predicted = calibratedClear(level, rider, marker, seat, body, feet, "PREDICTED_BODY"); checks.add(predicted);
        // Preserve normal entry's original short-circuit order. Only an explicit read-only diagnose
        // command requests all stages; rejected clicks do not rebuild unrelated 64-grid shapes.
        if (predicted.allowed() || allStages) {
            Check floor = clear(level, rider, floorBody, seat.uid(), "FLOOR_BODY"); checks.add(floor);
            if (predicted.allowed() && floor.allowed() || allStages) checks.add(assessExit(level, rider, seat));
        }
        return new EntryAssessment(passenger, attachment, feet, body, anchor, floorBody, seat.exit(), checks);
    }
    static boolean canSit(ServerLevel level, ServerPlayer rider, ArmorStand marker, FractionalSeatLayout.Seat seat) {
        return assessEntry(level, rider, marker, seat).allowed();
    }
    static Check assessMountedBody(ServerLevel level, ServerPlayer rider, FractionalSeatLayout.Seat seat) {
        if (rider.getVehicle() instanceof ArmorStand marker)
            return calibratedClear(level, rider, marker, seat, rider.getBoundingBox(), rider.position(), "MOUNTED_BODY");
        // Existing unmounted/model test paths get no contact exception at all.
        return clear(level, rider, rider.getBoundingBox(), seat.uid(), "MOUNTED_BODY");
    }
    private static Check calibratedClear(ServerLevel level, ServerPlayer rider, ArmorStand marker, FractionalSeatLayout.Seat seat,
                                         AABB body, Vec3 feet, String stage) {
        if (!FractionalSeatSupportContact.appliesTo(seat)) return clear(level, rider, body, seat.uid(), stage);
        var contact = FractionalSeatSupportContact.evaluate(level, rider, marker, seat, body, feet, stage);
        return contact.allowed() ? clear(level, rider, body, seat.uid(), stage, contact) : contact.check();
    }
    static boolean mountedBodyClear(ServerLevel level, ServerPlayer rider, FractionalSeatLayout.Seat seat) {
        return assessMountedBody(level, rider, seat).allowed();
    }
    static Optional<Vec3> exit(ServerLevel level, ServerPlayer rider, FractionalSeatLayout.Seat seat) {
        return assessExit(level, rider, seat).allowed() ? Optional.of(seat.exit()) : Optional.empty();
    }
    static Check assessExit(ServerLevel level, ServerPlayer rider, FractionalSeatLayout.Seat seat) {
        Vec3 target = seat.exit();
        Check standing = assessStanding(level, rider, target);
        if (!standing.allowed()) return standing;
        Vec3 start = new Vec3(seat.anchor().x(), seat.anchor().y(), seat.anchor().z());
        if (Math.abs(target.y - start.y) > 1.001)
            return failure("EXIT_CORRIDOR", Reason.HEIGHT_DIFFERENCE, null, null, "", seat.uid(), "exit height differs from floor anchor by more than 1.001 m");
        int steps = Math.max(1, (int)Math.ceil(start.distanceTo(target) * 8));
        AABB previous = rider.getDimensions(Pose.STANDING).makeBoundingBox(start);
        for (int i = 0; i <= steps; i++) {
            AABB next = rider.getDimensions(Pose.STANDING).makeBoundingBox(start.lerp(target, (double)i / steps));
            Check step = clear(level, rider, previous.minmax(next), seat.uid(), "EXIT_CORRIDOR");
            if (!step.allowed()) return new Check(step.stage(), step.reason(), step.testedBox(), step.block(), step.state(),
                step.retainedHost(), step.retainedHostCollision(), step.blockingOwners(), step.ignoredOwner(),
                "sample " + i + "/" + steps + "; " + step.detail());
            previous = next;
        }
        return Check.ok("EXIT", rider.getDimensions(Pose.STANDING).makeBoundingBox(target));
    }
    static boolean safeStanding(ServerLevel level, ServerPlayer rider, Vec3 feet) {
        return assessStanding(level, rider, feet).allowed();
    }
    static Check assessStanding(ServerLevel level, ServerPlayer rider, Vec3 feet) {
        AABB body = rider.getDimensions(Pose.STANDING).makeBoundingBox(feet);
        Check clear = clear(level, rider, body, null, "EXIT_BODY");
        if (!clear.allowed()) return clear;
        // Same four inset support checks as the entry/exit gate. Never load a chunk to diagnose it.
        for (double x : new double[]{body.minX + .03, body.maxX - .03})
            for (double z : new double[]{body.minZ + .03, body.maxZ - .03}) {
                AABB sole = new AABB(x - .01, feet.y - .025, z - .01, x + .01, feet.y + .0000001, z + .01);
                if (!level.getBlockCollisions(rider, sole).iterator().hasNext()) {
                    BlockPos below = BlockPos.containing(x, feet.y - .025, z);
                    return failure("EXIT_SUPPORT", Reason.NO_SUPPORT, sole, below, level.getBlockState(below).toString(), null,
                        "one of four inset corners has no actual block collision support");
                }
                for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sole.minX, sole.minY, sole.minZ),
                        BlockPos.containing(sole.maxX, sole.maxY, sole.maxZ))) {
                    BlockState state = level.getBlockState(pos);
                    if (dangerous(state)) return blockFailure(level, rider, "EXIT_SUPPORT", Reason.HAZARD, sole, pos, state, null,
                        "dangerous or fluid support in downward sole region");
                }
            }
        return Check.ok("EXIT_SUPPORT", body);
    }
    private static Check clear(ServerLevel level, ServerPlayer rider, AABB box, UUID ignoredOwner, String stage) {
        return clear(level, rider, box, ignoredOwner, stage, null);
    }
    private static Check clear(ServerLevel level, ServerPlayer rider, AABB box, UUID ignoredOwner, String stage,
                               FractionalSeatSupportContact.Plan contact) {
        AABB region = box.inflate(1);
        BlockPos min = BlockPos.containing(region.minX, region.minY, region.minZ), max = BlockPos.containing(region.maxX, region.maxY, region.maxZ);
        if (min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight())
            return failure(stage, Reason.BUILD_HEIGHT, box, min, "", ignoredOwner, "inflated query region outside build height; max=" + max.toShortString());
        if (!level.hasChunksAt(min, max)) {
            BlockPos unloaded = min;
            for (BlockPos p : BlockPos.betweenClosed(min, max)) if (!level.hasChunkAt(p)) { unloaded = p.immutable(); break; }
            return failure(stage, Reason.UNLOADED, box, unloaded, "UNLOADED", ignoredOwner, "query region is not loaded; no chunk was requested");
        }
        if (!level.getWorldBorder().isWithinBounds(box)) return failure(stage, Reason.WORLD_BORDER, box, min, "", ignoredOwner, "body crosses world border");
        var entities = level.getEntityCollisions(rider, box.deflate(1e-7));
        if (!entities.isEmpty()) return failure(stage, Reason.ENTITY_COLLISION, box, null, "", ignoredOwner,
            "entity collision shapes=" + entities.size() + "; first=" + entities.get(0).bounds());
        VoxelShape body = Shapes.create(box.deflate(1e-7));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.is(FractionalSeatModule.CELL.get()) && (!(level.getBlockEntity(pos) instanceof FractionalSeatTile cell)
                || cell.isRemoved() || !cell.hasValidData() || cell.data().isEmpty()))
                return failure(stage, Reason.INVALID_CELL, box, pos, state.toString(), ignoredOwner, "fractional transport has no live nonempty valid tile; peer geometry cannot be inferred");
            if (new AABB(pos).intersects(box.deflate(1e-7)) && dangerous(state))
                return blockFailure(level, rider, stage, Reason.HAZARD, box, pos, state, ignoredOwner, "dangerous or fluid block intersects body");
            if (contact != null) {
                boolean approvedHost = contact.exempts(level, pos);
                if (contact.hosts().containsKey(pos) && !approvedHost)
                    return failure(stage, Reason.SUPPORT_CHANGED, box, pos, state.toString(), ignoredOwner, "verified support changed during body validation");
                if (level.getBlockEntity(pos) instanceof FractionalSeatTile tile) {
                    if (!tile.hasValidData()) return failure(stage, Reason.INVALID_CELL, box, pos, state.toString(), ignoredOwner, "invalid cell in calibrated body region");
                    // Owner masks are independently tested against the FULL physical body. The contact
                    // allowance is never subtracted from a combined shared-cell union.
                    for (var owner : tile.data().owners()) if (!owner.uid().equals(ignoredOwner)
                        && FractionalSeatSupportContact.ownerBlocks(owner, box.deflate(1e-7), pos))
                        return blockFailure(level, rider, stage, Reason.BLOCK_COLLISION, box, pos, state, ignoredOwner, "other-owner mask intersects full seated body, including contact slice");
                    VoxelShape host = tile.host().getCollisionShape(level, pos, CollisionContext.of(rider)).move(pos.getX(), pos.getY(), pos.getZ());
                    if (approvedHost ? FractionalSeatSupportContact.hostBlocks(body, host, contact.contact())
                        : Shapes.joinIsNotEmpty(body, host, BooleanOp.AND))
                        return blockFailure(level, rider, stage, Reason.BLOCK_COLLISION, box, pos, state, ignoredOwner, "retained host outside the verified contact sliver blocks body");
                } else {
                    VoxelShape block = state.getCollisionShape(level, pos, CollisionContext.of(rider)).move(pos.getX(), pos.getY(), pos.getZ());
                    if (approvedHost ? FractionalSeatSupportContact.hostBlocks(body, block, contact.contact())
                        : Shapes.joinIsNotEmpty(body, block, BooleanOp.AND))
                        return blockFailure(level, rider, stage, Reason.BLOCK_COLLISION, box, pos, state, ignoredOwner, "foreign block or verified host outside contact sliver blocks body");
                }
            } else {
                VoxelShape shape;
                if (ignoredOwner != null && level.getBlockEntity(pos) instanceof FractionalSeatTile tile) {
                    if (!tile.hasValidData()) return failure(stage, Reason.INVALID_CELL, box, pos, state.toString(), ignoredOwner, "fractional tile payload or retained host is invalid");
                    shape = tile.collisionShapeExcluding(ignoredOwner);
                } else shape = state.getCollisionShape(level, pos, CollisionContext.of(rider));
                if (Shapes.joinIsNotEmpty(body, shape.move(pos.getX(), pos.getY(), pos.getZ()), BooleanOp.AND))
                    return blockFailure(level, rider, stage, Reason.BLOCK_COLLISION, box, pos, state, ignoredOwner, "actual collision intersects full body; contact epsilon=1e-7");
            }
        }
        if (contact != null) return contact.stillValid(level) ? contact.check()
            : failure(stage, Reason.SUPPORT_CHANGED, box, null, "", ignoredOwner, "support changed before validation completed");
        return Check.ok(stage, box);
    }
    private static Check blockFailure(ServerLevel level, ServerPlayer rider, String stage, Reason reason, AABB box,
                                      BlockPos pos, BlockState state, UUID ignoredOwner, String detail) {
        String host = ""; boolean hostCollision = false; List<UUID> owners = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof FractionalSeatTile tile && tile.hasValidData()) {
            try {
                host = tile.host().toString(); VoxelShape body = Shapes.create(box.deflate(1e-7));
                hostCollision = intersects(body, tile.host().getCollisionShape(level, pos, CollisionContext.of(rider)), pos);
                for (var owner : tile.data().owners()) if (!owner.uid().equals(ignoredOwner)
                    && FractionalSeatSupportContact.ownerBlocks(owner, box.deflate(1e-7), pos)) owners.add(owner.uid());
            } catch (RuntimeException attributionFailure) {
                // The authoritative predicate already refused the body. A diagnostic attribution
                // failure must not turn that refusal into an exception or an accidental approval.
                detail += "; attribution unavailable: " + attributionFailure.getClass().getSimpleName();
            }
        }
        return new Check(stage, reason, box, pos, state.toString(), host, hostCollision, owners, ignoredOwner, detail);
    }
    private static boolean intersects(VoxelShape body, VoxelShape local, BlockPos pos) {
        return Shapes.joinIsNotEmpty(body, local.move(pos.getX(), pos.getY(), pos.getZ()), BooleanOp.AND);
    }
    private static Check failure(String stage, Reason reason, AABB box, BlockPos pos, String state, UUID ignoredOwner, String detail) {
        return new Check(stage, reason, box, pos, state, "", false, List.of(), ignoredOwner, detail);
    }
    private static boolean dangerous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
    }
    private FractionalSeatExitSafety() {}
}
