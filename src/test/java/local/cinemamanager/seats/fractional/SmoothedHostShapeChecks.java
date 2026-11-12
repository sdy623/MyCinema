package local.cinemamanager.seats.fractional;
import java.util.*;import net.minecraft.SharedConstants;import net.minecraft.server.Bootstrap;import net.minecraft.core.*;import net.minecraft.core.registries.BuiltInRegistries;import net.minecraft.resources.ResourceLocation;import net.minecraft.nbt.CompoundTag;import net.minecraft.world.level.EmptyBlockGetter;import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.*;import net.minecraft.world.phys.shapes.*;
public final class SmoothedHostShapeChecks {
 static int n;static void check(boolean b){if(!b)throw new AssertionError("check "+n);n++;}
 static <T extends Block>T reg(String name,T b){Registry.register(BuiltInRegistries.BLOCK,ResourceLocation.fromNamespaceAndPath("mycinema",name),b);for(var s:b.getStateDefinition().getPossibleStates())s.initCache();return b;}
 static FractionalSeatData point(int p){return FractionalSeatData.of(List.of(new FractionalSeatData.Contribution(UUID.fromString("00000000-0000-0000-0000-000000000001"),new FractionalSeatData.Anchor(0,0,0,0),new int[]{p,p+1})));}
 static void rejected(Runnable r){try{r.run();throw new AssertionError("accepted corruption");}catch(IllegalArgumentException expected){n++;}}
 public static void main(String[]args)throws Exception{
 SharedConstants.tryDetectVersion();Bootstrap.bootStrap();var registry=BuiltInRegistries.BLOCK;var f=MappedRegistry.class.getDeclaredField("frozen");f.setAccessible(true);f.setBoolean(registry,false);f=MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");f.setAccessible(true);f.set(registry,new IdentityHashMap<>());
 WoolSurfaceModule.GRAY_SLAB=reg("gray_wool_slab",WoolSurfaceModule.slab(true));WoolSurfaceModule.BLACK_SLAB=reg("black_wool_slab",WoolSurfaceModule.slab(false));WoolSurfaceModule.GRAY_STAIRS=reg("gray_wool_stairs",WoolSurfaceModule.stairs(true));WoolSurfaceModule.BLACK_STAIRS=reg("black_wool_stairs",WoolSurfaceModule.stairs(false));WoolSurfaceModule.BLACK_DEEPSLATE_STAIRS=reg("black_wool_deepslate_stairs",WoolSurfaceModule.stairs(true));
 WoolSurfaceModule.GRAY_HALF_DEPTH=reg("gray_wool_half_depth_slab",new WoolHalfDepthBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_DEEPSLATE_SLAB)));WoolSurfaceModule.BLACK_HALF_DEPTH=reg("black_wool_half_depth_slab",new WoolHalfDepthBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.POLISHED_BLACKSTONE_SLAB)));
 var cell=reg("test_fractional_cell",new FractionalSeatBlock(BlockBehaviour.Properties.of(),()->null));
 for(var m:FractionalSeatHost.Material.values()){
  var host=FractionalSeatHost.slab(m);check(FractionalSeatHost.allowed(host));var state=cell.defaultBlockState().setValue(FractionalSeatBlock.HOST_SLAB,true).setValue(FractionalSeatBlock.HOST_MATERIAL,m);check(FractionalSeatHost.matchesCellState(host,state));
  check(!Shapes.joinIsNotEmpty(host.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),FractionalSeatHost.shape(host),BooleanOp.NOT_SAME));
  check(!Shapes.joinIsNotEmpty(FractionalSeatTile.buildShape(FractionalSeatData.EMPTY,host),FractionalSeatHost.shape(host),BooleanOp.NOT_SAME));
  check(!Shapes.joinIsNotEmpty(FractionalSeatBlock.hostShape(state),FractionalSeatHost.shape(host),BooleanOp.NOT_SAME));
  var mask=FractionalSeatHost.hostVoxels(host);check(mask.cardinality()==(m.shape==0?131072:m.shape==1?196608:65536));
  rejected(()->FractionalSeatHost.validateData(point(mask.nextSetBit(0)),host));var free=point(mask.nextClearBit(0));var before=free.voxelsCopy();FractionalSeatHost.validateData(free,host);check(before.equals(free.voxelsCopy()));
  var tag=new CompoundTag();FractionalSeatHost.write(tag,host);check(FractionalSeatHost.read(tag).equals(host));tag.getCompound("fractional_host").getCompound("Properties").putString("waterlogged","true");rejected(()->FractionalSeatHost.read(tag));
 }
 check(FractionalSeatHost.allowed(Blocks.AIR.defaultBlockState()));check(!FractionalSeatHost.allowed(Blocks.GRAY_WOOL.defaultBlockState()));
 check(!FractionalSeatHost.allowed(WoolSurfaceModule.GRAY_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP)));check(!FractionalSeatHost.allowed(WoolSurfaceModule.GRAY_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE)));
 var st=WoolSurfaceModule.GRAY_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH);check(!FractionalSeatHost.allowed(st.setValue(StairBlock.FACING,Direction.NORTH)));check(!FractionalSeatHost.allowed(st.setValue(StairBlock.SHAPE,StairsShape.INNER_LEFT)));check(!FractionalSeatHost.allowed(st.setValue(StairBlock.HALF,Half.TOP)));check(!FractionalSeatHost.allowed(st.setValue(StairBlock.WATERLOGGED,true)));
 for(var d:Direction.Plane.HORIZONTAL){var hs=WoolSurfaceModule.GRAY_HALF_DEPTH.defaultBlockState().setValue(WoolHalfDepthBlock.FACING,d);check(FractionalSeatHost.allowed(hs)==(d==Direction.SOUTH));check(hs.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).bounds().getYsize()==.5);}
 for(var version:new FractionalSeatLayoutVersion.Version[]{FractionalSeatLayoutVersion.Version.Y_SMOOTH_V1,FractionalSeatLayoutVersion.Version.Y_SMOOTH_HIDDEN_PORTS_V1}){
 var layout=FractionalSeatLayout.get(version);
 for(var entry:layout.cells.entrySet()){var host=layout.expectedHost(entry.getKey());check(FractionalSeatHost.allowed(host));FractionalSeatHost.validateData(entry.getValue(),host);check(layout.supportAt(entry.getKey()).isPresent());}
 var stream=SmoothedHostShapeChecks.class.getResourceAsStream("/data/mycinema/seat_collision/"+(layout.usesHiddenPorts()?"selected150-ysmooth-hidden-ports-support.json":"selected150-ysmooth-v1-support.json"));var support=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
 for(var e:support.getAsJsonArray("hosts")){var a=e.getAsJsonObject().getAsJsonArray("pos");check(layout.supportAt(new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt())).isPresent());}
 for(boolean fixturesOn:new boolean[]{false,true})for(var seat:layout.seats.values()){
  double floor=seat.anchor().y(),x=seat.anchor().x(),z=seat.anchor().z(),halfWidth=((double).6f)/2,eps=1e-7;boolean full=floor==Math.rint(floor);int sy=(int)(full?floor-1:floor-.5);VoxelShape union=Shapes.empty();
  int minX=(int)Math.floor(x-halfWidth+eps),maxX=(int)Math.floor(x+halfWidth-eps),minZ=(int)Math.floor(z-halfWidth+eps),maxZ=(int)Math.floor(z+halfWidth-eps);
  for(int xx=minX;xx<=maxX;xx++)for(int zz=minZ;zz<=maxZ;zz++){
   var pos=new BlockPos(xx,sy,zz);var expected=layout.supportAt(pos).orElseThrow();var host=expected;
   if(fixturesOn){if(expected.is(Blocks.REDSTONE_LAMP)){var lit=expected.setValue(RedstoneLampBlock.LIT,true);if(SmoothedSupportFixtures.accepts(!layout.usesHiddenPorts(),pos,expected,lit))host=lit;}else if(SmoothedSupportFixtures.accepts(!layout.usesHiddenPorts(),pos,expected,Blocks.REDSTONE_BLOCK.defaultBlockState()))host=Blocks.REDSTONE_BLOCK.defaultBlockState();}
   check(host.equals(expected)&&(host.equals(Blocks.GRAY_WOOL.defaultBlockState())||FractionalSeatHost.hasSolidHost(host))||SmoothedSupportFixtures.accepts(!layout.usesHiddenPorts(),pos,expected,host));
   var data=layout.cells.get(pos);if(data!=null)check(data.owner(seat.uid()).isPresent());
   var shape=FractionalSeatHost.hasSolidHost(host)?FractionalSeatHost.shape(host):host.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);union=Shapes.or(union,shape.move(xx,sy,zz));
  }
  var contact=Shapes.box(x-halfWidth+eps,floor-.145+eps,z-halfWidth+eps,x+halfWidth-eps,floor,z+halfWidth-eps);
  if(Shapes.joinIsNotEmpty(contact,union,BooleanOp.ONLY_FIRST))throw new AssertionError("Support gap "+seat.label());check(true);
 }
 }
 for(int xx:new int[]{15,52}){
  var lamp=Blocks.REDSTONE_LAMP.defaultBlockState();var lit=lamp.setValue(RedstoneLampBlock.LIT,true);var lp=new BlockPos(xx,73,32);check(SmoothedSupportFixtures.accepts(true,lp,lamp,lamp));check(SmoothedSupportFixtures.accepts(true,lp,lamp,lit));check(!SmoothedSupportFixtures.accepts(true,lp.above(),lamp,lit));check(!SmoothedSupportFixtures.accepts(true,lp,lamp,Blocks.STONE.defaultBlockState()));
  var off=Blocks.BLACK_WOOL.defaultBlockState();var on=Blocks.REDSTONE_BLOCK.defaultBlockState();var pp=new BlockPos(xx,80,49);check(SmoothedSupportFixtures.accepts(true,pp,off,off));check(SmoothedSupportFixtures.accepts(true,pp,off,on));check(!SmoothedSupportFixtures.accepts(true,pp.east(),off,on));check(!SmoothedSupportFixtures.accepts(true,pp,off,Blocks.GRAY_WOOL.defaultBlockState()));check(!SmoothedSupportFixtures.accepts(false,pp,off,off));check(!SmoothedSupportFixtures.accepts(false,pp,off,on));check(SmoothedSupportFixtures.accepts(false,lp,lamp,lit));
  check(!Shapes.joinIsNotEmpty(lamp.getCollisionShape(EmptyBlockGetter.INSTANCE,lp),Shapes.block(),BooleanOp.NOT_SAME));check(!Shapes.joinIsNotEmpty(lit.getCollisionShape(EmptyBlockGetter.INSTANCE,lp),Shapes.block(),BooleanOp.NOT_SAME));check(!Shapes.joinIsNotEmpty(off.getCollisionShape(EmptyBlockGetter.INSTANCE,pp),Shapes.block(),BooleanOp.NOT_SAME));check(!Shapes.joinIsNotEmpty(on.getCollisionShape(EmptyBlockGetter.INSTANCE,pp),Shapes.block(),BooleanOp.NOT_SAME));
 }
 System.out.println("PASS "+n+" finite host shape, exact voxel union, owner intrusion refusal, NBT and orientation checks");
 }
}
