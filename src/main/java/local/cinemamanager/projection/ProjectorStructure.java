package local.cinemamanager.projection;

import java.util.LinkedHashSet;
import java.util.Set;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class ProjectorStructure {
    private ProjectorStructure() {}
    public static BlockPos resolve(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CinemaProjectorTile) return pos;
        if (level.getBlockEntity(pos) instanceof CinemaProjectorPartTile part) return part.anchor();
        return null;
    }
    public static void removeParts(Level level, CinemaProjectorTile projector) {
        for (BlockPos offset : ProjectorGeometry.cells(projector.style(), projector.machineFacing()).keySet())
            if (!offset.equals(BlockPos.ZERO)) {
                BlockPos pos = projector.getBlockPos().offset(offset);
                if (level.getBlockEntity(pos) instanceof CinemaProjectorPartTile part
                        && part.belongsTo(projector.getBlockPos(), projector.identity())) level.removeBlock(pos, false);
            }
    }

    /**
     * Treat the projector cabinet as one survival object even though its collision
     * occupies several ordinary blocks. This listener runs only after the normal
     * break event has been accepted; it cancels vanilla's single-cell removal and
     * performs one style-preserving structure removal instead.
     */
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos broken = event.getPos();
        BlockPos anchor = resolve(level, broken);
        if (anchor == null || !(level.getBlockEntity(anchor) instanceof CinemaProjectorTile projector)) return;

        ProjectorStyle style = projector.style();
        Set<BlockPos> occupied = new LinkedHashSet<>();
        for (BlockPos offset : ProjectorGeometry.cells(style, projector.machineFacing()).keySet()) {
            occupied.add(anchor.offset(offset));
        }

        event.setCanceled(true);
        for (BlockPos pos : occupied) {
            if (pos.equals(anchor)) continue;
            if (level.getBlockEntity(pos) instanceof CinemaProjectorPartTile part
                    && part.belongsTo(anchor, projector.identity())) level.removeBlock(pos, false);
        }
        level.removeBlock(anchor, false);
        if (!event.getPlayer().isCreative()) {
            Block.popResource(level, anchor, CinemaProjectorItem.stack(style));
        }

        // BreakEvent cancellation sends the pre-break state of the clicked cell to
        // the client after listeners return. Re-send the final structure state on
        // the next server turn so the client cannot retain a one-cell ghost.
        level.getServer().execute(() -> {
            for (BlockPos pos : occupied) {
                var state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, 3);
            }
        });
    }

    /**
     * NeoForge restores cancelled multi-place snapshots in reverse write order.
     * Replaceable plants can therefore be restored before a neighbouring cell and
     * then removed again by that neighbour update. Restore air first and retained
     * blocks last while the placement event is still active; NeoForge's subsequent
     * rollback then becomes a no-op and the pre-placement world is exact.
     */
    public static void onCancelledPlace(BlockEvent.EntityPlaceEvent event) {
        if (!event.isCanceled() || !(event instanceof BlockEvent.EntityMultiPlaceEvent multi)
                || !(event.getLevel() instanceof Level level)) return;
        var snapshots = multi.getReplacedBlockSnapshots();
        boolean ours = snapshots.stream().anyMatch(snapshot -> {
            var state = level.getBlockState(snapshot.getPos());
            return state.is(CinemaManager.CINEMA_PROJECTOR.get()) || state.is(CinemaManager.IMAX_GT_PROJECTOR.get());
        });
        if (!ours) return;

        var ordered = snapshots.stream()
                .sorted(java.util.Comparator.comparing(snapshot -> !snapshot.getState().isAir()))
                .toList();
        level.restoringBlockSnapshots = true;
        try {
            for (var snapshot : ordered) snapshot.restore(snapshot.getFlags() | Block.UPDATE_CLIENTS);
        } finally {
            level.restoringBlockSnapshots = false;
        }
    }
}
