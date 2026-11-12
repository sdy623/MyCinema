package local.cinemamanager.access.mixin;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.projection.ProjectorGeometry;
import local.cinemamanager.projection.ProjectorStyle;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.TerminalGeometry;
import me.srrapero720.waterframes.common.block.DisplayBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Version-pinned creation hook for MyCinema's two official-WaterFrames-backed devices. */
@Mixin(DisplayBlock.class)
public abstract class DisplayBlockEntityMixin {
    @Inject(method = "newBlockEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void mycinema$createDevice(BlockPos pos, BlockState state, CallbackInfoReturnable<BlockEntity> result) {
        if (state.is(CinemaManager.TMS_TERMINAL.get())) result.setReturnValue(new CinemaTerminalTile(pos, state));
        else if (isProjector(state)) result.setReturnValue(new CinemaProjectorTile(pos, state));
    }

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true, require = 1)
    private void mycinema$projectorShape(BlockState state, BlockGetter level, BlockPos pos,
                                         CollisionContext context, CallbackInfoReturnable<VoxelShape> result) {
        if (state.is(CinemaManager.TMS_TERMINAL.get())) result.setReturnValue(TerminalGeometry.shape(facing(state, level, pos)));
        else if (isProjector(state)) result.setReturnValue(ProjectorGeometry.shape(style(state), facing(state, level, pos), BlockPos.ZERO));
    }

    @Inject(method = "getInteractionShape", at = @At("HEAD"), cancellable = true, require = 1)
    private void mycinema$projectorInteractionShape(BlockState state, BlockGetter level, BlockPos pos,
                                                    CallbackInfoReturnable<VoxelShape> result) {
        if (state.is(CinemaManager.TMS_TERMINAL.get())) result.setReturnValue(TerminalGeometry.shape(facing(state, level, pos)));
        else if (isProjector(state)) result.setReturnValue(ProjectorGeometry.shape(style(state), facing(state, level, pos), BlockPos.ZERO));
    }

    private static boolean isProjector(BlockState state) {
        return state.is(CinemaManager.CINEMA_PROJECTOR.get()) || state.is(CinemaManager.IMAX_GT_PROJECTOR.get());
    }

    private static ProjectorStyle style(BlockState state) {
        return state.is(CinemaManager.IMAX_GT_PROJECTOR.get()) ? ProjectorStyle.IMAX_GT : ProjectorStyle.CINEMA;
    }

    private static Direction facing(BlockState state, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CinemaProjectorTile tile) return tile.machineFacing();
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            Direction value = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            if (value.getAxis().isHorizontal()) return value;
        }
        return Direction.NORTH;
    }
}
