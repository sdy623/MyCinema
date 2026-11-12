package local.cinemamanager.projection;

import me.srrapero720.waterframes.common.block.data.DisplayData;
import net.minecraft.nbt.CompoundTag;

public final class CinemaProjectionSettings {
    public static final String OFFSET_X = "projection_offset_x";
    public static final String OFFSET_Y = "projection_offset_y";
    public static final String OFFSET_Z = "projection_offset_z";
    private CinemaProjectionSettings() {}
    public static boolean valid(CompoundTag tag) {
        if (tag == null) return false;
        for (String key : tag.getAllKeys()) {
            float value = tag.getFloat(key); if (!Float.isFinite(value)) return false;
            boolean okay = switch (key) {
                case DisplayData.WIDTH, DisplayData.HEIGHT -> value >= 0.25f && value <= 128;
                case DisplayData.PROJECTION_DISTANCE -> value >= 0 && value <= 128;
                case OFFSET_X, OFFSET_Y, OFFSET_Z -> Math.abs(value) <= 512;
                default -> false;
            };
            if (!okay) return false;
        }
        return !tag.getAllKeys().isEmpty();
    }
    public static void apply(CinemaProjectorTile tile, CompoundTag tag) {
        if (tag.contains(DisplayData.WIDTH)) tile.data.setWidth(tag.getFloat(DisplayData.WIDTH));
        if (tag.contains(DisplayData.HEIGHT)) tile.data.setHeight(tag.getFloat(DisplayData.HEIGHT));
        if (tag.contains(DisplayData.PROJECTION_DISTANCE)) tile.data.projectionDistance = tag.getFloat(DisplayData.PROJECTION_DISTANCE);
        if (tag.contains(OFFSET_X)) tile.projectionOffsetX = tag.getFloat(OFFSET_X);
        if (tag.contains(OFFSET_Y)) tile.projectionOffsetY = tag.getFloat(OFFSET_Y);
        if (tag.contains(OFFSET_Z)) tile.projectionOffsetZ = tag.getFloat(OFFSET_Z);
        tile.setChanged();
    }
}
