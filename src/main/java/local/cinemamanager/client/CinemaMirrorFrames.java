package local.cinemamanager.client;

import local.cinemamanager.mirror.CinemaMirrors;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import me.srrapero720.waterframes.client.display.Display;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Borrows an existing display texture; never creates a second media player. */
public final class CinemaMirrorFrames {
    public enum Kind { BLACK, MEDIA }
    public record Frame(Kind kind, ResourceLocation texture, int width, int height) {}

    private CinemaMirrorFrames() {}

    public static Display active(DisplayTile tile) {
        if (tile == null) return null;
        Display display = tile instanceof CinemaTerminalTile terminal ? terminal.activeDisplay()
                : tile instanceof CinemaProjectorTile projector ? projector.activeDisplay() : tile.display;
        return display == null || display.isReleased() ? null : display;
    }

    public static Frame current(Level level, CinemaMirrors.BindingRef ref) {
        DisplayTile source = CinemaMirrors.matchLoaded(level, ref);
        if (source == null) return null;
        DisplayTile authority = source instanceof CinemaProjectorTile projector ? projector.terminal() : source;
        Display display = active(authority);
        return display == null || !display.canRender()
                ? new Frame(Kind.BLACK, null, 0, 0)
                : new Frame(Kind.MEDIA, display.textureId(), display.width(), display.height());
    }
}
