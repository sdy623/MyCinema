package local.cinemamanager.seats.fractional;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Exact world-scoped layout opt-in. An unknown or malformed marker never falls back. */
final class FractionalSeatLayoutVersion {
    enum Version {
        ORIGINAL("selected150_372_1814"), Y_SMOOTH_V1("selected150_y_smooth_v1"), Y_SMOOTH_HIDDEN_PORTS_V1("selected150_y_smooth_hidden_ports_v1"), ORIGINAL368_V1("original368_highback150_v1"), ORIGINAL368_CARPET_V2("original368_highback150_carpet_v2");
        final String id;
        Version(String id) { this.id = id; }
    }
    static Version forLevel(ServerLevel level) { return fromPalette(FractionalSeatFloorPalette.forLevel(level)); }
    static Version parse(CompoundTag tag) { return fromPalette(FractionalSeatFloorPalette.parse(tag)); }
    private static Version fromPalette(FractionalSeatFloorPalette.Profile profile) {
        return switch (profile) {
            case LEGACY, WOOL_DEEPSLATE_V1, WOOL_SURFACES_V2 -> Version.ORIGINAL;
            case WOOL_SMOOTH_Y_V3 -> Version.Y_SMOOTH_V1;
            case WOOL_SMOOTH_Y_HIDDEN_PORTS_V4 -> Version.Y_SMOOTH_HIDDEN_PORTS_V1;
            case WOOL_ORIGINAL368_V1 -> Version.ORIGINAL368_V1;
            case WOOL_ORIGINAL368_CARPET_V2 -> Version.ORIGINAL368_CARPET_V2;
        };
    }
    private FractionalSeatLayoutVersion() {}
}
