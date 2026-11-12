package local.cinemamanager.seats.fractional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
public final class FractionalSeatFloorMaterialChecks {
 static int n;
 static void check(boolean yes){if(!yes)throw new AssertionError();n++;}
 public static void main(String[] args){
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  var old=FractionalSeatFloorPalette.Profile.LEGACY;var wool=FractionalSeatFloorPalette.Profile.WOOL_DEEPSLATE_V1;
  check(old.fullSupport().equals(Blocks.GRAY_CONCRETE.defaultBlockState()));
  check(wool.fullSupport().equals(Blocks.GRAY_WOOL.defaultBlockState()));
  check(old.slabSupport().equals(FractionalSeatHost.ANDESITE_BOTTOM_SLAB));
  check(wool.slabSupport().equals(FractionalSeatHost.BOTTOM_SLAB));
  check(!old.slabSupport().equals(wool.slabSupport()));
  check(!wool.fullSupport().equals(Blocks.BLACK_WOOL.defaultBlockState()));
  check(!wool.slabSupport().equals(Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP)));
  check(!wool.slabSupport().equals(Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE)));
  check(!wool.slabSupport().equals(FractionalSeatHost.BOTTOM_SLAB.setValue(SlabBlock.WATERLOGGED,true)));
  check(!wool.slabSupport().equals(Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState()));
  check(FractionalSeatHost.allowed(wool.slabSupport()));check(FractionalSeatHost.allowed(old.slabSupport()));
  System.out.println("PASS "+n+" legacy/wool exact-material and corrupt-host rejection checks");
 }
}
