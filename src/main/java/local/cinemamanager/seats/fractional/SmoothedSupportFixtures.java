package local.cinemamanager.seats.fractional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
/** Four protected authored full-cube fixtures under eight seat contact footprints; never an arbitrary-solid exemption. */
final class SmoothedSupportFixtures {
 static boolean accepts(boolean legacyExposedPorts,BlockPos p,BlockState expected,BlockState actual){
  if(p.getX()!=15&&p.getX()!=52)return false;
  if(p.getY()==73&&p.getZ()==32&&expected.equals(Blocks.REDSTONE_LAMP.defaultBlockState()))
   return actual.equals(Blocks.REDSTONE_LAMP.defaultBlockState())||actual.equals(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT,true));
  if(legacyExposedPorts&&p.getY()==80&&p.getZ()==49&&expected.equals(Blocks.BLACK_WOOL.defaultBlockState()))
   return actual.equals(Blocks.BLACK_WOOL.defaultBlockState())||actual.equals(Blocks.REDSTONE_BLOCK.defaultBlockState());
  return false;
 }
 private SmoothedSupportFixtures(){}
}
