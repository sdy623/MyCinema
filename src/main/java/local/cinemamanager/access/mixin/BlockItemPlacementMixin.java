package local.cinemamanager.access.mixin;

import local.cinemamanager.mirror.CinemaMirrors;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures accepted real BlockItem placement after its display tile exists. */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacementMixin {
    @Inject(method = "place", at = @At("RETURN"), require = 1)
    private void mycinema$recordDisplayPlacement(BlockPlaceContext context,
                                                  CallbackInfoReturnable<InteractionResult> result) {
        if (!result.getReturnValue().consumesAction() || context.getLevel().isClientSide
                || !(context.getPlayer() instanceof ServerPlayer actor)) return;
        if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof DisplayTile tile)
            CinemaMirrors.placedBy(tile, actor);
    }
}
