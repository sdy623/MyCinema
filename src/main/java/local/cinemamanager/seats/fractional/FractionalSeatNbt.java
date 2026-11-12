package local.cinemamanager.seats.fractional;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** New implementation of bounded v2 payloads; old v1 remains ORIGINAL rather than being converted to the selected chair. */
public final class FractionalSeatNbt {
    private FractionalSeatNbt(){}
    public static FractionalSeatData read(CompoundTag tag,BlockPos home){
        if(!tag.contains("fractional_version",Tag.TAG_INT))throw new IllegalArgumentException("Missing version");
        int version=tag.getInt("fractional_version");
        if(version!=1&&version!=2||!tag.contains("fractional_resolution",Tag.TAG_INT)||tag.getInt("fractional_resolution")!=64
            ||!tag.contains("fractional_home",Tag.TAG_LONG)||tag.getLong("fractional_home")!=home.asLong()
            ||version==1&&(!tag.contains("fractional_model",Tag.TAG_STRING)||!FractionalSeatData.MODEL_SHA256.equals(tag.getString("fractional_model")))
            ||!(tag.get("fractional_owners") instanceof ListTag list)||list.size()>4||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid seat-cell schema");
        List<FractionalSeatData.Contribution> out=new ArrayList<>();
        for(Tag entry:list){
            CompoundTag owner=(CompoundTag)entry;
            if(!owner.hasUUID("uid")||!owner.contains("x",Tag.TAG_DOUBLE)||!owner.contains("y",Tag.TAG_DOUBLE)||!owner.contains("z",Tag.TAG_DOUBLE)
                ||!owner.contains("turns",Tag.TAG_INT)||!(owner.get("runs") instanceof IntArrayTag runs)||runs.size()>8192)
                throw new IllegalArgumentException("Invalid cell owner");
            var profile=FractionalSeatGeometryProfile.ORIGINAL;
            if(version==2){if(!owner.contains("model_sha256",Tag.TAG_STRING))throw new IllegalArgumentException("Missing model identity");profile=FractionalSeatGeometryProfile.fromHash(owner.getString("model_sha256"));}
            out.add(new FractionalSeatData.Contribution(owner.getUUID("uid"),new FractionalSeatData.Anchor(owner.getDouble("x"),owner.getDouble("y"),owner.getDouble("z"),owner.getInt("turns")),runs.getAsIntArray(),profile));
        }
        var result=FractionalSeatData.of(out);if(!result.validAt(home.getX(),home.getY(),home.getZ()))throw new IllegalArgumentException("Wrong seat cell home");return result;
    }
    public static void write(CompoundTag tag,BlockPos home,FractionalSeatData data){
        tag.putInt("fractional_version",2);tag.putInt("fractional_resolution",64);tag.putLong("fractional_home",home.asLong());tag.remove("fractional_model");
        ListTag owners=new ListTag();
        for(var c:data.owners()){
            CompoundTag n=new CompoundTag();n.putUUID("uid",c.uid());n.putString("model_sha256",c.geometry().modelSha256());
            n.putDouble("x",c.anchor().x());n.putDouble("y",c.anchor().y());n.putDouble("z",c.anchor().z());n.putInt("turns",c.anchor().turns());n.putIntArray("runs",c.runsCopy());owners.add(n);
        }
        tag.put("fractional_owners",owners);
    }
}
