package local.cinemamanager;
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
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
public final class InfoBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<InfoBlock> CODEC=simpleCodec(InfoBlock::new);
    public InfoBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH)); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite()); }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s) { return new InfoTile(p,s); }
    @Override public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity e, ItemStack i) { if (!l.isClientSide && e!=null && l.getBlockEntity(p) instanceof InfoTile t) { t.initializePlacement(e.getUUID());t.dirty(); } }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit) {
        if (!l.isClientSide && player instanceof ServerPlayer sp && l.getBlockEntity(p) instanceof InfoTile t) {
            t.interact(sp,player.isShiftKeyDown());
        }
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> type) { return l.isClientSide?null:createTickerHelper(type,CinemaManager.INFO_TILE.get(),(a,b,c,t)->t.tick()); }
}
