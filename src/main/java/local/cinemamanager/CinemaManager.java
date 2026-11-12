package local.cinemamanager;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.*;

@Mod(CinemaManager.ID)
public final class CinemaManager {
    public static final String ID = "mycinema";
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    public static final DeferredBlock<me.srrapero720.waterframes.common.block.DisplayBlock> TMS_TERMINAL = BLOCKS.register("tms_terminal", () -> me.srrapero720.waterframes.common.block.DisplayBlock.of(me.srrapero720.waterframes.common.block.data.DisplayCaps.TV_BOX));
    public static final DeferredBlock<me.srrapero720.waterframes.common.block.DisplayBlock> CINEMA_PROJECTOR = BLOCKS.register("cinema_projector", () -> me.srrapero720.waterframes.common.block.DisplayBlock.of(me.srrapero720.waterframes.common.block.data.DisplayCaps.PROJECTOR));
    public static final DeferredBlock<me.srrapero720.waterframes.common.block.DisplayBlock> IMAX_GT_PROJECTOR = BLOCKS.register("imax_gt_projector", () -> me.srrapero720.waterframes.common.block.DisplayBlock.of(me.srrapero720.waterframes.common.block.data.DisplayCaps.PROJECTOR));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<local.cinemamanager.theater.CinemaTerminalTile>> TMS_TERMINAL_TILE = TILES.register("tms_terminal", () -> BlockEntityType.Builder.of(local.cinemamanager.theater.CinemaTerminalTile::new,TMS_TERMINAL.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<local.cinemamanager.projection.CinemaProjectorTile>> CINEMA_PROJECTOR_TILE = TILES.register("cinema_projector", () -> BlockEntityType.Builder.of(local.cinemamanager.projection.CinemaProjectorTile::new,CINEMA_PROJECTOR.get(),IMAX_GT_PROJECTOR.get()).build(null));
    public static final DeferredItem<Item> TMS_TERMINAL_ITEM = ITEMS.register("tms_terminal", () -> new local.cinemamanager.theater.CinemaTerminalItem(TMS_TERMINAL.get(),new Item.Properties()));
    public static final DeferredItem<Item> CINEMA_PROJECTOR_ITEM = ITEMS.register("cinema_projector", () -> new local.cinemamanager.projection.CinemaProjectorItem(CINEMA_PROJECTOR.get(),local.cinemamanager.projection.ProjectorStyle.CINEMA,new Item.Properties()));
    public static final DeferredItem<Item> IMAX_GT_PROJECTOR_ITEM = ITEMS.register("imax_gt_projector", () -> new local.cinemamanager.projection.CinemaProjectorItem(IMAX_GT_PROJECTOR.get(),local.cinemamanager.projection.ProjectorStyle.IMAX_GT,new Item.Properties()));
    public static final DeferredBlock<local.cinemamanager.projection.CinemaProjectorPartBlock> PROJECTOR_PART = BLOCKS.register("cinema_projector_part", () -> new local.cinemamanager.projection.CinemaProjectorPartBlock(BlockBehaviour.Properties.of().strength(3.0f).noOcclusion().noLootTable()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<local.cinemamanager.projection.CinemaProjectorPartTile>> PROJECTOR_PART_TILE = TILES.register("cinema_projector_part", () -> BlockEntityType.Builder.of(local.cinemamanager.projection.CinemaProjectorPartTile::new,PROJECTOR_PART.get()).build(null));
    public static final DeferredBlock<local.cinemamanager.collision.CollisionCoreBlock> COLLISION_CORE = BLOCKS.register("speaker_collision_core", () -> new local.cinemamanager.collision.CollisionCoreBlock(BlockBehaviour.Properties.of().strength(3.0f).noLootTable(), () -> CinemaManager.COLLISION_CORE_TILE.get()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<local.cinemamanager.collision.CollisionCoreTile>> COLLISION_CORE_TILE = TILES.register("speaker_collision_core", () -> BlockEntityType.Builder.of((pos,state) -> new local.cinemamanager.collision.CollisionCoreTile(CinemaManager.COLLISION_CORE_TILE.get(),pos,state),COLLISION_CORE.get()).build(null));
    public static final DeferredBlock<local.cinemamanager.doors.CinemaDoorBlock> DOOR_WOOD = BLOCKS.register("cinema_door_wood", () -> new local.cinemamanager.doors.CinemaDoorBlock(BlockBehaviour.Properties.of().strength(3.0f), net.minecraft.world.level.block.state.properties.BlockSetType.DARK_OAK));
    public static final DeferredBlock<local.cinemamanager.doors.CinemaDoorBlock> DOOR_METAL = BLOCKS.register("cinema_door_metal", () -> new local.cinemamanager.doors.CinemaDoorBlock(BlockBehaviour.Properties.of().strength(5.0f), net.minecraft.world.level.block.state.properties.BlockSetType.IRON));
    public static final DeferredBlock<local.cinemamanager.advertising.AdvertisingBlock> AD_LED = BLOCKS.register("led_advertising_panel",()->new local.cinemamanager.advertising.AdvertisingBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion(),true));
    public static final DeferredBlock<local.cinemamanager.advertising.AdvertisingBlock> AD_PRINTED = BLOCKS.register("printed_sign",()->new local.cinemamanager.advertising.AdvertisingBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion(),false));
    public static final DeferredBlock<local.cinemamanager.advertising.AdvertisingPartBlock> AD_PART = BLOCKS.register("advertising_part",()->new local.cinemamanager.advertising.AdvertisingPartBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion().noLootTable()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<local.cinemamanager.advertising.AdvertisingPartTile>> AD_PART_TILE=TILES.register("advertising_part",()->BlockEntityType.Builder.of(local.cinemamanager.advertising.AdvertisingPartTile::new,AD_PART.get()).build(null));
    public static final DeferredItem<Item> AD_LED_ITEM=ITEMS.register("led_advertising_panel",()->new BlockItem(AD_LED.get(),new Item.Properties()));
    public static final DeferredItem<Item> AD_PRINTED_ITEM=ITEMS.register("printed_sign",()->new BlockItem(AD_PRINTED.get(),new Item.Properties()));
    public static final DeferredBlock<ManagerBlock> MANAGER = BLOCKS.register("manager_terminal", () -> new ManagerBlock(BlockBehaviour.Properties.of().strength(3.0f,3600000.0f)));
    public static final DeferredBlock<EndpointBlock> LIGHT = BLOCKS.register("light_output", () -> new EndpointBlock(BlockBehaviour.Properties.of().strength(2.0f), true));
    public static final DeferredBlock<EndpointBlock> ENTRY = BLOCKS.register("entry_output", () -> new EndpointBlock(BlockBehaviour.Properties.of().strength(2.0f), false));
    public static final DeferredBlock<EndpointBlock> AISLE = BLOCKS.register("aisle_output", () -> new EndpointBlock(BlockBehaviour.Properties.of().strength(2.0f), local.cinemamanager.model.ManagerState.LightGroup.AISLE));
    public static final DeferredBlock<EndpointBlock> EXIT = BLOCKS.register("exit_output", () -> new EndpointBlock(BlockBehaviour.Properties.of().strength(2.0f), local.cinemamanager.model.ManagerState.LightGroup.EXIT));
    public static final DeferredBlock<EndpointBlock> ACCENT = BLOCKS.register("accent_output", () -> new EndpointBlock(BlockBehaviour.Properties.of().strength(2.0f), local.cinemamanager.model.ManagerState.LightGroup.ACCENT));
    public static final DeferredBlock<local.cinemamanager.panels.WallInfoBlock> WALL_INFO = BLOCKS.register("wall_information_panel", () -> new local.cinemamanager.panels.WallInfoBlock(BlockBehaviour.Properties.of().strength(2.0f).noOcclusion().noCollission()));
    public static final DeferredBlock<InfoBlock> INFO = BLOCKS.register("information_panel", () -> new InfoBlock(BlockBehaviour.Properties.of().strength(2.0f)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ManagerTile>> MANAGER_TILE = TILES.register("manager_terminal", () -> BlockEntityType.Builder.of(ManagerTile::new, MANAGER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EndpointTile>> ENDPOINT_TILE = TILES.register("scene_output", () -> BlockEntityType.Builder.of(EndpointTile::new, LIGHT.get(), ENTRY.get(), AISLE.get(), EXIT.get(), ACCENT.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InfoTile>> INFO_TILE = TILES.register("information_panel", () -> BlockEntityType.Builder.of(InfoTile::new, INFO.get(), WALL_INFO.get(), AD_LED.get(), AD_PRINTED.get()).build(null));
    public static final DeferredItem<Item> DOOR_WOOD_ITEM = ITEMS.register("cinema_door_wood", () -> new local.cinemamanager.doors.CinemaDoorItem(DOOR_WOOD.get(),new Item.Properties()));
    public static final DeferredItem<Item> DOOR_METAL_ITEM = ITEMS.register("cinema_door_metal", () -> new local.cinemamanager.doors.CinemaDoorItem(DOOR_METAL.get(),new Item.Properties()));
    public static final DeferredItem<Item> MANAGER_ITEM = ITEMS.register("manager_terminal", () -> new BlockItem(MANAGER.get(), new Item.Properties()));
    public static final DeferredItem<Item> LIGHT_ITEM = ITEMS.register("light_output", () -> new BlockItem(LIGHT.get(), new Item.Properties()));
    public static final DeferredItem<Item> ENTRY_ITEM = ITEMS.register("entry_output", () -> new BlockItem(ENTRY.get(), new Item.Properties()));
    public static final DeferredItem<Item> AISLE_ITEM = ITEMS.register("aisle_output", () -> new BlockItem(AISLE.get(), new Item.Properties()));
    public static final DeferredItem<Item> EXIT_ITEM = ITEMS.register("exit_output", () -> new BlockItem(EXIT.get(), new Item.Properties()));
    public static final DeferredItem<Item> ACCENT_ITEM = ITEMS.register("accent_output", () -> new BlockItem(ACCENT.get(), new Item.Properties()));
    public static final DeferredItem<Item> INFO_ITEM = ITEMS.register("information_panel", () -> new local.cinemamanager.panels.InformationPanelItem(INFO.get(), WALL_INFO, new Item.Properties()));
    public static final DeferredItem<Item> LINKER = ITEMS.register("linking_card", () -> new LinkingCard(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> TABLET = ITEMS.register("manager_tablet", () -> new TabletItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> DISPLAY_LINKER=ITEMS.register("display_linking_card",()->new local.cinemamanager.advertising.DisplayLinkingCard(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<Item> MANUAL = ITEMS.register("manual", () -> new ManualItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("cinema", () -> CreativeModeTab.builder()
            .title(Component.literal("MyCinema")).icon(() -> new ItemStack(MANAGER_ITEM.get()))
            .displayItems((params, output) -> { local.cinemamanager.access.AccessModule.addCreative(output); output.accept(local.cinemamanager.seats.SeatsModule.BINDER);output.accept(local.cinemamanager.seats.SeatsModule.GUIDE_ITEM); output.accept(MANUAL); output.accept(MANAGER_ITEM); output.accept(TMS_TERMINAL_ITEM); output.accept(CINEMA_PROJECTOR_ITEM); output.accept(IMAX_GT_PROJECTOR_ITEM); output.accept(INFO_ITEM); output.accept(AD_LED_ITEM); output.accept(AD_PRINTED_ITEM); output.accept(DISPLAY_LINKER); output.accept(LIGHT_ITEM); output.accept(AISLE_ITEM); output.accept(EXIT_ITEM); output.accept(ACCENT_ITEM); output.accept(ENTRY_ITEM); output.accept(LINKER); output.accept(TABLET); output.accept(DOOR_WOOD_ITEM); output.accept(DOOR_METAL_ITEM); }).build());
    public CinemaManager(IEventBus bus, net.neoforged.fml.ModContainer container) { local.cinemamanager.access.AccessModule.register(bus);local.cinemamanager.seats.SeatsModule.register(bus); net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(local.cinemamanager.mirror.CinemaMirrors::onBlockPlaced);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,local.cinemamanager.projection.ProjectorStructure::onBreak);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,true,local.cinemamanager.projection.ProjectorStructure::onCancelledPlace); container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, local.cinemamanager.client.PosterResources.SPEC);container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,local.cinemamanager.advertising.DisplayLimits.SPEC,"mycinema-displays-server.toml");container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,local.cinemamanager.policy.CinemaPermissions.SPEC,"mycinema-permissions-server.toml");local.cinemamanager.panels.PanelGroup.configureLimits(local.cinemamanager.advertising.DisplayLimits::panelSide,local.cinemamanager.advertising.DisplayLimits::panelArea); container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,local.cinemamanager.panels.assets.AssetLimits.SPEC,"mycinema-assets-server.toml"); BLOCKS.register(bus); ITEMS.register(bus); TILES.register(bus); TABS.register(bus); bus.addListener(ManagerNetwork::register); }
}
