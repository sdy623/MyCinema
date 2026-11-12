package local.cinemamanager;

import com.google.gson.JsonParser;
import local.cinemamanager.panels.*;
import net.minecraft.nbt.*;

import java.io.*;
import java.util.*;

/** Public manual-programme contracts; no loaded world, media clock, renderer or GUI. */
public final class ActualProgrammeGuestChecks {
    private static final long NOW=1_900_000_000_000L;
    private static int checks;
    private ActualProgrammeGuestChecks() {}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){
        try{action.run();}catch(RuntimeException expected){checks++;return;}
        throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception{run();}
    public static void run()throws Exception{
        checks=0;
        var manual=new PublicShowRow("programme:public-fixture","Actual film","Public hall",0,120_000,0,false);
        var snapshot=new PublicPanelSnapshot("",NOW,List.of(manual));
        check(!manual.scheduled()&&manual.start()==0&&manual.end()==0,"manual programme carries no invented schedule timestamps");
        check(GuestFields.range(manual,snapshot,key->"translated:"+key).equals("translated:time.unscheduled"),"manual time is explicitly unscheduled even without a configured timezone");
        var fields=GuestFields.forRow(PanelSettings.DEFAULT,PanelScope.HALL,snapshot,manual,true,key->"translated:"+key);
        check(fields.stream().anyMatch(f->f.label().equals("title_label")&&f.value().equals("Actual film")),"manual title comes from the current public row");
        check(fields.stream().anyMatch(f->f.label().equals("time_label")&&f.value().equals("translated:time.unscheduled")),"both public renderers receive the explicit manual time field");
        check(fields.stream().anyMatch(f->f.label().equals("duration_label")&&f.value().equals("2:00")),"manual duration is known media duration, independent of absent schedule times");
        check(fields.stream().noneMatch(f->f.value().contains("1970")||f.value().contains("00:00–")),"manual display never formats the sentinel timestamps as a schedule");
        var hiddenTime=new PanelSettings(PanelSettings.Layout.TEXT,"","","","","","",0xffffff,0,0,100,PanelSettings.TITLE|PanelSettings.DURATION);
        check(GuestFields.forRow(hiddenTime,PanelScope.HALL,snapshot,manual,true,key->key).stream().noneMatch(f->f.label().equals("time_label")),"hidden time suppresses even the unscheduled label");
        var override=new PanelSettings(PanelSettings.Layout.TEXT,"","Owner title","20:30","","","",0xffffff,0,0,100,PanelSettings.PUBLIC);
        check(GuestFields.forRow(override,PanelScope.HALL,snapshot,manual,true,key->key).stream().anyMatch(f->f.label().equals("time_label")&&f.value().equals("20:30")),"an explicit owner time override still wins over manual programme facts");

        var scheduled=new PublicShowRow("show:public-fixture","Planned film","Public hall",NOW,120_000,NOW+123_000);
        check(scheduled.scheduled()&&new PublicShowRow("legacy","Film","Hall",NOW,1000).scheduled(),"legacy constructors continue to mean a scheduled row");
        var scheduledSnapshot=new PublicPanelSnapshot("UTC",NOW,List.of(scheduled));
        check(!GuestFields.range(scheduled,scheduledSnapshot,key->key).contains("unscheduled"),"scheduled programme keeps its nominal wall-clock range");
        rejects(()->new PublicShowRow("bad","Film","Hall",NOW,120_000,NOW+123_000,false),"manual rows reject nonzero schedule timestamps");
        rejects(()->new PublicShowRow("bad","Film","Hall",0,120_000,1,false),"manual rows reject an invented end alone");
        rejects(()->new PublicPanelSnapshot("",NOW,List.of(manual,scheduled)),"a mixed snapshot still requires a valid timezone for scheduled entries");

        CompoundTag saved=snapshot.save();
        var item=saved.getList("rows",Tag.TAG_COMPOUND).getCompound(0);
        check(item.getAllKeys().equals(Set.of("id","title","hall","start","end","duration_ms","scheduled")),"row NBT has exactly the seven public fields");
        check(item.contains("scheduled",Tag.TAG_BYTE)&&!item.getBoolean("scheduled"),"NBT explicitly preserves false rather than relying on a missing flag");
        check(PublicPanelSnapshot.read(saved).equals(snapshot),"manual snapshot survives NBT roundtrip with its empty timezone");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){NbtIo.write(saved,out);}
        CompoundTag decoded;
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))){decoded=NbtIo.read(in,NbtAccounter.unlimitedHeap());}
        check(PublicPanelSnapshot.read(decoded).equals(snapshot),"manual marker survives real binary NBT serialization");
        var old=scheduledSnapshot.save();var oldRow=old.getList("rows",Tag.TAG_COMPOUND).getCompound(0);oldRow.remove("scheduled");
        check(PublicPanelSnapshot.read(old).equals(scheduledSnapshot),"existing row NBT without scheduled remains scheduled");
        oldRow.remove("end");
        var restoredLegacy=PublicPanelSnapshot.read(old).rows().getFirst();
        check(restoredLegacy.scheduled()&&restoredLegacy.end()==restoredLegacy.start(),"older row NBT without end retains the prior migration behavior");
        var forged=scheduledSnapshot.save();forged.getList("rows",Tag.TAG_COMPOUND).getCompound(0).putBoolean("scheduled",false);
        rejects(()->PublicPanelSnapshot.read(forged),"forged manual NBT cannot retain apparently authoritative start/end values");

        var privateSettings=new PanelSettings(PanelSettings.Layout.POSTER_LEFT,"","","","PRIVATE_NEXT","PRIVATE_FOOTER","resource:mycinema:textures/block/info.png",0xffffff,0,0,100,PanelSettings.ALL);
        var settings=privateSettings.publicOnly(PanelScope.HALL,privateSettings.poster());
        var data=new PanelNetwork.GuestData(settings,PanelScope.HALL,snapshot,1,1);
        String json=PanelNetwork.JSON.toJson(data);
        var publicRow=JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("snapshot").getAsJsonArray("rows").get(0).getAsJsonObject();
        check(publicRow.keySet().equals(Set.of("id","title","hall","start","end","durationMs","scheduled")),"JSON manual rows expose only the seven approved public fields");
        check(!publicRow.get("scheduled").getAsBoolean(),"guest JSON does not lose the manual marker");
        check(PanelNetwork.JSON.fromJson(json,PanelNetwork.GuestData.class).equals(data),"guest JSON roundtrip retains manual row semantics");
        for(String forbidden:List.of("PRIVATE_","seekToken","filmSnapshot","filmId","owner","terminalIdentity","operationRevision","boundSource"))
            check(!json.contains(forbidden),"manual guest payload excludes private/control field "+forbidden);
        check(Arrays.stream(PublicShowRow.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("id","title","hall","start","durationMs","end","scheduled")),"record shape cannot silently acquire a control token or private source");
        check(snapshot.sameContent(new PublicPanelSnapshot("",NOW+1,snapshot.rows())),"clock-only manual refresh remains stable without a timezone");
        rejects(()->snapshot.rows().clear(),"public manual row collection remains immutable");
        System.out.println("PASS: "+checks+" actual programme guest checks: explicit manual time, visibility, duration, JSON privacy and binary NBT migration.");
    }
}
