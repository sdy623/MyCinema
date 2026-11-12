package local.cinemamanager.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.projection.ProjectionGeometry;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class TheaterAudioAnchor {
    public static final double ALIGNMENT_TOLERANCE = 0.25;
    public static final double WORLD_LIMIT = 29_999_984;
    public enum Status { READY, PAIR_UNAVAILABLE, UNCALIBRATED, MISALIGNED, OUT_OF_BOUNDS, UNLOADED }
    public record Resolution(Status status, Vec3 position) { public boolean available() { return status == Status.READY && position != null; } }
    private TheaterAudioAnchor() {}
    public static List<CinemaProjectorTile> members(CinemaTerminalTile terminal) {
        List<CinemaProjectorTile> members = new ArrayList<>();
        if (terminal == null || terminal.getLevel() == null) return members;
        for (var entry : terminal.projectors().entrySet()) {
            if (!terminal.getLevel().hasChunkAt(entry.getKey())) return List.of();
            if (!(terminal.getLevel().getBlockEntity(entry.getKey()) instanceof CinemaProjectorTile projector)
                    || !entry.getValue().equals(projector.identity()) || !projector.linkedTo(terminal)) return List.of();
            members.add(projector);
        }
        members.sort(Comparator.comparingLong(p -> p.getBlockPos().asLong()));
        return members;
    }
    public static Resolution resolve(CinemaTerminalTile terminal) { return resolve(terminal, terminal == null ? null : terminal.audioMode()); }
    public static Resolution resolve(CinemaTerminalTile terminal, TheaterAudioMode mode) {
        if (terminal == null || terminal.getLevel() == null || mode == null) return unavailable(Status.PAIR_UNAVAILABLE);
        List<CinemaProjectorTile> members = members(terminal);
        if (members.size() != 2) return unavailable(Status.PAIR_UNAVAILABLE);
        if (mode == TheaterAudioMode.PROJECTOR) return new Resolution(Status.READY,
                members.get(0).getBlockPos().getCenter().add(members.get(1).getBlockPos().getCenter()).scale(0.5));
        if (mode == TheaterAudioMode.AUDIENCE) {
            Vec3 audience = terminal.audienceCenter();
            if (audience == null) return unavailable(Status.UNCALIBRATED);
            if (!finite(audience) || !withinWorld(terminal.getLevel(), audience) || !audienceWithinRange(terminal.getBlockPos(), audience)) return unavailable(Status.OUT_OF_BOUNDS);
            if (!terminal.getLevel().hasChunkAt(BlockPos.containing(audience))) return unavailable(Status.UNLOADED);
            return new Resolution(Status.READY, audience);
        }
        Vec3[] first = ProjectionGeometry.corners(members.get(0)), second = ProjectionGeometry.corners(members.get(1));
        if (!ProjectionGeometry.aligned(first, second, ALIGNMENT_TOLERANCE)) return unavailable(Status.MISALIGNED);
        Vec3 center = ProjectionGeometry.center(first);
        return withinWorld(terminal.getLevel(), center) ? new Resolution(Status.READY, center) : unavailable(Status.OUT_OF_BOUNDS);
    }
    public static Resolution unavailable(Status status) { return new Resolution(status, null); }
    public static double audienceRange() { return 64; }
    public static boolean finite(Vec3 point) { return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
    public static boolean audienceWithinRange(BlockPos terminal, Vec3 point) { return finite(point) && terminal.getCenter().distanceToSqr(point) <= audienceRange() * audienceRange(); }
    public static boolean withinWorld(net.minecraft.world.level.Level level, Vec3 point) { return finite(point) && Math.abs(point.x) <= WORLD_LIMIT && Math.abs(point.z) <= WORLD_LIMIT && point.y >= level.getMinBuildHeight() && point.y < level.getMaxBuildHeight(); }
}
