package local.cinemamanager.panels;
import java.util.*;
import java.util.function.Function;
/** Shared factual field selection used directly by both world and read-only client renderers. */
public final class GuestFields {
    public record Field(String label,String value,boolean accent){}
    private GuestFields(){}
    public static List<Field> forRow(PanelSettings settings,PanelScope scope,PublicPanelSnapshot snapshot,PublicShowRow row,boolean overrides,Function<String,String> tr){
        List<Field> out=new ArrayList<>();boolean manual=scope!=PanelScope.LOBBY;
        boolean title=overrides&&!settings.title().isBlank(),time=overrides&&!settings.time().isBlank();
        if(row==null&&!(manual&&(!settings.title().isBlank()||!settings.time().isBlank()||!settings.duration().isBlank()||!settings.hall().isBlank()||!settings.screeningFormat().isBlank())))return List.of();
        add(out,settings,PanelSettings.TITLE,"title_label",title?settings.title():row==null?"—":row.title(),true);
        add(out,settings,PanelSettings.TIME,"time_label",time?settings.manualTime().replace("{next_day}",tr.apply("day.next_day")):row==null?"—":range(row,snapshot,tr,scope),false);
        if(scope==PanelScope.LOBBY)add(out,settings,PanelSettings.HALL,"hall_label",row==null?"—":local.cinemamanager.MessageText.display(row.hall()),true);
        boolean handwritten=manual&&overrides&&!settings.duration().isBlank();
        add(out,settings,PanelSettings.DURATION,handwritten?"duration_manual_label":"duration_label",handwritten?settings.duration():duration(row==null?0:row.durationMs()),false);
        if(manual&&!settings.screeningFormat().isBlank())add(out,settings,PanelSettings.FORMAT,"format_label",settings.screeningFormat(),false);
        return List.copyOf(out);
    }
    private static void add(List<Field> out,PanelSettings p,int flag,String label,String value,boolean accent){if(p.shows(flag))out.add(new Field(label,value==null||value.isBlank()?"—":value,accent));}
    /** Legacy/general date context remains unchanged; auditorium formatting is scope-specific. */
    public static String range(PublicShowRow row,PublicPanelSnapshot snapshot,Function<String,String> tr){return range(row,snapshot,tr,PanelScope.LOBBY);}
    public static String range(PublicShowRow row,PublicPanelSnapshot snapshot,Function<String,String> tr,PanelScope scope){
        if(!row.scheduled())return tr.apply("time.unscheduled");
        var r=PublicTimeRange.of(row.start(),row.end(),snapshot.generatedAt(),snapshot.timezone());
        String day=r.dayKey().equals("date")?r.date():r.dayKey().equals("unknown")?"":tr.apply("day."+r.dayKey());
        if(scope==PanelScope.HALL&&r.dayKey().equals("today"))day="";
        String endDay=switch(r.endDayKey()){case "next_day"->tr.apply("day.next_day")+" ";case "date"->r.endDate()+" ";default->"";};
        return (day.isBlank()?"":day+" ")+r.start()+"–"+endDay+r.end();
    }
    public static String duration(long milliseconds){if(milliseconds<=0)return "—";long s=milliseconds/1000;return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
}
