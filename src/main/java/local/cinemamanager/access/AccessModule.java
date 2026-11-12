package local.cinemamanager.access;

import local.cinemamanager.CinemaManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** Call register once from the mod constructor and addCreative from its creative-tab callback. */
public final class AccessModule {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(CinemaManager.ID);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(CinemaManager.ID);
    private static final DeferredRegister<BlockEntityType<?>> TILES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,CinemaManager.ID);
    public static final DeferredBlock<StaffReaderBlock> READER=BLOCKS.register("staff_reader",()->new StaffReaderBlock(BlockBehaviour.Properties.of().strength(3)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<StaffReaderTile>> READER_TILE=TILES.register("staff_reader",()->BlockEntityType.Builder.of(StaffReaderTile::new,READER.get()).build(null));
    public static final DeferredItem<Item> READER_ITEM=ITEMS.register("staff_reader",()->new BlockItem(READER.get(),new Item.Properties()));
    public static final DeferredItem<StaffCardItem> CARD=ITEMS.register("staff_card",()->new StaffCardItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<AccessPairingCard> PAIRING_CARD=ITEMS.register("access_pairing_card",()->new AccessPairingCard(new Item.Properties().stacksTo(1)));
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);TILES.register(bus);}
    public static void addCreative(CreativeModeTab.Output output){output.accept(READER_ITEM);output.accept(CARD);output.accept(PAIRING_CARD);}
    private AccessModule(){}
}
