package local.cinemamanager.projection;

import com.mojang.serialization.MapCodec;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class CinemaProjectorPartBlock extends BaseEntityBlock {
    public static final MapCodec<CinemaProjectorPartBlock> CODEC = simpleCodec(CinemaProjectorPartBlock::new);
    public CinemaProjectorPartBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CinemaProjectorPartTile(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockPos anchor = ProjectorStructure.resolve(level,pos);
        if (anchor != null && level.getBlockEntity(anchor) instanceof CinemaProjectorTile projector)
            return ProjectorGeometry.shape(projector.style(),projector.machineFacing(),pos.subtract(anchor));
        return Shapes.block();
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        BlockPos anchor = ProjectorStructure.resolve(level, pos);
        if (anchor != null && level.getBlockEntity(anchor) instanceof CinemaProjectorTile projector)
            return CinemaProjectorItem.stack(projector.style());
        return ItemStack.EMPTY;
    }
}
