package local.cinemamanager.switches;

import java.util.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import static local.cinemamanager.switches.RegisteredSwitchEngine.*;

/** Persistent positional generations, not unforgeable block identities. No automatic enrollment. */
final class PositionEnrollment extends SavedData {
    static final String KEY="mycinema_registered_switch_positions_rc2";
    static final Factory<PositionEnrollment> FACTORY=new Factory<>(PositionEnrollment::new,PositionEnrollment::load,null);
    record Entry(long generation,boolean valid,String reason){}
    private final Map<String,Entry> entries=new TreeMap<>();
    private String installation=UUID.randomUUID().toString();
    private long generation;
    private boolean malformed;
    static PositionEnrollment read(ServerLevel level){return level.getDataStorage().get(FACTORY,KEY);}
    static PositionEnrollment create(ServerLevel level){
        String fault=diskFault(level.getServer().getWorldPath(LevelResource.ROOT),read(level)!=null);
        if(fault!=null)throw new IllegalStateException(fault);
        return level.getDataStorage().computeIfAbsent(FACTORY,KEY);
    }
    static String diskFault(Path worldRoot,boolean loaded){
        Path data=worldRoot.resolve("data"),file=data.resolve(KEY+".dat");
        if(Files.isSymbolicLink(worldRoot)||Files.isSymbolicLink(data)||Files.isSymbolicLink(file))return "SYMLINK_POSITION_RECEIPT_REFUSED";
        try{
            BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isRegularFile())return "POSITION_RECEIPT_NOT_REGULAR_FILE";
            if(!loaded)return "EXISTING_POSITION_RECEIPT_UNREADABLE: preserve bytes and review; no replacement";
            return null;
        }catch(NoSuchFileException absent){return null;}
        catch(IOException|SecurityException bad){return "POSITION_RECEIPT_FILESYSTEM_UNKNOWN: preserve and review";}
    }
    private boolean reserve(int count){return generation>=0 && generation<=Long.MAX_VALUE-45-count;}
    String fault(){return !reserve(0)?"EXHAUSTED_POSITION_GENERATION":malformed?"MALFORMED_POSITION_REGISTRY":entries.size()!=45?"INCOMPLETE_POSITION_REGISTRY":null;}
    String summary(){return entries.values().stream().filter(Entry::valid).count()+"/45 valid positional enrollments; generation="+generation;}
    boolean valid(String id){Entry e=entries.get(id);return fault()==null&&e!=null&&e.valid();}
    long generation(String id){Entry e=entries.get(id);return e==null?-1:e.generation();}
    boolean enrollAll(){
        if(malformed||!reserve(45))return false;
        for(Device d:RegisteredSwitchRegistry.DEVICES)if(d.kind()==Kind.LEVER)entries.put(d.id(),new Entry(++generation,true,"explicit positional enrollment"));
        setDirty();return true;
    }
    boolean reenroll(String id){
        if(fault()!=null||!entries.containsKey(id)||!reserve(1))return false;
        entries.put(id,new Entry(++generation,true,"explicit positional re-enrollment"));setDirty();return true;
    }
    void invalidate(String id,String reason){
        Entry e=entries.get(id);
        if(e!=null&&e.valid()){
            // Invalidation must still fail closed if its counter is exhausted; never wrap negative.
            if(reserve(1))generation++;
            entries.put(id,new Entry(generation,false,reason));setDirty();
        }
    }
    static PositionEnrollment load(CompoundTag tag,HolderLookup.Provider provider){
        PositionEnrollment r=new PositionEnrollment();
        try{
            if(!tag.getAllKeys().equals(Set.of("schema_version","registry_sha256","installation_uuid","generation","entries"))
                    ||!tag.contains("schema_version",3)||tag.getInt("schema_version")!=1||!tag.contains("registry_sha256",8)
                    ||!RegisteredSwitchRegistry.DIGEST.equals(tag.getString("registry_sha256"))||!tag.contains("installation_uuid",8)
                    ||!tag.contains("generation",4)||!tag.contains("entries",9))throw new IllegalArgumentException();
            r.installation=UUID.fromString(tag.getString("installation_uuid")).toString();r.generation=tag.getLong("generation");
            if(!r.reserve(0))throw new IllegalArgumentException();
            Set<String>allowed=new HashSet<>(RegisteredSwitchRegistry.DEVICES.stream().filter(d->d.kind()==Kind.LEVER).map(Device::id).toList());
            ListTag list=tag.getList("entries",10);if(list.size()!=45)throw new IllegalArgumentException();
            for(Tag t:list){CompoundTag e=(CompoundTag)t;
                if(!e.getAllKeys().equals(Set.of("id","generation","valid","reason"))||!e.contains("id",8)||!e.contains("generation",4)
                        ||!e.contains("valid",1)||(e.getByte("valid")!=0&&e.getByte("valid")!=1)||!e.contains("reason",8)||e.getString("reason").length()>256||!allowed.remove(e.getString("id"))
                        ||e.getLong("generation")<1||e.getLong("generation")>r.generation)throw new IllegalArgumentException();
                r.entries.put(e.getString("id"),new Entry(e.getLong("generation"),e.getBoolean("valid"),e.getString("reason")));
            }
            if(!allowed.isEmpty())throw new IllegalArgumentException();
        }catch(RuntimeException bad){r.malformed=true;r.entries.clear();}
        return r;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider){
        if(malformed)throw new IllegalStateException("Refuse overwriting malformed enrollment registry");
        tag.putInt("schema_version",1);tag.putString("registry_sha256",RegisteredSwitchRegistry.DIGEST);
        tag.putString("installation_uuid",installation);tag.putLong("generation",generation);ListTag list=new ListTag();
        for(var entry:entries.entrySet()){CompoundTag e=new CompoundTag();e.putString("id",entry.getKey());e.putLong("generation",entry.getValue().generation());
            e.putBoolean("valid",entry.getValue().valid());e.putString("reason",entry.getValue().reason());list.add(e);}
        tag.put("entries",list);return tag;
    }
}
