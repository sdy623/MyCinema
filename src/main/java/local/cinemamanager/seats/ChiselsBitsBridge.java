package local.cinemamanager.seats;

import java.lang.reflect.*;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Optional 21.1.32 public-API bridge. No C&B classes are linked or initialized when absent. */
public final class ChiselsBitsBridge {
    public static final String SUPPORTED_VERSION="21.1.32";
    private static final String API="mod.chiselsandbits.api.";
    public static String availability(){
        var container=ModList.get().getModContainerById("chiselsandbits");
        if(container.isEmpty())return "mycinema.seats.bits_missing";
        return SUPPORTED_VERSION.equals(container.get().getModInfo().getVersion().toString())?"":"mycinema.seats.bits_version";
    }
    /** Builds an inventory item only. Never obtains a Level or in-world mutator. */
    public static ItemStack create(ServerPlayer actor,GuidePreset preset){
        if(!actor.isCreative()||actor.isSpectator())throw SeatRegistry.error("bits_creative");String problem=availability();if(!problem.isEmpty())throw new IllegalArgumentException(problem);
        try{return createUsingApi(preset);}catch(ReflectiveOperationException|LinkageError failure){throw SeatRegistry.error("bits_api");}
    }
    static ItemStack createUsingApi(GuidePreset preset) throws ReflectiveOperationException {
        boolean[][] pixels=BitTagRaster.raster(preset); // Reject oversized combined ranges before optional API work.
        Class<?> api=Class.forName(API+"IChiselsAndBitsAPI");Object instance=api.getMethod("getInstance").invoke(null);if(instance==null)throw SeatRegistry.error("bits_api");
        Object size=api.getMethod("getStateEntrySize").invoke(instance);
        int resolution=(int)Class.forName(API+"multistate.StateEntrySize").getMethod("getBitsPerBlockSide").invoke(size);
        requireResolution(resolution);
        Class<?> material=Class.forName(API+"blockinformation.BlockInformation");Constructor<?> constructor=material.getConstructor(BlockState.class,Optional.class);
        Object dark=constructor.newInstance(Blocks.BLACK_CONCRETE.defaultBlockState(),Optional.empty());Object white=constructor.newInstance(Blocks.WHITE_CONCRETE.defaultBlockState(),Optional.empty());
        Object factory=api.getMethod("getSnapshotFactory").invoke(instance);
        Object snapshot=Class.forName(API+"multistate.snapshot.ISnapshotFactory").getMethod("singleBlock",material).invoke(factory,material.getField("AIR").get(null));
        Method set=Class.forName(API+"multistate.mutator.IAreaMutator").getMethod("setInAreaTarget",material,Vec3.class);
        // The sheet backs onto the block's south edge. Its readable front faces north:
        // from the north, looking south (yaw 0), screen-right is decreasing world X.
        // C&B 21.1.32 copies these coordinates unchanged into its item and on placement.
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)set.invoke(snapshot,pixels[y][x]?white:dark,new Vec3((15-x+.5)/16.0,(15-y+.5)/16.0,15.5/16.0));
        Object multi=Class.forName(API+"multistate.snapshot.IMultiStateSnapshot").getMethod("toItemStack").invoke(snapshot);
        Object result=Class.forName(API+"item.multistate.IMultiStateItemStack").getMethod("toBlockStack").invoke(multi);
        if(!(result instanceof ItemStack stack)||stack.isEmpty())throw SeatRegistry.error("bits_api");
        stack.setCount(1);stack.set(DataComponents.CUSTOM_NAME,Component.literal((preset.hall()+" "+preset.zone()).strip()+" · "+preset.displayLabel()+" "+preset.arrowText()));return stack;
    }
    static void requireResolution(int resolution){if(resolution!=16)throw SeatRegistry.error("bits_resolution");}
    private ChiselsBitsBridge(){}
}
