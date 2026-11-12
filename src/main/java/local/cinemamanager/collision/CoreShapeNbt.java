package local.cinemamanager.collision;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

/** Strict shape payload codec. Minecraft's packet/file decoder bounds the outer NBT. */
public final class CoreShapeNbt {
    private CoreShapeNbt() { }
    public static boolean readInto(CompoundTag tag, CoreShapeCache<?> cache) {
        // Exact NBT types: do not silently coerce a float, long or byte into an integer.
        if (tag == null || !tag.contains(CollisionCoreTile.VERSION_KEY, Tag.TAG_INT)
                || !tag.contains(CollisionCoreTile.RESOLUTION_KEY, Tag.TAG_INT)
                || !(tag.get(CollisionCoreTile.BOXES_KEY) instanceof IntArrayTag boxes)
                || boxes.size() > CoreShapeData.MAX_PACKED_INTS) {
            cache.reject();
            return false;
        }
        return cache.replacePacked(tag.getInt(CollisionCoreTile.VERSION_KEY),
                tag.getInt(CollisionCoreTile.RESOLUTION_KEY), boxes.getAsIntArray());
    }
    public static void write(CompoundTag tag, CoreShapeData data) {
        tag.putInt(CollisionCoreTile.VERSION_KEY, CoreShapeData.VERSION);
        tag.putInt(CollisionCoreTile.RESOLUTION_KEY, CoreShapeData.RESOLUTION);
        tag.putIntArray(CollisionCoreTile.BOXES_KEY, data.packedCopy());
    }
}
