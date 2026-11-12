package local.cinemamanager.seats.fractional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
/** Two full-solid exit-floor lamps plus four exact structural support posts. No power-port exemption. */
final class Original368SupportFixtures {
    static boolean accepts(BlockPos pos, BlockState required, BlockState actual) {
        if ((pos.getX()==18 || pos.getX()==49) && (pos.getY()==82 && pos.getZ()==53 || pos.getY()==83 && pos.getZ()==56))
            return required.equals(Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Y)) && actual.equals(required);
        return (pos.getX()==15 || pos.getX()==52) && pos.getY()==69 && pos.getZ()==20
            && required.is(Blocks.REDSTONE_LAMP) && actual.is(Blocks.REDSTONE_LAMP)
            && required.getFluidState().isEmpty() && actual.getFluidState().isEmpty();
    }
    private Original368SupportFixtures() {}
}
