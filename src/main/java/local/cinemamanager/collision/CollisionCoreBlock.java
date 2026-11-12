package local.cinemamanager.collision;

import com.mojang.serialization.MapCodec;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Invisible, per-block bounded collision only. Must never be registered with noCollission(). */
public final class CollisionCoreBlock extends BaseEntityBlock {
    private final Supplier<? extends BlockEntityType<?>> tileType;
    private final MapCodec<CollisionCoreBlock> codec;

    public CollisionCoreBlock(Properties properties, Supplier<? extends BlockEntityType<?>> tileType) {
        super(properties.dynamicShape().noOcclusion().pushReaction(PushReaction.BLOCK)
                .isSuffocating((state, world, pos) -> false).isViewBlocking((state, world, pos) -> false));
        if (!hasCollision) throw new IllegalArgumentException("Collision cores require collision-enabled properties");
        this.tileType = Objects.requireNonNull(tileType);
        this.codec = simpleCodec(p -> new CollisionCoreBlock(p, tileType));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return codec; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CollisionCoreTile(tileType.get(), pos, state);
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return world.getBlockEntity(pos) instanceof CollisionCoreTile tile ? tile.collisionShape() : Shapes.empty();
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return getCollisionShape(state, world, pos, context);
    }
    @Override protected VoxelShape getOcclusionShape(BlockState state, BlockGetter world, BlockPos pos) { return Shapes.empty(); }
    @Override protected VoxelShape getVisualShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) { return Shapes.empty(); }
    @Override protected int getLightBlock(BlockState state, BlockGetter world, BlockPos pos) { return 0; }
    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter world, BlockPos pos) { return true; }
    @Override protected float getShadeBrightness(BlockState state, BlockGetter world, BlockPos pos) { return 1.0f; }
}
