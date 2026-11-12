package local.cinemamanager.access.mixin;

import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.TerminalNetwork;
import me.srrapero720.waterframes.common.network.ControlPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Refuses every official WaterFrames control packet for a TMS-linked member. */
@Mixin(targets = "me.srrapero720.waterframes.common.network.DisplayNetwork", remap = false)
public abstract class DisplayNetworkGuardMixin {
    @Inject(method = "openScreen", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mycinema$guardLinkedProjectorScreen(ServerPlayer actor, BlockPos pos, boolean remote,
                                                             CallbackInfo ci) {
        if (!actor.level().hasChunkAt(pos)) return;
        if (actor.level().getBlockEntity(pos) instanceof CinemaTerminalTile terminal) {
            TerminalNetwork.open(actor, terminal);
            ci.cancel();
            return;
        }
        if (actor.level().getBlockEntity(pos) instanceof CinemaProjectorTile projector
                && projector.directControlsLocked()) {
            actor.displayClientMessage(Component.translatable("mycinema.projector.linked_controls_locked"), true);
            ci.cancel();
        }
    }

    @Inject(method = "onControl", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private static void mycinema$guardLinkedProjector(ControlPacket packet, IPayloadContext context, CallbackInfo ci) {
        if (!(context.player() instanceof ServerPlayer actor) || !actor.level().hasChunkAt(packet.pos())) return;
        if (actor.level().getBlockEntity(packet.pos()) instanceof CinemaProjectorTile projector
                && projector.directControlsLocked()) ci.cancel();
    }
}
