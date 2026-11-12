package local.cinemamanager.seats;

import local.cinemamanager.CinemaManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** A single constructor hook registers the optional seat-management tools. */
public final class SeatsModule {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(CinemaManager.ID);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(CinemaManager.ID);
    private static final DeferredRegister<BlockEntityType<?>> TILES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,CinemaManager.ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES=DeferredRegister.create(Registries.ENTITY_TYPE,CinemaManager.ID);
    public static final DeferredBlock<CinemaSeatBlock> CHAIR=BLOCKS.register("cinema_seat",()->new CinemaSeatBlock(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredBlock<CinemaSeatBlock> DOUBLE_SUPPORT_CHAIR=BLOCKS.register("cinema_seat_double_support",()->new CinemaSeatBlock(BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.WOOD).noOcclusion(),true));
    public static final DeferredItem<Item> DOUBLE_SUPPORT_CHAIR_ITEM=ITEMS.register("cinema_seat_double_support",()->new BlockItem(DOUBLE_SUPPORT_CHAIR.get(),new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<CinemaSeatTile>> CHAIR_TILE=TILES.register("cinema_seat",()->BlockEntityType.Builder.of(CinemaSeatTile::new,CHAIR.get(),DOUBLE_SUPPORT_CHAIR.get()).build(null));
    public static final DeferredItem<Item> CHAIR_ITEM=ITEMS.register("cinema_seat",()->new BlockItem(CHAIR.get(),new Item.Properties()));
    public static final DeferredHolder<EntityType<?>,EntityType<CinemaSeatMount>> CHAIR_MOUNT=ENTITIES.register("cinema_seat_mount",()->EntityType.Builder.<CinemaSeatMount>of(CinemaSeatMount::new,MobCategory.MISC).sized(.01f,.01f).noSave().noSummon().clientTrackingRange(8).updateInterval(20).build(CinemaManager.ID+":cinema_seat_mount"));
    public static final DeferredBlock<SeatGuideBlock> GUIDE=BLOCKS.register("seat_guide",()->new SeatGuideBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion().noCollission()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<SeatGuideTile>> GUIDE_TILE=TILES.register("seat_guide",()->BlockEntityType.Builder.of(SeatGuideTile::new,GUIDE.get()).build(null));
    public static final DeferredItem<Item> GUIDE_ITEM=ITEMS.register("seat_guide",()->new BlockItem(GUIDE.get(),new Item.Properties()));
    public static final DeferredItem<Item> BINDER=ITEMS.register("seat_binding_card",()->new SeatBindingItem(new Item.Properties().stacksTo(1)));
    public static void register(IEventBus bus){local.cinemamanager.seats.fractional.FractionalSeatModule.register(bus);BLOCKS.register(bus);ITEMS.register(bus);TILES.register(bus);ENTITIES.register(bus);bus.addListener(SeatNetwork::register);bus.addListener(local.cinemamanager.seats.map.SeatMapNetwork::register);bus.addListener(SeatsModule::creative);}
    private static void creative(BuildCreativeModeTabContentsEvent event){if(event.getTab()==CinemaManager.TAB.get()){event.accept(CHAIR_ITEM.get());event.accept(DOUBLE_SUPPORT_CHAIR_ITEM.get());}}
    private SeatsModule(){}
}
