package local.cinemamanager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;
/** One persistent control-center enrollment per immutable TMS identity, within a dimension. */
public final class EnrollmentRegistry extends SavedData {
    private final Map<UUID,UUID> claims=new HashMap<>();
    public static EnrollmentRegistry get(ServerLevel level){return level.getDataStorage().computeIfAbsent(new Factory<>(EnrollmentRegistry::new,EnrollmentRegistry::load),"mycinema_enrollments");}
    public boolean claim(UUID terminal,UUID manager){UUID existing=claims.get(terminal);if(existing!=null&&!existing.equals(manager))return false;claims.put(terminal,manager);setDirty();return true;}
    public boolean owns(UUID terminal,UUID manager){return manager.equals(claims.get(terminal));}
    public void release(UUID terminal,UUID manager){if(claims.remove(terminal,manager))setDirty();}
    public void releaseAll(UUID manager){if(claims.entrySet().removeIf(e->e.getValue().equals(manager)))setDirty();}
    public static EnrollmentRegistry load(CompoundTag tag,HolderLookup.Provider regs){EnrollmentRegistry r=new EnrollmentRegistry();for(Tag t:tag.getList("claims",Tag.TAG_COMPOUND)){CompoundTag c=(CompoundTag)t;if(c.hasUUID("terminal")&&c.hasUUID("manager"))r.claims.put(c.getUUID("terminal"),c.getUUID("manager"));}return r;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider regs){ListTag list=new ListTag();for(var e:claims.entrySet()){CompoundTag c=new CompoundTag();c.putUUID("terminal",e.getKey());c.putUUID("manager",e.getValue());list.add(c);}tag.put("claims",list);return tag;}
}
