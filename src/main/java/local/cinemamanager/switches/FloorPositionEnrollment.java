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
/** Separate two-position receipt, so 45 original lever registrations never depend on new floor construction. */
final class FloorPositionEnrollment extends SavedData {
 static final String KEY="mycinema_floor_positions";
 static final Factory<FloorPositionEnrollment> FACTORY=new Factory<>(FloorPositionEnrollment::new,FloorPositionEnrollment::load,null);
 record Entry(long generation,boolean valid,boolean routeValid,String reason){}
 private static final Map<ServerLevel,Map<String,Long>> CANDIDATES=Collections.synchronizedMap(new WeakHashMap<>());
 private final Map<String,Entry> entries=new TreeMap<>();private long generation;private boolean malformed;
 static FloorPositionEnrollment read(ServerLevel l){return l.getDataStorage().get(FACTORY,KEY);}
 static String diskFault(Path root,boolean loaded){
  Path data=root.resolve("data"),f=data.resolve(KEY+".dat");
  if(Files.isSymbolicLink(root)||Files.isSymbolicLink(data)||Files.isSymbolicLink(f))return "SYMLINK_FLOOR_RECEIPT_REFUSED";
  try{var a=Files.readAttributes(f,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return !a.isRegularFile()?"FLOOR_RECEIPT_NOT_REGULAR_FILE":!loaded?"EXISTING_FLOOR_RECEIPT_UNREADABLE: preserve bytes":null;}
  catch(NoSuchFileException absent){return null;}catch(IOException|SecurityException bad){return "FLOOR_RECEIPT_FILESYSTEM_UNKNOWN";}
 }
 static String receiptFault(ServerLevel l,FloorPositionEnrollment r){String f=diskFault(l.getServer().getWorldPath(LevelResource.ROOT),r!=null);return f!=null?f:r==null?"FLOOR_NOT_BOUND":r.fault();}
 String fault(){return malformed?"MALFORMED_FLOOR_RECEIPT":generation<0||generation>Long.MAX_VALUE-8?"EXHAUSTED_FLOOR_GENERATION":null;}
 Entry entry(String id){return fault()==null?entries.get(id):null;}
 String summary(){return entries.entrySet().stream().map(e->e.getKey()+"="+(e.getValue().valid()?"bound":"invalid")+", route="+e.getValue().routeValid()).toList().toString();}
 static String bind(ServerLevel l,String id){
  if(l!=l.getServer().overworld())return "WRONG_DIMENSION";
  var p=AuxiliaryPlan.get(id);if(p==null||!p.floor())return "UNKNOWN_FIXED_FLOOR_ID";
  String f=new MinecraftSwitchGateway(l.getServer()).profileFault();if(f!=null)return f;
  if(RegisteredSwitchCommands.hasPending(l.getServer()))return "PENDING_CONTROL_REQUEST";
  var r=read(l);f=diskFault(l.getServer().getWorldPath(LevelResource.ROOT),r!=null);if(f!=null)return f;if(r!=null&&r.fault()!=null)return r.fault();
  f=FloorCircuitValidation.core(l,p);if(f!=null)return f;f=FloorCircuitValidation.route(l,p);if(f!=null)return f;
  if(FloorCircuitValidation.powered(l,p)||!Boolean.FALSE.equals(FloorCircuitValidation.lamps(l,p)))return "FLOOR_BIND_REQUIRES_CONTROLLER_OFF_AND_12_LAMPS_OFF";
  var candidates=CANDIDATES.computeIfAbsent(l,k->new HashMap<>());Long tick=candidates.get(id);
  if(tick==null){candidates.put(id,l.getGameTime());return "FLOOR_BIND_PENDING: repeat after later native tick";}
  if(l.getGameTime()<=tick)return "FLOOR_BIND_PENDING: await later native tick";
  if(r==null)r=l.getDataStorage().computeIfAbsent(FACTORY,KEY);
  if(!r.enroll(id))return "FLOOR_BIND_RECEIPT_REFUSED";
  candidates.remove(id);RegisteredSwitchCommands.bindingsChanged(l.getServer());return null;
 }
 boolean enroll(String id){
  var p=AuxiliaryPlan.get(id);if(fault()!=null||generation>=Long.MAX_VALUE-8||p==null||!p.floor())return false;
  entries.put(id,new Entry(++generation,true,true,"explicit fixed positional + exact route enrollment"));setDirty();return true;
 }
 void invalidate(String id,boolean critical,String reason){
  Entry e=entries.get(id);if(e==null||(!e.valid()&&!e.routeValid()))return;
  if(generation<Long.MAX_VALUE-8)generation++;
  entries.put(id,new Entry(generation,critical?false:e.valid(),false,reason));setDirty();
 }
 static FloorPositionEnrollment load(CompoundTag t,HolderLookup.Provider provider){
  var r=new FloorPositionEnrollment();
  try{
   if(!t.getAllKeys().equals(Set.of("schema_version","plan_sha256","generation","entries"))||!t.contains("schema_version",3)||t.getInt("schema_version")!=1
    ||!t.contains("plan_sha256",8)||!AuxiliaryPlan.DIGEST.equals(t.getString("plan_sha256"))||!t.contains("generation",4)||!t.contains("entries",9))throw new IllegalArgumentException();
   r.generation=t.getLong("generation");if(r.fault()!=null)throw new IllegalArgumentException();ListTag list=(ListTag)t.get("entries");if((list.getElementType()!=0&&list.getElementType()!=10)||list.size()>2)throw new IllegalArgumentException();
   for(Tag tag:list){CompoundTag e=(CompoundTag)tag;String id=e.getString("id");var p=AuxiliaryPlan.get(id);
    if(!e.getAllKeys().equals(Set.of("id","generation","valid","route_valid","reason"))||!e.contains("id",8)||p==null||!p.floor()||r.entries.containsKey(id)
     ||!e.contains("generation",4)||e.getLong("generation")<1||e.getLong("generation")>r.generation||!e.contains("valid",1)||(e.getByte("valid")!=0&&e.getByte("valid")!=1)
     ||!e.contains("route_valid",1)||(e.getByte("route_valid")!=0&&e.getByte("route_valid")!=1)||(!e.getBoolean("valid")&&e.getBoolean("route_valid"))
     ||!e.contains("reason",8)||e.getString("reason").length()>256)throw new IllegalArgumentException();
    r.entries.put(id,new Entry(e.getLong("generation"),e.getBoolean("valid"),e.getBoolean("route_valid"),e.getString("reason")));
   }
  }catch(RuntimeException bad){r.malformed=true;r.entries.clear();}return r;
 }
 @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider provider){
  if(malformed)throw new IllegalStateException("Refuse overwrite malformed floor receipt");
  t.putInt("schema_version",1);t.putString("plan_sha256",AuxiliaryPlan.DIGEST);t.putLong("generation",generation);ListTag list=new ListTag();
  for(var row:entries.entrySet()){Entry a=row.getValue();CompoundTag e=new CompoundTag();e.putString("id",row.getKey());e.putLong("generation",a.generation());e.putBoolean("valid",a.valid());e.putBoolean("route_valid",a.routeValid());e.putString("reason",a.reason());list.add(e);}t.put("entries",list);return t;
 }
}
