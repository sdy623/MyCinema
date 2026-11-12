package local.cinemamanager.access;
import java.util.*;
import local.cinemamanager.doors.DoorAccess;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
/** Formal-card actuator adapter for the original backstage iron pair only. Never accepts arbitrary positions. */
final class FixedIronDoorAccess {
 static final FixedStaffDoorPlan.Plan PLAN=FixedStaffDoorPlan.PLANS.stream().filter(FixedStaffDoorPlan.Plan::iron).findFirst().orElseThrow();
 static boolean candidate(Level l,BlockPos clicked){
  return l instanceof ServerLevel server&&FixedStaffDoorPlan.profile(server)&&PLAN.leaves().stream().anyMatch(p->p.equals(clicked)||p.above().equals(clicked));
 }
 static BlockPos base(BlockPos clicked){return PLAN.leaves().stream().filter(p->p.equals(clicked)||p.above().equals(clicked)).findFirst().orElse(null);}
 static String shape(BlockPos base){
  if(!PLAN.leaves().contains(base))return "";
  return "minecraft:iron_door|east|"+(base.getZ()==4?"left":"right")+"|staff_back|staff_back|iron2";
 }
 static boolean leaf(ServerLevel l,BlockPos base){
  if(!FixedStaffDoorPlan.profile(l)||!PLAN.leaves().contains(base))return false;
  for(var cell:PLAN.cells())if(cell.pos().getZ()==base.getZ()){
   if(!FixedStaffDoorPlan.loaded(l,cell.pos()))return false;BlockState s=l.getBlockState(cell.pos());
   if(!cell.block().equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()))return false;
   for(var e:cell.properties().entrySet())if(!e.getValue().equals(FixedStaffDoorPlan.property(s,e.getKey())))return false;
  }
  return true;
 }
 static boolean capturedLeaf(ServerLevel l,StaffAccessRegistry.Door d,BlockPos base){
  return d!=null&&leaf(l,base)&&d.leaves.stream().anyMatch(x->x.pos()==base.asLong()&&shape(base).equals(x.shape()));
 }
 static boolean current(ServerLevel l,StaffAccessRegistry.Door d){
  if(d==null||d.leaves.size()!=2||!FixedStaffDoorPlan.exact(l,PLAN))return false;
  for(BlockPos base:PLAN.leaves())if(!capturedLeaf(l,d,base)||StaffAccessRegistry.get(l).door(base.asLong())!=d)return false;
  return true;
 }
 static void bind(ServerPlayer actor,StaffReaderTile reader,BlockPos clicked){
  reader.requireEditor(actor);ServerLevel l=actor.serverLevel();
  if(!candidate(l,clicked)||!FixedStaffDoorPlan.exact(l,PLAN))throw new IllegalArgumentException("mycinema.access.invalid");
  BlockPos base=base(clicked);
  if(actor.distanceToSqr(Vec3.atCenterOf(base))>64||reader.getBlockPos().distSqr(base)>16*16)throw new IllegalArgumentException("mycinema.access.range");
  // The fixed original interior is east. Ordinary pairing must be performed from the west entry side.
  if(!DoorAccess.STAFF_FRONT.allows(Direction.EAST,base,actor.position()))throw new IllegalArgumentException("mycinema.access.side");
  var registry=StaffAccessRegistry.get(l);
  for(BlockPos leaf:PLAN.leaves()){
   if(!l.mayInteract(actor,leaf)||!l.mayInteract(actor,leaf.above()))throw new IllegalArgumentException("mycinema.access.permission");
   var old=registry.door(leaf.asLong());
   if(old!=null&&!old.reader.equals(reader.identity)&&!actor.hasPermissions(2)){
    BlockPos at=BlockPos.of(old.readerPos);
    if(!l.hasChunkAt(at)||!(l.getBlockEntity(at) instanceof StaffReaderTile previous)||!previous.identity.equals(old.reader)||!previous.permits(actor,Double.POSITIVE_INFINITY))throw new IllegalArgumentException("mycinema.access.claimed");
   }
  }
  installBinding(registry,reader);
 }
 static void installBinding(StaffAccessRegistry registry,StaffReaderTile reader){
  var leaves=PLAN.leaves().stream().map(p->new StaffAccessRegistry.Leaf(p.asLong(),shape(p))).toList();
  var d=new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),leaves,reader.controllerId,reader.controllerOwner,reader.controllerPos.asLong());
  registry.bind(d);reader.invalidatePairing();reader.doorPos=BlockPos.of(d.base());reader.doorId=d.token;reader.dirty();
 }
 static boolean inside(Player player,ServerLevel l,StaffAccessRegistry.Door d,BlockPos base){
  return player!=null&&player.level()==l&&!player.isSpectator()&&l.mayInteract(player,base)
   &&capturedLeaf(l,d,base)&&DoorAccess.STAFF_BACK.allows(Direction.EAST,base,player.position());
 }
 static boolean egress(Player player,ServerLevel l,BlockPos base){
  var r=StaffAccessRegistry.get(l);var d=r.door(base.asLong());if(!inside(player,l,d,base))return false;
  for(var other:d.leaves)if(!inside(player,l,d,BlockPos.of(other.pos())))return false;
  return pulse(l,d,base);
 }
 static boolean pulse(ServerLevel l,StaffAccessRegistry.Door d,BlockPos base){
  if(!capturedLeaf(l,d,base))return false;
  for(var other:d.leaves)if(!capturedLeaf(l,d,BlockPos.of(other.pos()))||StaffAccessRegistry.get(l).door(other.pos())!=d)return false;
  StaffAccessRegistry.get(l).pulse(d,l.getGameTime());return setOpen(l,d,true);
 }
 private static boolean setOpen(ServerLevel l,StaffAccessRegistry.Door d,boolean open){
  for(var leaf:d.leaves)if(!capturedLeaf(l,d,BlockPos.of(leaf.pos())))return false;
  for(var leaf:d.leaves){BlockPos at=BlockPos.of(leaf.pos());
   if(StaffAccessRegistry.get(l).door(leaf.pos())!=d||!capturedLeaf(l,d,at))return false;
   ((DoorBlock)l.getBlockState(at).getBlock()).setOpen(null,l,l.getBlockState(at),at,open);
  }
  return d.leaves.stream().allMatch(x->{BlockPos at=BlockPos.of(x.pos());if(!capturedLeaf(l,d,at))return false;for(int y=0;y<2;y++){var s=l.getBlockState(at.above(y));if(!s.is(Blocks.IRON_DOOR)||s.getValue(DoorBlock.OPEN)!=open)return false;}return true;});
 }
 /** Returns true only when the requested reconciliation leaves every captured leaf confirmed OPEN. */
 static boolean reconcile(ServerLevel l,StaffAccessRegistry.Door d){
  if(d==null||d.leaves.isEmpty()||d.leaves.stream().anyMatch(x->!PLAN.leaves().contains(BlockPos.of(x.pos()))))return false;
  for(var leaf:d.leaves)if(!capturedLeaf(l,d,BlockPos.of(leaf.pos())))return false;
  boolean power=false,emergency=false,open=false;
  for(var leaf:d.leaves){BlockPos at=BlockPos.of(leaf.pos());if(!l.hasChunksAt(at.offset(-2,-1,-2),at.offset(2,2,2)))return false;
   power|=l.hasNeighborSignal(at)||l.hasNeighborSignal(at.above());emergency|=EmergencyAccess.heldOpen(l,at);open|=l.getBlockState(at).getValue(DoorBlock.OPEN);
  }
  boolean occupied=open&&FixedStaffDoorLifecycle.occupied(l,PLAN);
  boolean desired=power||emergency||d.held(l.getGameTime())||occupied;return setOpen(l,d,desired)&&desired;
 }
 private FixedIronDoorAccess(){}
}
