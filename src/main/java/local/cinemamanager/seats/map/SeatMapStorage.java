package local.cinemamanager.seats.map;

import java.util.*;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.seats.SeatRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.saveddata.SavedData;

/** One saved document per existing manager/hall. Invalid and future NBT is retained byte-for-byte structurally. */
public final class SeatMapStorage extends SavedData {
    private SeatMapLayoutDocument document;
    private CompoundTag preserved;
    public boolean readable(){return preserved==null;}
    public Optional<SeatMapLayoutDocument> document(){if(!readable())throw SeatMapGeometry.invalid("invalid_saved");return Optional.ofNullable(document);}
    public static SeatMapStorage get(ServerLevel level,UUID manager,String hall){
        SeatRegistry.uuid(hall);
        return level.getDataStorage().computeIfAbsent(new Factory<>(SeatMapStorage::new,SeatMapStorage::load),"mycinema_seat_map_"+manager+"_"+hall);
    }
    public static SeatMapStorage load(CompoundTag tag,HolderLookup.Provider registries){
        SeatMapStorage result=new SeatMapStorage();
        try {
            if(!tag.getAllKeys().equals(Set.of("version","document"))||!tag.contains("version",Tag.TAG_INT)||tag.getInt("version")!=1||!tag.contains("document",Tag.TAG_STRING))throw SeatMapGeometry.invalid("invalid_saved");
            String raw=tag.getString("document");if(!raw.isEmpty())result.document=SeatMapLayoutDocument.decode(raw);
        }catch(RuntimeException bad){result.preserved=tag.copy();}
        return result;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        if(preserved!=null)return preserved.copy();tag.putInt("version",1);tag.putString("document",document==null?"":document.encode());return tag;
    }
    /** Minimal safe editing contract for an explicit server-side importer; no client mutation packet exists.
     * Validates every seat and authored reference against current registry before committing. */
    public void importAuthored(ServerPlayer actor,ManagerTile manager,String hall,long expectedRegistry,long expectedLayout,String raw){
        if(!SeatMapNetwork.managerAllowed(actor,manager)||SeatMapStorage.get((ServerLevel)actor.level(),manager.identity,hall)!=this)
            throw SeatMapGeometry.invalid("permission");
        if(!readable())throw SeatMapGeometry.invalid("invalid_saved");
        var registry=local.cinemamanager.seats.SeatStorage.get((ServerLevel)actor.level(),manager.identity);
        registry.requireEditable();registry.registry().requireRevision(expectedRegistry);
        if((document==null?0:document.layout().revision())!=expectedLayout)throw SeatMapGeometry.invalid("stale");
        SeatMapLayoutDocument next=SeatMapLayoutDocument.decode(raw);
        if(next.layout().revision()!=expectedLayout+1)throw SeatMapGeometry.invalid("stale");
        SeatMapServer.capture(actor,manager,hall,Optional.of(next));
        document=next;setDirty();
    }
}
