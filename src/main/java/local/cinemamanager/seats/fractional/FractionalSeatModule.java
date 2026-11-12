package local.cinemamanager.seats.fractional;

import local.cinemamanager.CinemaManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Actual registered collision cells. No placeable full-block item or replacement seat entity. */
public final class FractionalSeatModule {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CinemaManager.ID);
    private static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CinemaManager.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CinemaManager.ID);
    public static final DeferredItem<Item> DISPLAY_ITEM = ITEMS.register("fractional_seat_highback150", () -> new Item(new Item.Properties()));
    public static final DeferredBlock<FractionalSeatBlock> CELL = BLOCKS.register("fractional_seat_collision", () ->
        new FractionalSeatBlock(BlockBehaviour.Properties.of().strength(-1.0f, 3600000.0f)
            .sound(SoundType.WOOD).noOcclusion().noLootTable(), () -> FractionalSeatModule.TILE.get()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FractionalSeatTile>> TILE =
        TILES.register("fractional_seat_collision", () -> BlockEntityType.Builder.of(
            (pos, state) -> new FractionalSeatTile(FractionalSeatModule.TILE.get(), pos, state), CELL.get()).build(null));
    public static void register(IEventBus bus) { BLOCKS.register(bus); TILES.register(bus); ITEMS.register(bus); }
    private FractionalSeatModule() {}
}
