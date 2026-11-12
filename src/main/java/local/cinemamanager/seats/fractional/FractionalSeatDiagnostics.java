package local.cinemamanager.seats.fractional;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import java.util.*;

/** Bounded operator diagnostics. No block/entity mutation, mounting, teleport, or chunk admission. */
final class FractionalSeatDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Long> NEXT_LOG = new HashMap<>();
    private static final long LOG_INTERVAL_TICKS = 100;

    static int diagnose(CommandSourceStack source, String requestedLabel) {
        if (!source.hasPermission(2) || !(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this operator-only diagnostic as the player whose passenger body should be measured.")); return 0;
        }
        if (!player.level().dimension().equals(Level.OVERWORLD)) {
            source.sendFailure(Component.literal("Selected seat diagnostics are overworld-only.")); return 0;
        }
        if (requestedLabel.length() != 3 || !requestedLabel.toUpperCase(Locale.ROOT).matches("[A-L][0-9]{2}")) {
            source.sendFailure(Component.literal("Expected one selected seat label, for example A01.")); return 0;
        }
        try {
            var seat = FractionalSeatLayout.get(player.serverLevel()).labels.get(requestedLabel.toUpperCase(Locale.ROOT));
            if (seat == null) { source.sendFailure(Component.literal("Unknown selected seat label: " + requestedLabel)); return 0; }
            var entities = FractionalSeatIntegration.entities(player.serverLevel(), seat);
            var assessment = FractionalSeatExitSafety.diagnoseEntry(player.serverLevel(), player, entities.marker(), seat);
            boolean installed = FractionalSeatInstallation.installed(player.serverLevel(), seat);
            source.sendSuccess(() -> Component.literal("Seat " + seat.label() + " uid=" + seat.uid() + " installed=" + installed
                + " markerPassengers=" + entities.marker().getPassengers().size() + " riderPose=" + player.getPose()
                + " riderPassenger=" + player.isPassenger() + " safety=" + (assessment.allowed() ? "ALL_CLEAR" : assessment.firstFailure().code())), false);
            source.sendSuccess(() -> Component.literal("chairFrontYaw=" + FractionalSeatFacing.frontYaw(seat.anchor().turns())
                + " markerYaw=" + entities.marker().getYRot() + " riderYaw=" + player.getYRot() + " previousYaw=" + player.yRotO
                + " headYaw=" + player.getYHeadRot() + " previousHeadYaw=" + player.yHeadRotO
                + " bodyYaw=" + player.yBodyRot + " previousBodyYaw=" + player.yBodyRotO + " pitch=" + player.getXRot()), false);
            source.sendSuccess(() -> Component.literal(pose(assessment)), false);
            for (var check : assessment.checks()) source.sendSuccess(() -> Component.literal(format(check)), false);
            if (player.getVehicle() == entities.marker()) {
                var actual = FractionalSeatExitSafety.assessMountedBody(player.serverLevel(), player, seat);
                source.sendSuccess(() -> Component.literal("actualRiderFeet=" + point(player.position())
                    + " actualRiderFullAABB=" + box(player.getBoundingBox()) + " actualMountedCheck=" + format(actual)), false);
            } else source.sendSuccess(() -> Component.literal("actualMountedBody=UNAVAILABLE_NOT_ON_MATCHING_MARKER"), false);
            // Report success means the read-only diagnosis ran; it does not authorize or perform a ride.
            return 1;
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("Seat diagnostic refused: IDENTITY_OR_DATA_FAILURE: " + failure.getMessage())); return 0;
        }
    }
    static void entryFailure(ServerPlayer player, FractionalSeatLayout.Seat seat, FractionalSeatExitSafety.EntryAssessment assessment) {
        player.displayClientMessage(Component.literal("Seat " + seat.label() + " refused: " + assessment.firstFailure().code()), true);
        if (mayLog(player)) LOGGER.warn("Fractional seat entry refusal player={} seat={} uid={} {} checks={}",
            player.getUUID(), seat.label(), seat.uid(), pose(assessment), assessment.checks().stream().map(FractionalSeatDiagnostics::format).toList());
    }
    static void mountedFailure(ServerPlayer player, FractionalSeatLayout.Seat seat, FractionalSeatExitSafety.Check check) {
        if (mayLog(player)) LOGGER.warn("Fractional seat positioned-body refusal player={} seat={} uid={} {}",
            player.getUUID(), seat.label(), seat.uid(), format(check));
    }
    private static boolean mayLog(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        if (now < NEXT_LOG.getOrDefault(player.getUUID(), Long.MIN_VALUE)) return false;
        if (NEXT_LOG.size() >= 1024) NEXT_LOG.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (NEXT_LOG.size() >= 1024 && !NEXT_LOG.containsKey(player.getUUID())) return false;
        NEXT_LOG.put(player.getUUID(), now + LOG_INTERVAL_TICKS); return true;
    }
    static void forget(UUID player) { NEXT_LOG.remove(player); }
    static void clear() { NEXT_LOG.clear(); }
    static String pose(FractionalSeatExitSafety.EntryAssessment assessment) {
        return "passengerPoint=" + point(assessment.passengerPoint()) + " attachment=" + point(assessment.attachmentPoint())
            + " predictedFeet=" + point(assessment.predictedFeet())
            + " feetMinusFloorY=" + String.format(Locale.ROOT, "%.6f", assessment.predictedFeet().y - assessment.floorAnchor().y)
            + " predictedFullAABB=" + box(assessment.predictedBody())
            + " floorAnchor=" + point(assessment.floorAnchor()) + " floorFullAABB=" + box(assessment.floorBody())
            + " exit=" + point(assessment.exit());
    }
    static String format(FractionalSeatExitSafety.Check check) {
        return check.code() + " tested=" + box(check.testedBox())
            + (check.block() == null ? "" : " firstBlock=" + check.block().toShortString() + " state=" + check.state())
            + (check.retainedHost().isEmpty() ? "" : " retainedHost=" + check.retainedHost() + " retainedHostCollision=" + check.retainedHostCollision())
            + (check.blockingOwners().isEmpty() ? "" : " blockingOwnerUIDs=" + check.blockingOwners())
            + (check.ignoredOwner() == null ? "" : " excludedOwnUID=" + check.ignoredOwner())
            + (check.detail().isEmpty() ? "" : " detail=" + check.detail());
    }
    private static String point(Vec3 p) { return String.format(Locale.ROOT, "(%.6f,%.6f,%.6f)", p.x, p.y, p.z); }
    private static String box(AABB b) {
        return b == null ? "none" : String.format(Locale.ROOT, "[(%.6f,%.6f,%.6f)..(%.6f,%.6f,%.6f)]", b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }
    private FractionalSeatDiagnostics() {}
}
