package local.cinemamanager.access;
import java.util.*;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.doors.CinemaDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
/** Bounded registered-door lease recovery and card-free east-side exit; never enrolls or grants cards. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class FixedStaffDoorLifecycle {
 static boolean occupied(ServerLevel l,FixedStaffDoorPlan.Plan p){
  return !l.getEntitiesOfClass(LivingEntity.class,p.occupied().inflate(.05),e->e.isAlive()&&!e.isSpectator()).isEmpty()
      ||!l.getEntitiesOfClass(LivingEntity.class,p.egress(),e->e.isAlive()&&!e.isSpectator()).isEmpty();
 }
 static boolean occupiedOpen(Level level,BlockPos base){
  if(!(level instanceof ServerLevel l)||!FixedStaffDoorPlan.profile(l))return false;
  var plan=FixedStaffDoorPlan.base(base);if(plan==null||plan.iron()||!FixedStaffDoorPlan.loaded(l,base))return false;
  var registry=StaffMigrationRegistryGuard.peek(l);if(registry==null||registry.door(base.asLong())==null)return false;
  var state=l.getBlockState(base);if(!(state.getBlock() instanceof CinemaDoorBlock)||!state.getValue(CinemaDoorBlock.OPEN))return false;
  return occupied(l,plan);
 }
 /** Pure requested-state guard, called before vanilla's native power-loss write. No placement, repair or new opening. */
 public static BlockState guardIronPowerClose(Level level,BlockPos at,BlockState requested){
  if(!(level instanceof ServerLevel l)||!requested.is(Blocks.IRON_DOOR)||requested.getValue(DoorBlock.OPEN)
   ||!FixedIronDoorAccess.candidate(l,at)||!FixedStaffDoorPlan.loaded(l,at))return requested;
  BlockPos base=FixedIronDoorAccess.base(at);var registry=StaffMigrationRegistryGuard.peek(l);var binding=registry==null?null:registry.door(base.asLong());
  if(binding==null||!FixedIronDoorAccess.capturedLeaf(l,binding,base))return requested;
  for(var leaf:binding.leaves)if(registry.door(leaf.pos())!=binding||!FixedIronDoorAccess.capturedLeaf(l,binding,BlockPos.of(leaf.pos())))return requested;
  BlockState current=l.getBlockState(at);if(!current.is(Blocks.IRON_DOOR)||!current.getValue(DoorBlock.OPEN)
   ||current.getValue(DoorBlock.HALF)!=requested.getValue(DoorBlock.HALF)||current.getValue(DoorBlock.HINGE)!=requested.getValue(DoorBlock.HINGE)
   ||current.getValue(DoorBlock.FACING)!=requested.getValue(DoorBlock.FACING)||!occupied(l,FixedIronDoorAccess.PLAN))return requested;
  // Only OPEN is held. POWERED, facing, hinge, half and the native update flags remain exactly as requested.
  return requested.setValue(DoorBlock.OPEN,true);
 }
 static void arm(ServerLevel l){/* Work is deferred to postTick; never mutate during chunk admission/onLoad. */}
 @SubscribeEvent public static void tick(ServerTickEvent.Post event){
  ServerLevel l=event.getServer().overworld();if(l==null||l.getGameTime()%5!=0||!FixedStaffDoorPlan.profile(l))return;
  var registry=StaffMigrationRegistryGuard.peek(l);if(registry==null)return;
  for(var p:FixedStaffDoorPlan.PLANS){
   Set<StaffAccessRegistry.Door> bindings=new HashSet<>();
   for(BlockPos base:p.leaves()){
    if(!FixedStaffDoorPlan.loaded(l,base))continue;
    var binding=registry.door(base.asLong());if(binding==null)continue;bindings.add(binding);
    for(ServerPlayer player:l.players())if(StaffReaderTile.real(player)&&player.isAlive()&&p.egress().intersects(player.getBoundingBox()))AccessDoors.egress(player,l,base);
   }
   if(p.iron())for(var binding:bindings)FixedIronDoorAccess.reconcile(l,binding);
  }
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void clicked(PlayerInteractEvent.RightClickBlock event){
  if(!(event.getLevel() instanceof ServerLevel l)||!(event.getEntity() instanceof ServerPlayer actor)||!StaffReaderTile.real(actor)
   ||!FixedIronDoorAccess.candidate(l,event.getPos())||!actor.canInteractWithBlock(event.getPos(),1.0))return;
  // Explicit pairing is handled by the existing item path, never by a lifecycle callback.
  if(event.getItemStack().is(AccessModule.PAIRING_CARD.get()))return;
  BlockPos base=FixedIronDoorAccess.base(event.getPos());
  if(FixedIronDoorAccess.egress(actor,l,base)||EmergencyAccess.keepOpen(actor,l,base)||TabletAccess.click(actor,event.getPos())){
   event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
  }
 }
 private static void invalidate(ServerLevel l,BlockPos pos,String reason){
  if(!FixedIronDoorAccess.candidate(l,pos))return;
  var registry=StaffMigrationRegistryGuard.peek(l);if(registry!=null)registry.invalidate(FixedIronDoorAccess.base(pos).asLong());
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void broken(BlockEvent.BreakEvent event){if(event.getLevel() instanceof ServerLevel l)invalidate(l,event.getPos(),"break");}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void placed(BlockEvent.EntityPlaceEvent event){
  if(event.getLevel() instanceof ServerLevel l){invalidate(l,event.getPos(),"placement");if(event instanceof BlockEvent.EntityMultiPlaceEvent multi)for(var s:multi.getReplacedBlockSnapshots())invalidate(l,s.getPos(),"multi placement");}
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void exploded(ExplosionEvent.Detonate event){if(event.getLevel() instanceof ServerLevel l)for(BlockPos p:event.getAffectedBlocks())invalidate(l,p,"explosion");}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void piston(PistonEvent.Pre event){
  if(event.getLevel() instanceof ServerLevel l)for(BlockPos p:FixedIronDoorAccess.PLAN.leaves())if(p.distManhattan(event.getPos())<=14)invalidate(l,p,"piston vicinity");
 }
 private FixedStaffDoorLifecycle(){}
}
