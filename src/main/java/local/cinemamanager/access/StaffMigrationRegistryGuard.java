package local.cinemamanager.access;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
/** Explicit migration only: never treats a corrupt or partially parsed existing staff file as empty. */
final class StaffMigrationRegistryGuard {
 static StaffAccessRegistry peek(ServerLevel l){return l.getDataStorage().get(new SavedData.Factory<>(StaffAccessRegistry::new,StaffAccessRegistry::load),"mycinema_staff_access");}
 static boolean complete(CompoundTag data){
  try{return canonical(data).equals(canonical(StaffAccessRegistry.load(data,null).save(new CompoundTag(),null)));}
  catch(RuntimeException bad){return false;}
 }
 private static String canonical(Tag t){
  if(t instanceof CompoundTag c){var keys=new ArrayList<>(c.getAllKeys());Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String k:keys)s.append(k.length()).append(':').append(k).append('=').append(canonical(c.get(k))).append(';');return s.append('}').toString();}
  if(t instanceof ListTag l){var items=new ArrayList<String>();for(Tag i:l)items.add(canonical(i));Collections.sort(items);return "list"+l.getElementType()+items;}
  return t==null?"null":t.getId()+":"+t.toString();
 }
 static String fault(ServerLevel l){
  Path root=l.getServer().getWorldPath(LevelResource.ROOT),data=root.resolve("data"),file=data.resolve("mycinema_staff_access.dat");
  if(Files.isSymbolicLink(root)||Files.isSymbolicLink(data)||Files.isSymbolicLink(file))return "STAFF_REGISTRY_SYMLINK_REFUSED";
  try{
   var attrs=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!attrs.isRegularFile()||attrs.size()>8L*1024*1024)return "STAFF_REGISTRY_FILE_UNKNOWN";
   CompoundTag nbt=NbtIo.readCompressed(file,NbtAccounter.create(16L*1024*1024));
   if(nbt==null||!nbt.contains("data",10)||!complete(nbt.getCompound("data")))return "STAFF_REGISTRY_PARTIAL_OR_MALFORMED: preserve existing bytes/grants";
   return peek(l)==null?"STAFF_REGISTRY_EXISTING_BUT_UNREADABLE":null;
  }catch(NoSuchFileException absent){return null;}catch(java.io.IOException|RuntimeException bad){return "STAFF_REGISTRY_READ_FAILED: preserve existing bytes/grants";}
 }
 private StaffMigrationRegistryGuard(){}
}
