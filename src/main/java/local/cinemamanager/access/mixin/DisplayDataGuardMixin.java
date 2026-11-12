package local.cinemamanager.access.mixin;

import local.cinemamanager.projection.CinemaProjectorTile;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refuses official WaterFrames data packets while a projector belongs to a MyCinema TMS. */
@Mixin(targets = "me.srrapero720.waterframes.common.block.data.DisplayData", remap = false)
public abstract class DisplayDataGuardMixin {
    @Inject(method = {"sync", "syncList"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void mycinema$guardLinkedProjector(DisplayTile tile, Player actor, CompoundTag tag, CallbackInfo ci) {
        if (tile instanceof CinemaProjectorTile projector && projector.directControlsLocked()) ci.cancel();
    }
}
