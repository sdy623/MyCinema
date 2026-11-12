package local.cinemamanager.seats;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** One bounded file per immutable manager identity; dimension data storage supplies the scope. */
public final class SeatStorage extends SavedData {
    private SeatRegistry registry=new SeatRegistry();
    private String invalidBackup="";
    public SeatRegistry registry(){return registry;}
    public boolean editable(){return invalidBackup.isEmpty();}
    public void requireEditable(){if(!editable())throw SeatRegistry.error("invalid_saved");}
    public static SeatStorage get(ServerLevel level, UUID manager){return level.getDataStorage().computeIfAbsent(new Factory<>(SeatStorage::new,SeatStorage::load),"mycinema_seats_"+manager);}
    public static SeatStorage load(CompoundTag tag,HolderLookup.Provider registries){
        SeatStorage storage=new SeatStorage();String raw=tag.getString("registry");
        if(!raw.isEmpty())try{storage.registry=SeatRegistry.decode(raw);}catch(RuntimeException invalid){storage.invalidBackup=raw;}
        return storage;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putString("registry",invalidBackup.isEmpty()?registry.encode():invalidBackup);return tag;}
}
