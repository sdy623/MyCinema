package local.cinemamanager.seats;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** An independent 8 x 4 x 1/2 pixel wall tag or floor decal, never a Chisels-and-Bits block. */
public final class SeatGuideBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty FLOOR=BooleanProperty.create("floor");
    public static final MapCodec<SeatGuideBlock> CODEC=simpleCodec(SeatGuideBlock::new);
    private static final VoxelShape FLOOR_NS=Block.box(4,0,6,12,.5,10),FLOOR_EW=Block.box(6,0,4,10,.5,12);
    public SeatGuideBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(FLOOR,false));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING,FLOOR);}
    public static VoxelShape outline(Direction face){return switch(face){case NORTH->Block.box(4,6,15.5,12,10,16);case SOUTH->Block.box(4,6,0,12,10,.5);case EAST->Block.box(0,6,4,.5,10,12);case WEST->Block.box(15.5,6,4,16,10,12);default->Shapes.empty();};}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return s.getValue(FLOOR)?(s.getValue(FACING).getAxis()==Direction.Axis.Z?FLOOR_NS:FLOOR_EW):outline(s.getValue(FACING));}
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return Shapes.empty();}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        Direction face=c.getClickedFace();
        if(face.getAxis().isHorizontal())return defaultBlockState().setValue(FACING,face);
        if(face!=Direction.UP)return null;
        BlockState floor=defaultBlockState().setValue(FLOOR,true).setValue(FACING,c.getHorizontalDirection().getOpposite());
        return floor.canSurvive(c.getLevel(),c.getClickedPos())?floor:null;
    }
    @Override protected boolean canSurvive(BlockState s,LevelReader l,BlockPos p){
        // Floor decals occupy the cell above a full-height top surface. Lower slabs would leave a gap.
        return !s.getValue(FLOOR)||l.getBlockState(p.below()).isFaceSturdy(l,p.below(),Direction.UP);
    }
    @Override protected BlockState updateShape(BlockState s,Direction direction,BlockState neighbor,LevelAccessor l,BlockPos p,BlockPos neighborPos){
        return s.getValue(FLOOR)&&direction==Direction.DOWN&&!s.canSurvive(l,p)?Blocks.AIR.defaultBlockState():super.updateShape(s,direction,neighbor,l,p,neighborPos);
    }
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return rotate(s,m.getRotation(s.getValue(FACING)));}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new SeatGuideTile(p,s);}
    @Override public void setPlacedBy(Level l,BlockPos p,BlockState s,LivingEntity actor,ItemStack stack){if(!l.isClientSide&&actor!=null&&l.getBlockEntity(p)instanceof SeatGuideTile tile)tile.initialize(actor.getUUID(),stack);}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player actor,BlockHitResult hit){if(!l.isClientSide&&actor instanceof ServerPlayer player&&l.getBlockEntity(p)instanceof SeatGuideTile tile&&tile.permits(player,8))SeatNetwork.openGuide(player,tile);return InteractionResult.sidedSuccess(l.isClientSide);}
}
