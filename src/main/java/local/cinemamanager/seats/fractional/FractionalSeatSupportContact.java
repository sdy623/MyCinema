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

/** An owner-scoped host-contact proof, never a change to world or other-owner collision geometry. */
final class FractionalSeatSupportContact {
    static final double PHYSICAL_EPS = 1e-7;
    private static final VoxelShape BOTTOM_SLAB = Shapes.box(0, 0, 0, 1, .5, 1);
    record Host(BlockState state, BlockState retained, FractionalSeatTile tile, FractionalSeatData data,
                FractionalSeatData.Contribution own, VoxelShape worldShape) {
        boolean matches(ServerLevel level, BlockPos pos) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).equals(state)) return false;
            if (tile == null) return level.getBlockEntity(pos) == null;
            return level.getBlockEntity(pos) == tile && tile.hasValidData() && tile.host().equals(retained)
                && tile.data().equals(data) && tile.data().owner(own.uid()).filter(own::equals).isPresent();
        }
    }
    record Plan(FractionalSeatExitSafety.Check check, Map<BlockPos, Host> hosts, VoxelShape contact) {
        Plan { hosts = Map.copyOf(hosts); }
        boolean allowed() { return check.allowed(); }
        boolean stillValid(ServerLevel level) { return hosts.entrySet().stream().allMatch(e -> e.getValue().matches(level, e.getKey())); }
        boolean exempts(ServerLevel level, BlockPos pos) { Host host = hosts.get(pos); return host != null && host.matches(level, pos); }
    }
    static boolean appliesTo(FractionalSeatLayout.Seat seat) {
        return !seat.footprint().cells().isEmpty() && seat.footprint().cells().values().stream().allMatch(c ->
            c.uid().equals(seat.uid()) && c.anchor().equals(seat.anchor()) && c.geometry() == FractionalSeatGeometryProfile.HIGHBACK_150);
    }
    static Plan evaluate(ServerLevel level, ServerPlayer rider, ArmorStand marker, FractionalSeatLayout.Seat seat,
                         AABB rawBody, Vec3 feet, String stage) {
        FractionalSeatFloorPalette.Profile palette;
        FractionalSeatLayout layout;
        try { palette = FractionalSeatFloorPalette.forLevel(level); layout = FractionalSeatLayout.get(level); }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            return refused(stage, FractionalSeatExitSafety.Reason.SUPPORT_UNVERIFIED, rawBody, null, "", seat.uid(), invalid.getMessage());
        }
        boolean smoothing = layout.isSmoothed();
        boolean selected = appliesTo(seat);
        Vec3 floor = new Vec3(seat.anchor().x(), seat.anchor().y(), seat.anchor().z());
        Vec3 passenger = marker.getPassengerRidingPosition(rider), attachment = rider.getVehicleAttachmentPoint(marker);
        var dimensions = rider.getDimensions(Pose.STANDING);
        var sample = new FractionalSeatContactPolicy.Sample(selected,
            marker.isMarker() && !marker.isRemoved() && marker.isNoGravity() && marker.isInvisible(), rider.getPose() == Pose.STANDING,
            rider.getScale(), point(floor), point(marker.position()), point(passenger), point(attachment), point(feet),
            dimensions.width(), dimensions.height(), new FractionalSeatContactPolicy.Bounds(rawBody.minX, rawBody.minY, rawBody.minZ, rawBody.maxX, rawBody.maxY, rawBody.maxZ));
        var calibration = FractionalSeatContactPolicy.validate(sample);
        if (calibration != FractionalSeatContactPolicy.Failure.NONE)
            return refused(stage, calibration == FractionalSeatContactPolicy.Failure.CONTACT_DEPTH ? FractionalSeatExitSafety.Reason.CONTACT_DEPTH
                : FractionalSeatExitSafety.Reason.POSE_UNCALIBRATED, rawBody, null, "", seat.uid(), "calibration=" + calibration);
        boolean full = floor.y == Math.rint(floor.y), half = floor.y - .5 == Math.rint(floor.y - .5);
        if (!full && !half) return refused(stage, FractionalSeatExitSafety.Reason.SUPPORT_UNVERIFIED, rawBody, null, "", seat.uid(), "floor is not an exact authored integer/half-block surface");
        // Calibration/depth use the raw body. Coverage uses exactly the existing collision-contact
        // epsilon, avoiding false adjacent-cell coverage from vanilla float dimensions such as .600000024.
        AABB physical = rawBody.deflate(PHYSICAL_EPS);
        AABB contactBox = new AABB(physical.minX, physical.minY, physical.minZ, physical.maxX, floor.y, physical.maxZ);
        VoxelShape contact = Shapes.create(contactBox), union = Shapes.empty();
        int supportY = (int)(full ? floor.y - 1 : floor.y - .5);
        BlockPos min = BlockPos.containing(physical.minX, supportY, physical.minZ);
        BlockPos max = BlockPos.containing(Math.nextDown(physical.maxX), supportY, Math.nextDown(physical.maxZ));
        Map<BlockPos, Host> hosts = new LinkedHashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = cursor.immutable();
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos))
                return refused(stage, FractionalSeatExitSafety.Reason.UNLOADED, rawBody, pos, "UNLOADED", seat.uid(), "support query does not load chunks");
            BlockState state = level.getBlockState(pos), host = state;
            FractionalSeatTile tile = null; FractionalSeatData data = null; FractionalSeatData.Contribution own = null;
            if (state.is(FractionalSeatModule.CELL.get())) {
                if (!(level.getBlockEntity(pos) instanceof FractionalSeatTile candidate) || !candidate.hasValidData())
                    return refused(stage, FractionalSeatExitSafety.Reason.INVALID_CELL, rawBody, pos, state.toString(), seat.uid(), "support tile is missing or invalid");
                tile = candidate; host = tile.host(); data = tile.data(); own = seat.footprint().cells().get(pos);
                if (own == null || !data.owner(seat.uid()).filter(own::equals).isPresent())
                    return refused(stage, FractionalSeatExitSafety.Reason.SUPPORT_UNVERIFIED, rawBody, pos, state.toString(), seat.uid(), "retained host lacks this exact selected UID contribution");
            } else if (level.getBlockEntity(pos) != null)
                return refused(stage, FractionalSeatExitSafety.Reason.SUPPORT_UNVERIFIED, rawBody, pos, state.toString(), seat.uid(), "support block entity is not permitted");
            BlockState required = smoothing ? layout.supportAt(pos).orElse(null) : (full ? palette.fullSupport() : palette.slabSupport());
            boolean fullWool = required != null && (required.equals(Blocks.GRAY_WOOL.defaultBlockState()) || layout.isOriginal368() && required.equals(Blocks.BLACK_WOOL.defaultBlockState()));
            boolean fixture = smoothing && required != null && (layout.isOriginal368() ? Original368SupportFixtures.accepts(pos, required, host) : SmoothedSupportFixtures.accepts(!layout.usesHiddenPorts(), pos, required, host));
            if (required == null || smoothing && !fixture && !(fullWool || FractionalSeatHost.hasSolidHost(required))
                || !fixture && !host.equals(required) || !host.getFluidState().isEmpty()
                || !smoothing && (full && tile != null || half && tile == null))
                return refused(stage, host.isAir() ? FractionalSeatExitSafety.Reason.SUPPORT_GAP : FractionalSeatExitSafety.Reason.SUPPORT_UNVERIFIED,
                    rawBody, pos, host.toString(), seat.uid(), "support differs from the exact per-world material or per-cell smoothing manifest");
            VoxelShape local = host.getCollisionShape(level, pos, CollisionContext.of(rider));
            VoxelShape expectedShape = smoothing ? (fixture || fullWool ? Shapes.block() : FractionalSeatHost.shape(required)) : (full ? Shapes.block() : BOTTOM_SLAB);
            if (Shapes.joinIsNotEmpty(local, expectedShape, BooleanOp.NOT_SAME))
                return refused(stage, FractionalSeatExitSafety.Reason.SUPPORT_UNVERIFIED, rawBody, pos, host.toString(), seat.uid(), "verified material no longer has its exact full/slab shape");
            VoxelShape world = local.move(pos.getX(), pos.getY(), pos.getZ());
            hosts.put(pos, new Host(state, host, tile, data, own, world)); union = Shapes.or(union, world);
            if (hosts.size() > 4) return refused(stage, FractionalSeatExitSafety.Reason.POSE_UNCALIBRATED, rawBody, pos, host.toString(), seat.uid(), "support region exceeds calibrated four-cell bound");
        }
        if (!fullyCovered(contact, union)) return refused(stage, FractionalSeatExitSafety.Reason.SUPPORT_GAP, rawBody, null, "", seat.uid(), "verified support union does not cover the complete physical contact footprint");
        var accepted = new FractionalSeatExitSafety.Check(stage, FractionalSeatExitSafety.Reason.OK, rawBody, null, "", "", false,
            List.of(), seat.uid(), "calibrated host-only contact; rawDepth=" + (floor.y - rawBody.minY)
                + "; cap=.145+1e-6; complete physical footprint covered by " + hosts.size() + " verified supports");
        return new Plan(accepted, hosts, contact);
    }
    static boolean fullyCovered(VoxelShape contact, VoxelShape verifiedSupport) {
        return !Shapes.joinIsNotEmpty(contact, verifiedSupport, BooleanOp.ONLY_FIRST);
    }
    static boolean hostBlocks(VoxelShape body, VoxelShape host, VoxelShape allowedContact) {
        // Subtract only from this individually verified HOST contribution, never from owner masks.
        return Shapes.joinIsNotEmpty(body, Shapes.join(host, allowedContact, BooleanOp.ONLY_FIRST), BooleanOp.AND);
    }
    static boolean ownerBlocks(FractionalSeatData.Contribution owner, AABB physical, BlockPos pos) {
        return owner.intersectsLocalBox(physical.minX - pos.getX(), physical.minY - pos.getY(), physical.minZ - pos.getZ(),
            physical.maxX - pos.getX(), physical.maxY - pos.getY(), physical.maxZ - pos.getZ());
    }
    private static FractionalSeatContactPolicy.Point point(Vec3 p) { return new FractionalSeatContactPolicy.Point(p.x, p.y, p.z); }
    private static Plan refused(String stage, FractionalSeatExitSafety.Reason reason, AABB body, BlockPos pos, String state, UUID own, String detail) {
        return new Plan(new FractionalSeatExitSafety.Check(stage, reason, body, pos, state, "", false, List.of(), own, detail), Map.of(), Shapes.empty());
    }
    private FractionalSeatSupportContact() {}
}
