package local.cinemamanager.access.mixin;
import local.cinemamanager.access.FixedStaffDoorLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
/** Required native pre-close argument guard. The original vanilla write executes once with natural POWERED intact. */
@Mixin(value=DoorBlock.class,remap=false)
public abstract class FixedIronDoorPowerMixin {
 @ModifyArgs(method="neighborChanged",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),require=1,remap=false)
 private void cinema$preserveOccupiedRegisteredIron(Args args,BlockState original,Level level,BlockPos pos,Block source,BlockPos from,boolean moving){
  BlockPos actual=args.get(0);BlockState requested=args.get(1);
  args.set(1,FixedStaffDoorLifecycle.guardIronPowerClose(level,actual,requested));
 }
}
