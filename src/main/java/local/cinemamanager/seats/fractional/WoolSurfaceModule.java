package local.cinemamanager.seats.fractional;

import local.cinemamanager.CinemaManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Four dedicated wool-finish surfaces, with the replaced vanilla block's exact physical properties. */
@EventBusSubscriber(modid = CinemaManager.ID, bus = EventBusSubscriber.Bus.MOD)
public final class WoolSurfaceModule {
    public static SlabBlock GRAY_SLAB, BLACK_SLAB;
    public static WoolHalfDepthBlock GRAY_HALF_DEPTH, BLACK_HALF_DEPTH;
    public static StairBlock GRAY_STAIRS, BLACK_STAIRS, BLACK_DEEPSLATE_STAIRS;
    static SlabBlock slab(boolean gray) { return new SlabBlock(BlockBehaviour.Properties.ofFullCopy(gray ? Blocks.POLISHED_DEEPSLATE_SLAB : Blocks.POLISHED_BLACKSTONE_SLAB)); }
    static StairBlock stairs(boolean gray) { return new WoolStairBlock((gray ? Blocks.POLISHED_DEEPSLATE : Blocks.POLISHED_BLACKSTONE).defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(gray ? Blocks.POLISHED_DEEPSLATE_STAIRS : Blocks.POLISHED_BLACKSTONE_STAIRS)); }
    private static final class WoolStairBlock extends StairBlock { WoolStairBlock(BlockState base, BlockBehaviour.Properties p) { super(base,p); } }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,name); }
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            helper.register(id("gray_wool_half_depth_slab"), GRAY_HALF_DEPTH=new WoolHalfDepthBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_DEEPSLATE_SLAB)));
            helper.register(id("black_wool_half_depth_slab"), BLACK_HALF_DEPTH=new WoolHalfDepthBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_BLACKSTONE_SLAB)));
            helper.register(id("gray_wool_slab"), GRAY_SLAB=slab(true));
            helper.register(id("black_wool_slab"), BLACK_SLAB=slab(false));
            helper.register(id("gray_wool_stairs"), GRAY_STAIRS=stairs(true));
            helper.register(id("black_wool_stairs"), BLACK_STAIRS=stairs(false));
            helper.register(id("black_wool_deepslate_stairs"), BLACK_DEEPSLATE_STAIRS=stairs(true));
        });
        event.register(Registries.ITEM, helper -> {
            helper.register(id("gray_wool_half_depth_slab"), new BlockItem(GRAY_HALF_DEPTH,new Item.Properties()));
            helper.register(id("black_wool_half_depth_slab"), new BlockItem(BLACK_HALF_DEPTH,new Item.Properties()));
            helper.register(id("gray_wool_slab"), new BlockItem(GRAY_SLAB,new Item.Properties()));
            helper.register(id("black_wool_slab"), new BlockItem(BLACK_SLAB,new Item.Properties()));
            helper.register(id("gray_wool_stairs"), new BlockItem(GRAY_STAIRS,new Item.Properties()));
            helper.register(id("black_wool_stairs"), new BlockItem(BLACK_STAIRS,new Item.Properties()));
            helper.register(id("black_wool_deepslate_stairs"), new BlockItem(BLACK_DEEPSLATE_STAIRS,new Item.Properties()));
        });
    }
    private WoolSurfaceModule() {}
}
