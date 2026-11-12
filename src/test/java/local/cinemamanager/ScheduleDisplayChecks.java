package local.cinemamanager;
import local.cinemamanager.panels.*;
import java.time.*;
import java.util.*;
public final class ScheduleDisplayChecks {
    private static int n;
    static void check(boolean x,String m){n++;if(!x)throw new AssertionError(m);}
    static long at(String x){return Instant.parse(x).toEpochMilli();}
    static String tr(String k){return switch(k){case "day.today"->"Today";case "day.tomorrow"->"Tomorrow";case "day.next_day"->"next day";default->k;};}
    public static void main(String[] args){
        var confirmation=new local.cinemamanager.client.ContinuousConfirmation(value->{},net.minecraft.network.chat.Component.literal("Confirm"),net.minecraft.network.chat.Component.literal("Message"));
        check(!confirmation.isPauseScreen(),"actual confirmation screen object does not pause the integrated scheduler");
        var details=new local.cinemamanager.client.ContinuousConfirmation(value->{},net.minecraft.network.chat.Component.literal("Details"),net.minecraft.network.chat.Component.literal("Reason"),net.minecraft.network.chat.Component.literal("Done"),net.minecraft.network.chat.Component.literal("Back"));
        check(!details.isPauseScreen(),"actual details screen object does not pause the integrated scheduler");
        long now=at("2030-01-01T09:00:00Z"),start=at("2030-01-01T09:45:00Z"),end=at("2030-01-01T11:45:00Z");
        var row=new PublicShowRow("show","Human film title","Hall A",start,0,end);var snap=new PublicPanelSnapshot("Asia/Tokyo",now,List.of(row));
        check(GuestFields.range(row,snap,ScheduleDisplayChecks::tr).equals("Today 18:45–20:45"),"show end reaches actual guest selector with HH:mm range");
        var f=GuestFields.forRow(PanelSettings.DEFAULT,PanelScope.HALL,snap,row,true,ScheduleDisplayChecks::tr);
        check(f.size()==3&&f.stream().noneMatch(x->x.label().equals("hall_label")),"hall screen exposes film/time/runtime only, plus separately rendered poster");
        check(f.stream().anyMatch(x->x.label().equals("time_label")&&x.value().equals("18:45–20:45")),"auditorium current-day field omits Today prefix");
        var lobby=GuestFields.forRow(PanelSettings.DEFAULT,PanelScope.LOBBY,snap,row,false,ScheduleDisplayChecks::tr);
        check(lobby.stream().anyMatch(x->x.label().equals("time_label")&&x.value().equals("Today 18:45–20:45")),"lobby retains daily programme context");
        check(f.get(2).value().equals("—"),"two-hour scheduled slot never becomes trusted movie runtime");
        check(f.stream().noneMatch(x->x.value().contains("2030")||x.value().contains("Asia/")||x.value().contains("+09")),"no full date, zone identifier or UTC offset in guest fields");
        var midnight=new PublicShowRow("night","Night","Hall",at("2030-01-01T14:30:00Z"),0,at("2030-01-01T16:30:00Z"));
        check(GuestFields.range(midnight,snap,ScheduleDisplayChecks::tr).equals("Today 23:30–next day 01:30"),"cross-midnight end explicit");
        check(GuestFields.range(midnight,snap,ScheduleDisplayChecks::tr,PanelScope.HALL).equals("23:30–next day 01:30"),"auditorium omits only today while preserving cross-midnight end");
        var tomorrow=new PublicShowRow("next","Next","Hall",start+86400000,0,end+86400000);check(GuestFields.range(tomorrow,snap,ScheduleDisplayChecks::tr).startsWith("Tomorrow "),"future show never called today");
        var later=new PublicShowRow("later","Later","Hall",start+172800000,0,end+172800000);check(GuestFields.range(later,snap,ScheduleDisplayChecks::tr).startsWith("01-03 "),"later date concise and unambiguous");
        var manual=new PanelSettings(PanelSettings.Layout.TEXT,"Hidden hall","Manual film","18:45–20:45","secret","secret","",0xffffff,0,0xeeeeee,100,PanelSettings.PUBLIC,"1h 53m");
        for(PanelScope scope:List.of(PanelScope.HALL,PanelScope.MANUAL)){
            var fields=GuestFields.forRow(manual,scope,PublicPanelSnapshot.EMPTY,null,true,ScheduleDisplayChecks::tr);
            check(fields.stream().anyMatch(x->x.label().equals("time_label")&&x.value().equals("18:45–20:45")),"saved manual time shown without a controller/plan in "+scope);
            check(fields.stream().anyMatch(x->x.label().equals("duration_manual_label")&&x.value().equals("1h 53m")),"handwritten runtime explicitly labelled");
            check(fields.stream().noneMatch(x->x.value().contains("secret")||x.value().equals("Hidden hall")),"guest override hides old footer/status and redundant hall");
        }
        check(GuestFields.forRow(manual,PanelScope.LOBBY,PublicPanelSnapshot.EMPTY,null,false,ScheduleDisplayChecks::tr).isEmpty(),"lobby cannot invent manual show");
        var laterFields=GuestFields.forRow(manual,PanelScope.HALL,snap,row,false,ScheduleDisplayChecks::tr);
        check(laterFields.stream().anyMatch(x->x.label().equals("duration_label")&&x.value().equals("—"))&&laterFields.stream().noneMatch(x->x.value().equals("1h 53m")),"later Hall rows do not inherit first-row handwritten runtime");
        var off=new PanelSettings(manual.layout(),manual.hall(),manual.title(),manual.time(),"","","",manual.foreground(),0,manual.accent(),100,PanelSettings.TITLE,manual.duration());
        check(GuestFields.forRow(off,PanelScope.HALL,PublicPanelSnapshot.EMPTY,null,true,ScheduleDisplayChecks::tr).stream().noneMatch(x->x.label().equals("time_label")),"explicit hidden time remains hidden");
        for(int blocks:List.of(1,2))for(int scale:List.of(60,100,160))for(var layout:List.of(PanelSettings.Layout.POSTER_TOP,PanelSettings.Layout.POSTER_LEFT))for(int[] image:List.of(new int[]{8192,1},new int[]{1,8192},new int[]{800,1200},new int[]{1200,800})){
            var p=new PanelSettings(layout,manual.hall(),manual.title(),manual.time(),"","","resource:minecraft:textures/block/stone.png",0xffffff,0,0xffffff,scale,PanelSettings.PUBLIC,manual.duration());
            var fields=GuestFields.forRow(p,PanelScope.MANUAL,PublicPanelSnapshot.EMPTY,null,true,ScheduleDisplayChecks::tr);
            float px=.0064f*scale/100f;var areas=PanelLayout.areas(.92f/px,(blocks-.08f)/px,layout);var poster=PanelLayout.contain(areas.poster(),image[0],image[1]);
            check(fields.size()==3&&fields.get(1).value().equals("18:45–20:45"),"poster presence/ratio/layout cannot drop manual text fields");
            check(areas.text().height()>=11&&areas.text().width()>=1,"smallest panel retains a usable text region");
            boolean disjoint=poster.x()+poster.width()<=areas.text().x()||poster.y()+poster.height()<=areas.text().y()||areas.text().x()+areas.text().width()<=poster.x()||areas.text().y()+areas.text().height()<=poster.y();
            check(disjoint,"poster containment does not paint over text region for extreme aspect ratios");
            check(p.withPoster("").title().equals(manual.title())&&p.withPoster("").time().equals(manual.time())&&p.withPoster("").duration().equals(manual.duration()),"clearing a saved poster preserves manual information");
        }
        var splitTime=new PanelSettings(manual.layout(),"",manual.title(),"23:30","","","",0xffffff,0,0xffffff,100,PanelSettings.PUBLIC,"1h 53m","01:20");
        check(GuestFields.forRow(splitTime,PanelScope.MANUAL,PublicPanelSnapshot.EMPTY,null,true,ScheduleDisplayChecks::tr).stream().anyMatch(x->x.value().equals("23:30–next day 01:20")),"separate manual HH:mm fields label next-day end");
        check(PanelSettings.read(splitTime.save()).equals(splitTime),"manual split time NBT survives");
        var oldTag=manual.save();oldTag.putInt("version",2);oldTag.remove("duration");oldTag.remove("end_time");var old=PanelSettings.read(oldTag);
        check(old.time().equals(manual.time())&&old.duration().isEmpty()&&old.endTime().isEmpty()&&old.save().getInt("version")==6,"legacy v2 preserves display override while new fields start empty");
        var oldJson=PanelNetwork.JSON.toJsonTree(old).getAsJsonObject();oldJson.remove("duration");oldJson.remove("endTime");
        var oldRound=PanelNetwork.JSON.fromJson(oldJson,PanelSettings.class);check(oldRound.equals(old),"missing old JSON fields migrate without clearing saved time");
        check(PanelSettings.read(manual.save()).equals(manual),"manual time/runtime actual NBT roundtrip");
        check(PublicPanelSnapshot.read(snap.save()).equals(snap),"explicit end survives public NBT");
        check(!snap.sameContent(new PublicPanelSnapshot(snap.timezone(),now+86400000,snap.rows())),"midnight invalidates public cache for today label");
        check(snap.sameContent(new PublicPanelSnapshot(snap.timezone(),now+1000,snap.rows())),"ordinary seconds do not churn snapshot");
        System.out.println("PASS: "+n+" schedule display checks; real shared guest field selector, manual overrides, planned end, midnight, NBT and trusted/runtime separation.");
    }
}
