package local.cinemamanager.seats.fractional;
import net.minecraft.core.*;import net.minecraft.world.item.context.BlockPlaceContext;import net.minecraft.world.level.BlockGetter;import net.minecraft.world.level.block.Block;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.world.phys.shapes.*;
/** Real half-height, half-depth solid block; no display shell, carpet or waterlogging. */
public final class WoolHalfDepthBlock extends Block {
 public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
 public WoolHalfDepthBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.SOUTH));}
 protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING);}
 public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
 protected VoxelShape getShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext c){return switch(s.getValue(FACING)){case SOUTH->box(0,0,8,16,8,16);case NORTH->box(0,0,0,16,8,8);case EAST->box(8,0,0,16,8,16);case WEST->box(0,0,0,8,8,16);default->throw new IllegalStateException("Nonhorizontal half-depth slab");};}
}
