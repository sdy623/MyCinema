package local.cinemamanager;
import com.mojang.serialization.MapCodec;
import local.cinemamanager.model.ManagerState.LightGroup;
import net.minecraft.core.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
public final class EndpointBlock extends BaseEntityBlock {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    public final boolean light;
    public final LightGroup group;
    public EndpointBlock(Properties properties, boolean light) { this(properties,light?LightGroup.MAIN:null); }
    public EndpointBlock(Properties properties, LightGroup group) { super(properties); this.group=group; this.light=group!=null; registerDefaultState(stateDefinition.any().setValue(POWER, light ? 15 : 0)); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(p -> new EndpointBlock(p, group)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(POWER); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected boolean isSignalSource(BlockState state) { return true; }
    @Override protected int getSignal(BlockState s, BlockGetter l, BlockPos p, Direction d) { return s.getValue(POWER); }
    @Override protected int getDirectSignal(BlockState s, BlockGetter l, BlockPos p, Direction d) { return s.getValue(POWER); }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new EndpointTile(p, s); }
    @Override public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity e, ItemStack i) { if (!l.isClientSide && e != null && l.getBlockEntity(p) instanceof EndpointTile t) { t.owner = e.getUUID(); t.dirty(); } }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moved) {
        if(!state.is(next.getBlock())&&level.getBlockEntity(pos) instanceof EndpointTile output&&output.manager!=null&&level.hasChunkAt(output.manager)
                &&level.getBlockEntity(output.manager) instanceof ManagerTile manager&&manager.identity.equals(output.managerIdentity))manager.removeOutput(pos);
        super.onRemove(state,level,pos,next,moved);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> type) { return l.isClientSide ? null : createTickerHelper(type, CinemaManager.ENDPOINT_TILE.get(), (a,b,c,t) -> t.tick()); }
}
