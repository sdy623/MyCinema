package local.cinemamanager.switches;
import java.util.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import static local.cinemamanager.switches.RegisteredSwitchEngine.*;
/** Explicit adoption of actual installer UUIDs. Never places, toggles, retags or configures hardware. */
public final class IeInstallationBinding extends SavedData {
 static final String KEY="mycinema_ie_installation";
 static final Factory<IeInstallationBinding> FACTORY=new Factory<>(IeInstallationBinding::new,IeInstallationBinding::load,null);
 record Entry(String owner,long generation,boolean valid,boolean routeValid,String reason){}
 private record Candidate(String owner,long tick){}
 private static final Map<ServerLevel,Map<String,Candidate>> CANDIDATES=Collections.synchronizedMap(new WeakHashMap<>());
 final Map<String,Entry> entries=new TreeMap<>();private long generation;private boolean malformed;
 static IeInstallationBinding read(ServerLevel l){return l.getDataStorage().get(FACTORY,KEY);}
 static String diskFault(Path root,boolean loaded){
  Path data=root.resolve("data"),f=data.resolve(KEY+".dat");
  if(Files.isSymbolicLink(root)||Files.isSymbolicLink(data)||Files.isSymbolicLink(f))return "SYMLINK_IE_RECEIPT_REFUSED";
  try{var a=Files.readAttributes(f,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return !a.isRegularFile()?"IE_RECEIPT_NOT_REGULAR_FILE":!loaded?"EXISTING_IE_RECEIPT_UNREADABLE: preserve bytes":null;}
  catch(NoSuchFileException absent){return null;}catch(IOException|SecurityException bad){return "IE_RECEIPT_FILESYSTEM_UNKNOWN";}
 }
 static String receiptFault(ServerLevel l,IeInstallationBinding r){String f=diskFault(l.getServer().getWorldPath(LevelResource.ROOT),r!=null);return f!=null?f:r==null?"IE_NOT_BOUND":r.fault();}
 String fault(){return malformed?"MALFORMED_IE_RECEIPT":generation<0||generation>Long.MAX_VALUE-8?"EXHAUSTED_IE_GENERATION":null;}
 Entry entry(String id){return fault()==null?entries.get(id):null;}
 String summary(){return entries.entrySet().stream().map(e->e.getKey()+"="+(e.getValue().valid()?"bound":"invalid")+", route="+e.getValue().routeValid()).toList().toString();}
 /** Installer calls this only after native construction. First call observes OFF; repeat after a later native tick. */
 public static String bind(ServerLevel level,String id){
  if(level!=level.getServer().overworld())return "WRONG_DIMENSION";
  var c=IeCircuitPlan.get(id);if(c==null)return "UNKNOWN_FIXED_IE_ID";
  String f=new MinecraftSwitchGateway(level.getServer()).profileFault();if(f!=null)return f;
  if(RegisteredSwitchCommands.hasPending(level.getServer()))return "PENDING_CONTROL_REQUEST: verify before binding";
  IeInstallationBinding r=read(level);f=diskFault(level.getServer().getWorldPath(LevelResource.ROOT),r!=null);if(f!=null)return f;if(r!=null&&r.fault()!=null)return r.fault();
  if(!level.hasChunkAt(IeCircuitValidation.pos(c.controller().pos())))return "UNLOADED_ACTUAL_CONTROLLER";
  var be=level.getBlockEntity(IeCircuitValidation.pos(c.controller().pos()));if(be==null)return "MISSING_ACTUAL_CONTROLLER";
  String owner=be.getPersistentData().getString("cinema_ie_owner");
  try{if(!UUID.fromString(owner).toString().equals(owner))return "NONCANONICAL_INSTALLER_UUID";}catch(RuntimeException bad){return "MISSING_ACTUAL_INSTALLER_UUID";}
  // Both independently installed circuits must adopt the same current installation session.
  if(r!=null)for(var e:r.entries.entrySet())if(!e.getKey().equals(id)&&!e.getValue().owner().equals(owner))return "INSTALLATION_UUID_CONFLICT";
  f=IeCircuitValidation.core(level,c,owner);if(f!=null)return f;
  f=IeCircuitValidation.route(level,c,owner);if(f!=null)return f;
  try{
   var controller=level.getBlockEntity(IeCircuitValidation.pos(c.controller().pos()));var breaker=level.getBlockEntity(IeCircuitValidation.pos(c.breaker().pos()));
   if(IeSwitchBridge.active(controller)||!IeSwitchBridge.active(breaker)||!Boolean.FALSE.equals(IeSwitchBridge.internal(level,breaker.getBlockPos())))return "BIND_REQUIRES_NATIVE_CONFIRMED_OFF";
  }catch(ReflectiveOperationException|RuntimeException|LinkageError bad){return "BIND_NETWORK_UNKNOWN";}
  Map<String,Candidate> candidates=CANDIDATES.computeIfAbsent(level,l->new HashMap<>());Candidate before=candidates.get(id);
  if(before==null||!before.owner().equals(owner)||level.getGameTime()<=before.tick()){
   if(before==null||!before.owner().equals(owner))candidates.put(id,new Candidate(owner,level.getGameTime()));
   return "BIND_PENDING_OFF_VERIFY: repeat explicit bind after a later native server tick";
  }
  if(r==null)r=level.getDataStorage().computeIfAbsent(FACTORY,KEY);
  if(!r.enroll(id,owner))return "BIND_RECEIPT_REFUSED";
  candidates.remove(id);RegisteredSwitchCommands.bindingsChanged(level.getServer());return null;
 }
 boolean enroll(String id,String owner){
  if(fault()!=null||generation>=Long.MAX_VALUE-8||IeCircuitPlan.get(id)==null)return false;
  try{if(!UUID.fromString(owner).toString().equals(owner))return false;}catch(RuntimeException bad){return false;}
  entries.put(id,new Entry(owner,++generation,true,true,"explicit native OFF + full physical topology binding"));setDirty();return true;
 }
 void invalidate(String id,boolean critical,String reason){
  Entry e=entries.get(id);if(e==null||(!e.valid()&&!e.routeValid()))return;
  if(generation<Long.MAX_VALUE-8)generation++;
  entries.put(id,new Entry(e.owner(),generation,critical?false:e.valid(),false,reason));setDirty();
 }
 static IeInstallationBinding load(CompoundTag t,HolderLookup.Provider provider){
  IeInstallationBinding r=new IeInstallationBinding();
  try{
   if(!t.getAllKeys().equals(Set.of("schema_version","plan_sha256","generation","entries"))||!t.contains("schema_version",3)||t.getInt("schema_version")!=1
    ||!t.contains("plan_sha256",8)||!IeCircuitPlan.DIGEST.equals(t.getString("plan_sha256"))||!t.contains("generation",4)||!t.contains("entries",9))throw new IllegalArgumentException();
   r.generation=t.getLong("generation");if(r.fault()!=null)throw new IllegalArgumentException();
   ListTag list=(ListTag)t.get("entries");if((list.getElementType()!=0&&list.getElementType()!=10)||list.size()>2)throw new IllegalArgumentException();
   for(Tag tag:list){CompoundTag e=(CompoundTag)tag;String id=e.getString("id"),owner=e.getString("owner");
    if(!e.getAllKeys().equals(Set.of("id","owner","generation","valid","route_valid","reason"))||!e.contains("id",8)||IeCircuitPlan.get(id)==null||r.entries.containsKey(id)
     ||!e.contains("owner",8)||!UUID.fromString(owner).toString().equals(owner)||!e.contains("generation",4)||e.getLong("generation")<1||e.getLong("generation")>r.generation
     ||!e.contains("valid",1)||(e.getByte("valid")!=0&&e.getByte("valid")!=1)||!e.contains("route_valid",1)||(e.getByte("route_valid")!=0&&e.getByte("route_valid")!=1)
     ||(!e.getBoolean("valid")&&e.getBoolean("route_valid"))||!e.contains("reason",8)||e.getString("reason").length()>256)throw new IllegalArgumentException();
    if(!r.entries.isEmpty()&&!r.entries.values().iterator().next().owner().equals(owner))throw new IllegalArgumentException();
    r.entries.put(id,new Entry(owner,e.getLong("generation"),e.getBoolean("valid"),e.getBoolean("route_valid"),e.getString("reason")));
   }
  }catch(RuntimeException bad){r.malformed=true;r.entries.clear();}return r;
 }
 @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider provider){
  if(malformed)throw new IllegalStateException("Refuse overwrite malformed IE receipt");
  t.putInt("schema_version",1);t.putString("plan_sha256",IeCircuitPlan.DIGEST);t.putLong("generation",generation);ListTag list=new ListTag();
  for(var row:entries.entrySet()){Entry a=row.getValue();CompoundTag e=new CompoundTag();e.putString("id",row.getKey());e.putString("owner",a.owner());e.putLong("generation",a.generation());e.putBoolean("valid",a.valid());e.putBoolean("route_valid",a.routeValid());e.putString("reason",a.reason());list.add(e);}t.put("entries",list);return t;
 }
}
