package local.cinemamanager.doors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Checks entity collision for every section before consuming an item or placing anything. */
public final class CinemaDoorItem extends BlockItem {
    public CinemaDoorItem(CinemaDoorBlock block, Properties properties) { super(block, properties); }
    @Override protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!super.canPlace(context, state) || !((CinemaDoorBlock)getBlock()).hasPlacementRoom(context)) return false;
        for (DoorPart part : DoorPart.values()) {
            BlockPos pos = context.getClickedPos().above(part.offset());
            if (!context.getLevel().isUnobstructed(state.setValue(CinemaDoorBlock.PART, part), pos,
                    context.getPlayer() == null ? CollisionContext.empty() : CollisionContext.of(context.getPlayer()))) return false;
        }
        return true;
    }
}
