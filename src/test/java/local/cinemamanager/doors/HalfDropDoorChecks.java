package local.cinemamanager.doors;
import java.lang.reflect.*;import java.util.*;
import net.minecraft.SharedConstants;import net.minecraft.server.Bootstrap;import net.minecraft.core.*;
import net.minecraft.world.level.*;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.world.phys.*;import net.minecraft.world.phys.shapes.*;
public final class HalfDropDoorChecks {
 static int n;static void check(boolean v,String why){n++;if(!v)throw new AssertionError(n+" "+why);}
 static Map<BlockPos,BlockState> cells=new HashMap<>();
 static Object mock(Object p,Method m,Object[]a)throws Throwable {return switch(m.getName()){
 case "getBlockState" -> cells.getOrDefault(a[0],Blocks.AIR.defaultBlockState());
 case "getFluidState" -> cells.getOrDefault(a[0],Blocks.AIR.defaultBlockState()).getFluidState();
 case "getHeight" -> 384; case "getMinBuildHeight" -> -64;case "hasChunkAt","hasChunk","hasChunksAt" -> true;
 case "getChunkForCollisions" -> p;case "isOutsideBuildHeight" -> false;case "getEntityCollisions" -> List.of();case "getBlockEntity" -> null;
 default -> {if(m.isDefault())yield InvocationHandler.invokeDefault(p,m,a);throw new UnsupportedOperationException(m.toString());}
 };}
 static LevelReader world=(LevelReader)Proxy.newProxyInstance(HalfDropDoorChecks.class.getClassLoader(),new Class[]{LevelReader.class,CollisionGetter.class},HalfDropDoorChecks::mock);
 public static void main(String[]args)throws Exception {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
 var registry=net.minecraft.core.registries.BuiltInRegistries.BLOCK;var frozen=net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");frozen.setAccessible(true);frozen.setBoolean(registry,false);var pending=net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");pending.setAccessible(true);pending.set(registry,new IdentityHashMap<>());
 var door=new CinemaDoorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_DOOR),BlockSetType.IRON);BlockPos base=new BlockPos(0,77,0);net.minecraft.core.Registry.register(registry,net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test","half_drop_door"),door);
 check(!door.defaultBlockState().getValue(CinemaDoorBlock.HALF_DROP),"legacy default false");
 for(BlockState s:door.getStateDefinition().getPossibleStates()){
  var f=s.getValue(CinemaDoorBlock.FACING);var h=s.getValue(CinemaDoorBlock.HINGE);var part=s.getValue(CinemaDoorBlock.PART);boolean half=s.getValue(CinemaDoorBlock.HALF_DROP),open=s.getValue(CinemaDoorBlock.OPEN);
  var tag=net.minecraft.nbt.NbtUtils.writeBlockState(s);
  check(net.minecraft.nbt.NbtUtils.readBlockState(registry.asLookup(),tag).equals(s),"native NBT roundtrip every state");
  if(!half){tag.getCompound("Properties").remove("half_drop");check(net.minecraft.nbt.NbtUtils.readBlockState(registry.asLookup(),tag).equals(s),"old NBT absent property defaults false");}
  VoxelShape shape=s.getCollisionShape(world,base);AABB b=shape.bounds();
  check(b.minY==(half&&part==DoorPart.LOWER?-.5:0)&&b.maxY==1,"exact vertical span");check(shape.toAabbs().size()==1,"solid panel");check(s.hasLargeCollisionShape(),"native broadphase enabled");
  check(!Shapes.joinIsNotEmpty(s.getShape(world,base),shape,BooleanOp.NOT_SAME),"outline collision equality");
  if(!half)check(!Shapes.joinIsNotEmpty(shape,DoorGeometry.shape(f,h,open),BooleanOp.NOT_SAME),"legacy geometry exact");
  if(half&&part==DoorPart.LOWER){cells.clear();cells.put(base,s);AABB tiny=new AABB(b.minX+.01,76.55,b.minZ+.01,b.maxX-.01,76.95,b.maxZ-.01);var it=new BlockCollisions<VoxelShape>((CollisionGetter)world,null,tiny,false,(p,v)->v);check(it.hasNext(),"native BlockCollisions discovers panel wholly below own cell");}
 }
 for(var support:List.of(Blocks.STONE.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP),Blocks.STONE_STAIRS.defaultBlockState(),Blocks.AIR.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED,true))){
  cells.clear();cells.put(base.below(),support);boolean isHalf=support.is(Blocks.STONE_SLAB)&&support.getValue(SlabBlock.TYPE)==SlabType.BOTTOM&&!support.getValue(SlabBlock.WATERLOGGED);
  check(CinemaDoorBlock.isHalfDropSupport(world,base.below())==isHalf,"support exactness");
  for(boolean half:new boolean[]{false,true}){BlockState lower=door.defaultBlockState().setValue(CinemaDoorBlock.HALF_DROP,half);for(var part:DoorPart.values())cells.put(base.above(part.offset()),lower.setValue(CinemaDoorBlock.PART,part));check(door.isComplete(world,base)==(half?isHalf:support.isFaceSturdy(world,base.below(),Direction.UP)),"native structural support");}
 }
 cells.clear();cells.put(base.below(),Blocks.STONE_SLAB.defaultBlockState());var lower=door.defaultBlockState().setValue(CinemaDoorBlock.HALF_DROP,true);for(var part:DoorPart.values())cells.put(base.above(part.offset()),lower.setValue(CinemaDoorBlock.PART,part));check(door.isComplete(world,base),"half assembly complete");cells.put(base.above(),lower.setValue(CinemaDoorBlock.PART,DoorPart.MIDDLE).setValue(CinemaDoorBlock.HALF_DROP,false));check(!door.isComplete(world,base),"mixed heights refused");
 var partner=CinemaDoorBlock.class.getDeclaredMethod("partner",LevelReader.class,BlockPos.class);partner.setAccessible(true);
 for(boolean half:new boolean[]{false,true})for(boolean otherHalf:new boolean[]{false,true})for(var facing:Direction.Plane.HORIZONTAL){
  cells.clear();var l=door.defaultBlockState().setValue(CinemaDoorBlock.HALF_DROP,half).setValue(CinemaDoorBlock.FACING,facing);BlockPos other=base.relative(DoorGeometry.partnerDirection(facing,DoorHingeSide.LEFT));var r=l.setValue(CinemaDoorBlock.HINGE,DoorHingeSide.RIGHT).setValue(CinemaDoorBlock.HALF_DROP,otherHalf);
  cells.put(base.below(),half?Blocks.STONE_SLAB.defaultBlockState():Blocks.STONE.defaultBlockState());cells.put(other.below(),otherHalf?Blocks.STONE_SLAB.defaultBlockState():Blocks.STONE.defaultBlockState());
  for(var part:DoorPart.values()){cells.put(base.above(part.offset()),l.setValue(CinemaDoorBlock.PART,part));cells.put(other.above(part.offset()),r.setValue(CinemaDoorBlock.PART,part));}
  check((partner.invoke(door,world,base)!=null)==(half==otherHalf),"pair equal elevation only");
 }
 System.out.println("PASS "+n+" native state/shape/broadphase/support/legacy checks");
 }
}
