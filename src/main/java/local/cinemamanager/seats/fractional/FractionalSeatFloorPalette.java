package local.cinemamanager.seats.fractional;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit per-world material contract; never infers a profile from possibly corrupt terrain. */
final class FractionalSeatFloorPalette {
    private static final ResourceLocation STORAGE = ResourceLocation.fromNamespaceAndPath("mycinema", "floor_palette");
    enum Profile {
        LEGACY, WOOL_DEEPSLATE_V1, WOOL_SURFACES_V2, WOOL_SMOOTH_Y_V3, WOOL_SMOOTH_Y_HIDDEN_PORTS_V4, WOOL_ORIGINAL368_V1, WOOL_ORIGINAL368_CARPET_V2;
        BlockState fullSupport() { return this == LEGACY ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.GRAY_WOOL.defaultBlockState(); }
        BlockState slabSupport() { return this == LEGACY ? FractionalSeatHost.ANDESITE_BOTTOM_SLAB : FractionalSeatHost.BOTTOM_SLAB; }
    }
    static Profile forLevel(ServerLevel level) { return parse(level.getServer().getCommandStorage().get(STORAGE)); }
    static Profile parse(CompoundTag tag) {
        if (tag.isEmpty()) return Profile.LEGACY;
        if (!tag.getAllKeys().equals(Set.of("schema_version", "profile", "layout"))
                || !tag.contains("schema_version", 3) || tag.getInt("schema_version") != 1
                || !tag.contains("profile", 8) || !Set.of("wool_deepslate_v1", "wool_surfaces_v2", "wool_smooth_y_v3", "wool_smooth_y_hidden_ports_v4", "wool_original368_v1", "wool_original368_carpet_v2").contains(tag.getString("profile"))
                || !tag.contains("layout", 8) || !tag.getString("layout").equals(tag.getString("profile").equals("wool_original368_carpet_v2") ? "original368_highback150_carpet_v2" : tag.getString("profile").equals("wool_original368_v1") ? "original368_highback150_v1" : tag.getString("profile").equals("wool_smooth_y_hidden_ports_v4") ? "selected150_y_smooth_hidden_ports_v1" : tag.getString("profile").equals("wool_smooth_y_v3") ? "selected150_y_smooth_v1" : "selected150_372_1814"))
            throw new IllegalArgumentException("Unknown or malformed selected150 floor-palette contract; refusing material inference");
        if (tag.getString("profile").equals("wool_original368_carpet_v2")) return Profile.WOOL_ORIGINAL368_CARPET_V2;
        if (tag.getString("profile").equals("wool_original368_v1")) return Profile.WOOL_ORIGINAL368_V1;
        if (tag.getString("profile").equals("wool_smooth_y_hidden_ports_v4")) return Profile.WOOL_SMOOTH_Y_HIDDEN_PORTS_V4;
        if (tag.getString("profile").equals("wool_smooth_y_v3")) return Profile.WOOL_SMOOTH_Y_V3;
        return tag.getString("profile").equals("wool_surfaces_v2") ? Profile.WOOL_SURFACES_V2 : Profile.WOOL_DEEPSLATE_V1;
    }
    private FractionalSeatFloorPalette() {}
}
