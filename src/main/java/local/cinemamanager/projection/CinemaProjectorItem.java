package local.cinemamanager.projection;

import java.util.List;
import java.util.Map;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

public final class CinemaProjectorItem extends BlockItem {
    private static final String STYLE = "mycinema_projector_style";
    private final ProjectorStyle projectorStyle;
    public CinemaProjectorItem(Block block, ProjectorStyle projectorStyle, Item.Properties properties) {
        super(block, properties);
        this.projectorStyle = projectorStyle;
    }
    public static ProjectorStyle style(ItemStack stack) {
        if (stack == null) return ProjectorStyle.CINEMA;
        if (stack.getItem() instanceof CinemaProjectorItem projector) return projector.projectorStyle;
        // Read stacks created by the pre-release single-ID candidate without
        // carrying that representation into the public registry contract.
        return ProjectorStyle.parse(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString(STYLE));
    }
    public static ItemStack stack(ProjectorStyle style) {
        Item target = style == ProjectorStyle.IMAX_GT ? CinemaManager.IMAX_GT_PROJECTOR_ITEM.get() : CinemaManager.CINEMA_PROJECTOR_ITEM.get();
        return new ItemStack(target);
    }
    public static List<ProjectorStyle> creativeStyles() {
        return List.of(ProjectorStyle.CINEMA, ProjectorStyle.IMAX_GT);
    }
    public static String translationKey(ProjectorStyle style) {
        return switch (style) {
            case STANDARD -> "item.mycinema.standard_projector";
            case CINEMA -> "item.mycinema.cinema_projector";
            case IMAX_GT -> "item.mycinema.imax_gt_projector";
        };
    }
    @Override public Component getName(ItemStack stack) {
        return Component.translatable(translationKey(style(stack)));
    }
    @Override public InteractionResult place(BlockPlaceContext context) {
        ProjectorStyle style = projectorStyle;
        if (context.getClickedFace() != Direction.UP) return InteractionResult.FAIL;
        Direction facing = context.getHorizontalDirection();
        BlockPos anchor = context.getClickedPos();
        Map<BlockPos, net.minecraft.world.phys.shapes.VoxelShape> cells = ProjectorGeometry.cells(style, facing);
        for (Map.Entry<BlockPos, net.minecraft.world.phys.shapes.VoxelShape> entry : cells.entrySet()) {
            BlockPos offset = entry.getKey();
            BlockPos pos = anchor.offset(offset);
            if (context.getLevel().isOutsideBuildHeight(pos) || !context.getLevel().getWorldBorder().isWithinBounds(pos)
                    || !context.getLevel().hasChunkAt(pos) || !context.getLevel().getBlockState(pos).canBeReplaced()) return InteractionResult.FAIL;
            if (ProjectorGeometry.needsFloor(offset, entry.getValue())) {
                BlockPos floor = pos.below();
                if (!context.getLevel().getBlockState(floor).isFaceSturdy(context.getLevel(), floor, Direction.UP)) return InteractionResult.FAIL;
            }
            for (AABB local : entry.getValue().toAabbs())
                if (!context.getLevel().getEntities(null, local.move(pos)).isEmpty()) return InteractionResult.FAIL;
        }
        InteractionResult placed = super.place(context);
        if (!placed.consumesAction()) return placed;
        if (!(context.getLevel().getBlockEntity(anchor) instanceof CinemaProjectorTile projector)) return InteractionResult.FAIL;
        projector.initializeStyle(style, facing);
        if (!context.getLevel().isClientSide && context.getPlayer() != null)
            projector.owner(context.getPlayer().getUUID());
        for (BlockPos offset : cells.keySet()) if (!offset.equals(BlockPos.ZERO)) {
            BlockPos pos = anchor.offset(offset);
            context.getLevel().setBlock(pos, CinemaManager.PROJECTOR_PART.get().defaultBlockState(), 3);
            if (context.getLevel().getBlockEntity(pos) instanceof CinemaProjectorPartTile part) part.bind(anchor, projector.identity());
        }
        return placed;
    }
}
