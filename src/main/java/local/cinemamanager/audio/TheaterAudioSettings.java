package local.cinemamanager.audio;

import java.util.Set;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class TheaterAudioSettings {
    private static final Set<String> MODE_KEYS = Set.of("terminal_id", "mode");
    private static final Set<String> POINT_KEYS = Set.of("terminal_id", "x", "y", "z");
    private TheaterAudioSettings() {}
    public static boolean validModeRequest(CompoundTag tag) { return tag != null && tag.getAllKeys().equals(MODE_KEYS) && tag.hasUUID("terminal_id") && TheaterAudioMode.parse(tag.getString("mode")) != null; }
    public static Vec3 requestedPoint(CompoundTag tag) { return tag == null ? null : new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")); }
    private static boolean authorized(CinemaTerminalTile terminal, ServerPlayer actor, CompoundTag tag) {
        if (terminal == null || actor == null || tag == null || !(terminal.getLevel() instanceof ServerLevel level)
                || actor.level() != level || !level.getServer().isSameThread() || level.getBlockEntity(terminal.getBlockPos()) != terminal
                || !terminal.identity().equals(tag.hasUUID("terminal_id") ? tag.getUUID("terminal_id") : null)
                || !terminal.canControl(actor) || TheaterAudioAnchor.members(terminal).size() != 2) return false;
        for (CinemaProjectorTile member : TheaterAudioAnchor.members(terminal)) if (!member.ownedBy(actor.getUUID())) return false;
        return true;
    }
    public static boolean select(CinemaTerminalTile terminal, ServerPlayer actor, CompoundTag tag) {
        if (!validModeRequest(tag) || !authorized(terminal, actor, tag)) return false;
        terminal.audioMode(TheaterAudioMode.parse(tag.getString("mode"))); return true;
    }
    public static boolean calibrate(CinemaTerminalTile terminal, ServerPlayer actor, CompoundTag tag) {
        if (tag == null || !tag.getAllKeys().equals(POINT_KEYS) || !authorized(terminal, actor, tag)) return false;
        Vec3 point = requestedPoint(tag); BlockPos pos = BlockPos.containing(point);
        if (!TheaterAudioAnchor.finite(point) || !TheaterAudioAnchor.audienceWithinRange(terminal.getBlockPos(), point)
                || !TheaterAudioAnchor.withinWorld(terminal.getLevel(), point) || !terminal.getLevel().hasChunkAt(pos)) return false;
        terminal.audienceCenter(point); return true;
    }
}
