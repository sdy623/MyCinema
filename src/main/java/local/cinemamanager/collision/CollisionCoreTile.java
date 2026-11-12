package local.cinemamanager.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** No renderer, ticker or global cache. Malformed/missing NBT produces empty collision. */
public final class CollisionCoreTile extends BlockEntity {
    public static final String VERSION_KEY = "core_version";
    public static final String RESOLUTION_KEY = "core_resolution";
    public static final String BOXES_KEY = "core_boxes";
    private final CoreShapeCache<VoxelShape> shape = new CoreShapeCache<>(CollisionCoreTile::buildShape);

    public CollisionCoreTile(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public VoxelShape collisionShape() { return isRemoved() ? Shapes.empty() : shape.shape(); }
    public CoreShapeData coreData() { return shape.data(); }
    public boolean hasValidCoreData() { return shape.lastInputValid(); }

    /** Trusted installer API; client writes are prohibited. Invalid input clears collision. */
    public boolean setCoreBoxes(int[] packed) {
        if (level != null && level.isClientSide) throw new IllegalStateException("Core changes are server-only");
        boolean valid = shape.replacePacked(CoreShapeData.VERSION, CoreShapeData.RESOLUTION, packed);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        return valid;
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CoreShapeNbt.readInto(tag, shape);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CoreShapeNbt.write(tag, shape.data());
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public boolean onlyOpCanSetNbt() { return true; }
    @Override public void setRemoved() { super.setRemoved(); shape.invalidate(); }

    @Override public void clearRemoved() { super.clearRemoved(); shape.invalidate(); }

    static VoxelShape buildShape(CoreShapeData data) {
        if (data.isEmpty()) return Shapes.empty();
        BitSetDiscreteVoxelShape voxels = new BitSetDiscreteVoxelShape(16, 16, 16);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
            if (data.occupied(x, y, z)) voxels.fill(x, y, z);
        // Direct fixed-grid construction avoids a potentially costly Shapes.or chain.
        return new GridShape(voxels);
    }
    private static final class GridShape extends ArrayVoxelShape {
        private GridShape(BitSetDiscreteVoxelShape voxels) { super(voxels, coordinates(), coordinates(), coordinates()); }
        private static double[] coordinates() {
            double[] result = new double[17];
            for (int i = 0; i <= 16; i++) result[i] = i / 16.0;
            return result;
        }
    }
}
