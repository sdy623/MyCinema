package local.cinemamanager.theater;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Gives a newly placed MyCinema TMS an explicit owner without modifying WaterFrames. */
public final class CinemaTerminalItem extends BlockItem {
    public CinemaTerminalItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        var result = super.place(context);
        if (result.consumesAction() && !context.getLevel().isClientSide && context.getPlayer() != null
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof CinemaTerminalTile terminal)
            terminal.owner(context.getPlayer().getUUID());
        return result;
    }
}
