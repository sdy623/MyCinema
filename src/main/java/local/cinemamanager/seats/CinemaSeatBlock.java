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
import java.util.Optional;
import java.util.UUID;

/** The actual selectable, collidable and sittable auditorium chair; there are no display/proxy parts. */
public final class CinemaSeatBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<CinemaSeatBlock> CODEC=simpleCodec(CinemaSeatBlock::new);
    private static final MapCodec<CinemaSeatBlock> DOUBLE_SUPPORT_CODEC=simpleCodec(properties->new CinemaSeatBlock(properties,true));
    private final boolean doubleSupport;
    public CinemaSeatBlock(Properties properties) { this(properties,false); }
    public CinemaSeatBlock(Properties properties,boolean doubleSupport) {
        super(properties);
        this.doubleSupport=doubleSupport;
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
    }
    public boolean doubleSupport() { return doubleSupport; }
    public net.minecraft.world.phys.Vec3 mountPosition(BlockPos pos,Direction facing) {
        return doubleSupport ? DoubleSupportSeatGeometry.mountPosition(pos,facing) : CinemaSeatGeometry.mountPosition(pos,facing);
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return doubleSupport ? DOUBLE_SUPPORT_CODEC : CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(FACING); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return doubleSupport ? DoubleSupportSeatGeometry.shape(state.getValue(FACING)) : CinemaSeatGeometry.shape(state.getValue(FACING));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context) {
        return doubleSupport ? DoubleSupportSeatGeometry.shape(state.getValue(FACING)) : CinemaSeatGeometry.shape(state.getValue(FACING));
    }
    @Override protected boolean isPathfindable(BlockState state,net.minecraft.world.level.pathfinder.PathComputationType type) { return false; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state=defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite());
        return state.canSurvive(context.getLevel(),context.getClickedPos()) ? state : null;
    }
    @Override protected boolean canSurvive(BlockState state,LevelReader level,BlockPos pos) {
        return level.hasChunkAt(pos.below()) && level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);
    }
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbor,LevelAccessor level,BlockPos pos,BlockPos neighborPos) {
        return direction==Direction.DOWN && !state.canSurvive(level,pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state,direction,neighbor,level,pos,neighborPos);
    }
    @Override protected BlockState rotate(BlockState state,Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state,Mirror mirror) { return rotate(state,mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state) { return new CinemaSeatTile(pos,state); }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity actor,ItemStack stack) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CinemaSeatTile tile) tile.initializeFresh();
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player actor,BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (actor instanceof ServerPlayer player && findSeat(level,pos).isPresent())
            return findSeat(level,pos).orElseThrow().sit(player,hit) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        return InteractionResult.FAIL;
    }
    @Override protected void onRemove(BlockState oldState,Level level,BlockPos pos,BlockState newState,boolean moved) {
        if (!oldState.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CinemaSeatTile tile)
            tile.releaseOccupant();
        super.onRemove(oldState,level,pos,newState,moved);
    }

    /** Does not load chunks. Canonical position equals the single occupied chair cell. */
    public static Optional<CinemaSeatTile> findSeat(LevelReader level,BlockPos pos) {
        if (pos==null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockState state=level.getBlockState(pos);
        if (!(state.getBlock() instanceof CinemaSeatBlock) || !state.canSurvive(level,pos)) return Optional.empty();
        return level.getBlockEntity(pos) instanceof CinemaSeatTile tile && !tile.isRemoved()
            ? Optional.of(tile) : Optional.empty();
    }
    public static boolean isValidSeat(LevelReader level,BlockPos pos,UUID expectedUid) {
        return expectedUid!=null && findSeat(level,pos).filter(tile->expectedUid.equals(tile.seatUid())).isPresent();
    }
}
