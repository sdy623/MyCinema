package local.cinemamanager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
public final class ManagerBlock extends BaseEntityBlock {
    public static final MapCodec<ManagerBlock> CODEC = simpleCodec(ManagerBlock::new);
    public ManagerBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ManagerTile(pos, state); }
    @Override public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity e, ItemStack i) { if (!l.isClientSide && e != null && l.getBlockEntity(p) instanceof ManagerTile t) { t.initializePlacement(e.getUUID()); t.dirty(); } }
    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player player, BlockHitResult hit) {
        if (!l.isClientSide && player instanceof ServerPlayer sp && l.getBlockEntity(p) instanceof ManagerTile t) {
            if (t.permits(sp, 8)) ManagerNetwork.open(sp, t, false);
            else sp.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m099")), true);
        }
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> type) {
        return l.isClientSide ? null : createTickerHelper(type, CinemaManager.MANAGER_TILE.get(), (level, pos, state, tile) -> tile.tick());
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof ManagerTile tile) { local.cinemamanager.access.EmergencyAccess.retireController(tile); tile.suspend(local.cinemamanager.MessageText.key("message.m100")); tile.releaseEnrollments(); }
        super.onRemove(state, level, pos, next, moved);
    }
}
