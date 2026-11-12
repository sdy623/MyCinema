package local.cinemamanager.seats.fractional;
import net.minecraft.SharedConstants;import net.minecraft.server.Bootstrap;import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.world.level.block.state.properties.Property;import net.minecraft.world.phys.shapes.*;
public final class WoolSurfaceShapeChecks {
 static int n;
 static void check(boolean v){if(!v)throw new AssertionError("check "+n);n++;}
 @SuppressWarnings({"rawtypes","unchecked"}) static BlockState copy(BlockState from,BlockState to){for(Property p:from.getProperties())to=to.setValue(p,from.getValue(p));return to;}
 static void pair(Block old,Block fresh){
  check(old.getStateDefinition().getPossibleStates().size()==fresh.getStateDefinition().getPossibleStates().size());
  for(BlockState s:old.getStateDefinition().getPossibleStates()){
   BlockState t=copy(s,fresh.defaultBlockState());t.initCache();check(t.getValues().equals(s.getValues()));
   check(!Shapes.joinIsNotEmpty(s.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),t.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),BooleanOp.NOT_SAME));
   check(!Shapes.joinIsNotEmpty(s.getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),t.getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),BooleanOp.NOT_SAME));
   check(s.getFluidState().equals(t.getFluidState()));
  }
  check(old.getFriction()==fresh.getFriction());check(old.getSpeedFactor()==fresh.getSpeedFactor());check(old.getJumpFactor()==fresh.getJumpFactor());check(old.getExplosionResistance()==fresh.getExplosionResistance());
 }
 public static void main(String[]args) throws Exception {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
 // Standalone fixture only: emulate the registration window before constructing unregistered test blocks.
 var registry=net.minecraft.core.registries.BuiltInRegistries.BLOCK;
 var frozen=net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");frozen.setAccessible(true);frozen.setBoolean(registry,false);
 var pending=net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");pending.setAccessible(true);pending.set(registry,new java.util.IdentityHashMap<>());pair(Blocks.POLISHED_DEEPSLATE_SLAB,WoolSurfaceModule.slab(true));pair(Blocks.POLISHED_BLACKSTONE_SLAB,WoolSurfaceModule.slab(false));pair(Blocks.POLISHED_DEEPSLATE_STAIRS,WoolSurfaceModule.stairs(true));pair(Blocks.POLISHED_BLACKSTONE_STAIRS,WoolSurfaceModule.stairs(false));pair(Blocks.POLISHED_DEEPSLATE_STAIRS,WoolSurfaceModule.stairs(true));System.out.println("PASS "+n+" exact all-state shape/fluid/property/physics checks");}
}
