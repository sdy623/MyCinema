package local.cinemamanager;

import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import me.srrapero720.waterframes.common.block.DisplayBlock;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Public dependency boundary for display registrations used by MyCinema.
 *
 * <p>MyCinema-owned terminal/projector registrations live in {@link CinemaManager};
 * unmodified WaterFrames registrations are re-exported from its public entry point.
 * Keeping this facade in our namespace prevents source code from depending on the
 * former private fork's {@code DisplaysRegistry} class.</p>
 */
public final class CinemaDisplays {
    private CinemaDisplays() {}

    public static final DeferredBlock<DisplayBlock> TMS_TERMINAL = CinemaManager.TMS_TERMINAL;
    public static final DeferredBlock<DisplayBlock> CINEMA_PROJECTOR = CinemaManager.CINEMA_PROJECTOR;
    public static final DeferredBlock<DisplayBlock> IMAX_GT_PROJECTOR = CinemaManager.IMAX_GT_PROJECTOR;
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CinemaTerminalTile>> TILE_TMS_TERMINAL = CinemaManager.TMS_TERMINAL_TILE;
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CinemaProjectorTile>> TILE_CINEMA_PROJECTOR = CinemaManager.CINEMA_PROJECTOR_TILE;
    public static final DeferredItem<Item> TMS_TERMINAL_ITEM = CinemaManager.TMS_TERMINAL_ITEM;
    public static final DeferredItem<Item> CINEMA_PROJECTOR_ITEM = CinemaManager.CINEMA_PROJECTOR_ITEM;
    public static final DeferredItem<Item> IMAX_GT_PROJECTOR_ITEM = CinemaManager.IMAX_GT_PROJECTOR_ITEM;

    public static final DeferredBlock<DisplayBlock> FRAME = me.srrapero720.waterframes.WaterFrames.FRAME;
    public static final DeferredBlock<DisplayBlock> PROJECTOR = me.srrapero720.waterframes.WaterFrames.PROJECTOR;
    public static final DeferredBlock<DisplayBlock> TV = me.srrapero720.waterframes.WaterFrames.TV;
    public static final DeferredBlock<DisplayBlock> BIG_TV = me.srrapero720.waterframes.WaterFrames.BIG_TV;
    public static final DeferredBlock<DisplayBlock> TV_BOX = me.srrapero720.waterframes.WaterFrames.TV_BOX;
    public static final DeferredItem<Item> FRAME_ITEM = me.srrapero720.waterframes.WaterFrames.FRAME_ITEM;
    public static final DeferredItem<Item> PROJECTOR_ITEM = me.srrapero720.waterframes.WaterFrames.PROJECTOR_ITEM;
    public static final DeferredItem<Item> TV_ITEM = me.srrapero720.waterframes.WaterFrames.TV_ITEM;
    public static final DeferredItem<Item> BIG_TV_ITEM = me.srrapero720.waterframes.WaterFrames.BIG_TV_ITEM;
    public static final DeferredItem<Item> TV_BOX_ITEM = me.srrapero720.waterframes.WaterFrames.TV_BOX_ITEM;
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<me.srrapero720.waterframes.common.block.entity.DisplayTile>> TILE_TV_BOX = me.srrapero720.waterframes.WaterFrames.TILE_TV_BOX;
}
