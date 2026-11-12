package local.cinemamanager.panels;

import com.mojang.serialization.MapCodec;
import local.cinemamanager.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class WallInfoBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=InfoBlock.FACING;
    public static final MapCodec<WallInfoBlock> CODEC=simpleCodec(WallInfoBlock::new);
    public WallInfoBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING);}
    public static VoxelShape outline(Direction facing){return switch(facing){case NORTH->Block.box(0,0,14,16,16,16);case SOUTH->Block.box(0,0,0,16,16,2);case EAST->Block.box(0,0,0,2,16,16);case WEST->Block.box(14,0,0,16,16,16);default->Shapes.empty();};}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return outline(s.getValue(FACING));}
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return Shapes.empty();}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){Direction face=c.getClickedFace();if(!face.getAxis().isHorizontal())return null;BlockState s=defaultBlockState().setValue(FACING,face);return canSurvive(s,c.getLevel(),c.getClickedPos())?s:null;}
    @Override protected boolean canSurvive(BlockState s,LevelReader l,BlockPos p){BlockPos back=p.relative(s.getValue(FACING).getOpposite());return l.hasChunkAt(back)&&l.getBlockState(back).isFaceSturdy(l,back,s.getValue(FACING));}
    @Override protected BlockState updateShape(BlockState s,Direction d,BlockState other,LevelAccessor l,BlockPos p,BlockPos otherPos){return d==s.getValue(FACING).getOpposite()&&!canSurvive(s,l,p)?Blocks.AIR.defaultBlockState():super.updateShape(s,d,other,l,p,otherPos);}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return rotate(s,m.getRotation(s.getValue(FACING)));}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new InfoTile(p,s);}
    @Override public void setPlacedBy(Level l,BlockPos p,BlockState s,LivingEntity e,ItemStack i){if(!l.isClientSide&&e!=null&&l.getBlockEntity(p) instanceof InfoTile t){t.initializePlacement(e.getUUID());t.joinAdjacent();t.dirty();}}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player actor,BlockHitResult hit){if(!l.isClientSide&&actor instanceof ServerPlayer sp&&l.getBlockEntity(p) instanceof InfoTile t)t.interact(sp,actor.isShiftKeyDown());return InteractionResult.sidedSuccess(l.isClientSide);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> type){return l.isClientSide?null:createTickerHelper(type,CinemaManager.INFO_TILE.get(),(a,b,c,t)->t.tick());}
}
