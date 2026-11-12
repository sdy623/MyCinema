package local.cinemamanager.access;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class StaffReaderBlock extends BaseEntityBlock {
    /** Outward normal of the reading face. Old palettes omitted facing and load north. */
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<StaffReaderBlock> CODEC=simpleCodec(StaffReaderBlock::new);
    private static final VoxelShape NORTH=Block.box(5.5,4,14.75,10.5,12,16);
    private static final VoxelShape EAST=Block.box(0,4,5.5,1.25,12,10.5);
    private static final VoxelShape SOUTH=Block.box(5.5,4,0,10.5,12,1.25);
    private static final VoxelShape WEST=Block.box(14.75,4,5.5,16,12,10.5);
    public StaffReaderBlock(Properties properties){
        super(properties.noOcclusion().isSuffocating((state,level,pos)->false).isViewBlocking((state,level,pos)->false));
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return switch(state.getValue(FACING)){case EAST->EAST;case SOUTH->SOUTH;case WEST->WEST;default->NORTH;};
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return getShape(state,level,pos,context);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){
        Direction facing=context.getClickedFace();
        if(!facing.getAxis().isHorizontal())return null;
        BlockPos backing=context.getClickedPos().relative(facing.getOpposite());
        if(!context.getLevel().hasChunkAt(backing)||!context.getLevel().getBlockState(backing).isFaceSturdy(context.getLevel(),backing,facing))return null;
        return defaultBlockState().setValue(FACING,facing);
    }
    // Check backing only for new item placement. Legacy free-standing readers must keep their
    // block entity and pairing when loaded or when a neighbor changes; never auto-drop them.
    @Override protected BlockState rotate(BlockState state,Rotation rotation){return state.setValue(FACING,rotation.rotate(state.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState state,Mirror mirror){return rotate(state,mirror.getRotation(state.getValue(FACING)));}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new StaffReaderTile(pos,state);}
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,LivingEntity actor,ItemStack stack){if(!level.isClientSide&&level.getBlockEntity(pos) instanceof StaffReaderTile reader)reader.initialize(actor==null?null:actor.getUUID());}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide&&player instanceof ServerPlayer actor&&level.getBlockEntity(pos) instanceof StaffReaderTile reader){
            try{reader.requireEditor(actor);AccessNetwork.open(actor,reader);}catch(IllegalArgumentException denied){actor.displayClientMessage(Component.translatable("mycinema.access.reader_help"),false);}
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    // BaseEntityBlock removes the tile normally. Employee credentials belong to the cinema;
    // removing the reader that originally issued one must not revoke that cinema-wide grant.
}
