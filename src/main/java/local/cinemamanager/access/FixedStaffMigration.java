package local.cinemamanager.access;
import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.doors.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
/** Explicit finite installation migration. Adopts verified original ownership; never issues any grant/card. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class FixedStaffMigration {
 private static String readerFault(ServerLevel l,FixedStaffDoorPlan.Plan p){
  if(!FixedStaffDoorPlan.loaded(l,p.reader())||!FixedStaffDoorPlan.loaded(l,p.reader().east())||!FixedStaffDoorPlan.loaded(l,p.reader().below()))return "UNLOADED_READER: "+p.id();
  var state=l.getBlockState(p.reader());
  if(!state.is(AccessModule.READER.get())||state.getValue(StaffReaderBlock.FACING)!=Direction.WEST||!(l.getBlockEntity(p.reader()) instanceof StaffReaderTile r)||r.identity==null||r.isRemoved()||r.ownershipFault())return "READER_EXACT_TYPE_FACING_ID: "+p.id();
  if(!p.backing().equals(BuiltInRegistries.BLOCK.getKey(l.getBlockState(p.reader().east()).getBlock()).toString())
   ||!p.below().equals(BuiltInRegistries.BLOCK.getKey(l.getBlockState(p.reader().below()).getBlock()).toString()))return "READER_SUPPORT_CLEARANCE: "+p.id();
  return null;
 }
 private static boolean unclaimed(StaffReaderTile r){return r.owner==null&&r.controllerPos==null&&r.controllerId==null&&r.controllerOwner==null&&r.doorPos==null&&r.doorId==null;}
 private static boolean adopted(StaffReaderTile r){return FixedStaffDoorPlan.OWNER.equals(r.owner)&&FixedStaffDoorPlan.MANAGER.equals(r.controllerPos)&&FixedStaffDoorPlan.CONTROLLER.equals(r.controllerId)&&FixedStaffDoorPlan.OWNER.equals(r.controllerOwner);}
 private static List<StaffAccessRegistry.Leaf> captured(ServerLevel l,FixedStaffDoorPlan.Plan p){
  if(p.iron())return p.leaves().stream().map(b->new StaffAccessRegistry.Leaf(b.asLong(),FixedIronDoorAccess.shape(b))).toList();
  List<StaffAccessRegistry.Leaf> result=new ArrayList<>();
  for(BlockPos base:p.leaves()){
   var s=l.getBlockState(base);StringBuilder shape=new StringBuilder(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).append('|').append(s.getValue(CinemaDoorBlock.FACING).getSerializedName()).append('|').append(s.getValue(CinemaDoorBlock.HINGE).getSerializedName());
   for(DoorPart part:DoorPart.values())shape.append('|').append(l.getBlockState(base.above(part.offset())).getValue(CinemaDoorBlock.ACCESS).getSerializedName());
   result.add(new StaffAccessRegistry.Leaf(base.asLong(),shape.toString()));
  }
  result.sort(Comparator.comparingLong(StaffAccessRegistry.Leaf::pos));return List.copyOf(result);
 }
 private static boolean already(ServerLevel l,StaffAccessRegistry registry,StaffReaderTile reader,FixedStaffDoorPlan.Plan p){
  if(registry==null||!adopted(reader)||reader.doorPos==null||reader.doorId==null)return false;
  var d=registry.door(reader.doorPos.asLong());
  return d!=null&&d.token.equals(reader.doorId)&&d.reader.equals(reader.identity)&&d.readerPos==reader.getBlockPos().asLong()
   &&FixedStaffDoorPlan.CONTROLLER.equals(d.controller)&&FixedStaffDoorPlan.OWNER.equals(d.controllerOwner)&&d.controllerPos==FixedStaffDoorPlan.MANAGER.asLong()
   &&new HashSet<>(d.leaves).equals(new HashSet<>(captured(l,p)))&&AccessDoors.current(l,d);
 }
 public static String migrate(ServerLevel l){
  if(!FixedStaffDoorPlan.profile(l))return "WRONG_WORLD_PROFILE";
  String f=StaffMigrationRegistryGuard.fault(l);if(f!=null)return f;
  if(!FixedStaffDoorPlan.loaded(l,FixedStaffDoorPlan.MANAGER)||!(l.getBlockEntity(FixedStaffDoorPlan.MANAGER) instanceof ManagerTile manager)
   ||manager.isRemoved()||!FixedStaffDoorPlan.OWNER.equals(manager.owner)||!FixedStaffDoorPlan.CONTROLLER.equals(manager.identity)
   ||!"mycinema:manager_terminal".equals(BuiltInRegistries.BLOCK.getKey(l.getBlockState(FixedStaffDoorPlan.MANAGER).getBlock()).toString()))return "ORIGINAL_MANAGER_IDENTITY_OWNER_MISMATCH";
  StaffAccessRegistry registry=StaffMigrationRegistryGuard.peek(l);List<FixedStaffDoorPlan.Plan> needed=new ArrayList<>();Set<UUID> readerIds=new HashSet<>();
  for(var p:FixedStaffDoorPlan.PLANS){
   if(!FixedStaffDoorPlan.exact(l,p))return "DOOR_STATIC_ASSEMBLY_MISMATCH: "+p.id();
   f=readerFault(l,p);if(f!=null)return f;var r=(StaffReaderTile)l.getBlockEntity(p.reader());
   if(!readerIds.add(r.identity))return "DUPLICATE_READER_IDENTITY";
   if(already(l,registry,r,p))continue;
   if(!unclaimed(r))return "READER_ALREADY_CLAIMED_OR_PARTIAL: "+p.id()+"; preserve existing ownership";
   if(registry!=null)for(BlockPos base:p.leaves())if(registry.door(base.asLong())!=null)return "EXISTING_DOOR_BINDING_CONFLICT: "+p.id();
   needed.add(p);
  }
  if(needed.isEmpty())return null;
  if(registry!=null&&registry.allDoors().stream().mapToInt(d->d.leaves.size()).sum()+needed.size()*2>StaffAccessRegistry.MAX_DOORS)return "STAFF_REGISTRY_CAPACITY";
  // Creation occurs only after complete finite preflight and the existing-file guard.
  if(registry==null)registry=StaffAccessRegistry.get(l);
  var cardsBefore=registry.cards.all();var tabletsBefore=registry.tablets.all();List<String> changed=new ArrayList<>();
  try{
   for(var p:needed){
    if(!FixedStaffDoorPlan.exact(l,p)||readerFault(l,p)!=null||!FixedStaffDoorPlan.OWNER.equals(manager.owner)||!FixedStaffDoorPlan.CONTROLLER.equals(manager.identity))return "PARTIAL: revalidation changed; migrated="+changed;
    var reader=(StaffReaderTile)l.getBlockEntity(p.reader());if(!unclaimed(reader))return "PARTIAL: reader changed; migrated="+changed;
    for(BlockPos base:p.leaves())if(registry.door(base.asLong())!=null)return "PARTIAL: door claimed; migrated="+changed;
    changed.add(p.id());
    // Exact user-authorized ownership migration to the verified original cinema, never QA ownership.
    reader.owner=manager.owner;reader.controllerPos=manager.getBlockPos().immutable();reader.controllerId=manager.identity;reader.controllerOwner=manager.owner;
    var d=new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),captured(l,p),manager.identity,manager.owner,manager.getBlockPos().asLong());
    registry.bind(d);reader.invalidatePairing();reader.doorPos=BlockPos.of(d.base());reader.doorId=d.token;reader.dirty();
   }
   if(!cardsBefore.equals(registry.cards.all())||!tabletsBefore.equals(registry.tablets.all()))return "PARTIAL: unrelated authority changed; inspect grants; migrated="+changed;
   for(var p:FixedStaffDoorPlan.PLANS)if(!already(l,registry,(StaffReaderTile)l.getBlockEntity(p.reader()),p))return "PARTIAL: final binding readback failed; migrated="+changed;
   return null;
  }catch(RuntimeException|LinkageError ex){return "PARTIAL: "+ex.getClass().getSimpleName()+"; migrated="+changed+"; no rollback or grant repair";}
 }
 @SubscribeEvent public static void commands(RegisterCommandsEvent event){
  var root=Commands.literal("mycinema_access").requires(s->s.hasPermission(2));
  root.then(Commands.literal("r7_migrate_doors").executes(c->{String fault=migrate(c.getSource().getServer().overworld());if(fault!=null){c.getSource().sendFailure(Component.literal(fault));return 0;}c.getSource().sendSuccess(()->Component.literal("R7 three readers/doors explicitly bound to the verified original cinema owner; no staff/tablet grant was issued or changed. Formal-card roles remain owner-configurable."),false);return 1;}));
  root.then(Commands.literal("r7_status").executes(c->{ServerLevel l=c.getSource().getServer().overworld();var r=StaffMigrationRegistryGuard.peek(l);String f=StaffMigrationRegistryGuard.fault(l);c.getSource().sendSuccess(()->Component.literal("R7 formal staff: registry="+(f==null?"readable/absent":f)+"; no automatic enrollment or legacy-paper conversion"),false);
   for(var p:FixedStaffDoorPlan.PLANS){boolean valid=r!=null&&FixedStaffDoorPlan.exact(l,p)&&readerFault(l,p)==null&&already(l,r,(StaffReaderTile)l.getBlockEntity(p.reader()),p);c.getSource().sendSuccess(()->Component.literal(p.id()+": original-owner binding="+valid),false);}return 0;
  }));event.getDispatcher().register(root);
 }
 private FixedStaffMigration(){}
}
