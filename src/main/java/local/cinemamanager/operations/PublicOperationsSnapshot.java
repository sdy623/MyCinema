package local.cinemamanager.operations;

import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.PanelScope;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.nbt.*;
import java.time.*;
import java.util.*;

/** Public wall allowlist. No addresses, identifiers, grants, source paths or actor-specific tickets. */
public record PublicOperationsSnapshot(String cinema,String timezone,long generatedAt,String state,List<Hall> halls,List<Plan> plans) {
    public record Hall(String name,String status,String playlist,int clip,int clips) {
        public Hall {name=label(name,"Hall");status=code(status);playlist=label(playlist,"");if(clip<0||clips<0||clips>64||clip>clips)throw invalid();}
    }
    public record Plan(String hall,String title,long start,long end,String status) {
        public Plan {hall=label(hall,"Hall");title=label(title,"Programme");status=code(status);if(start<0||end<=start)throw invalid();}
    }
    public static final PublicOperationsSnapshot EMPTY=new PublicOperationsSnapshot("","",0,"UNBOUND",List.of(),List.of());
    public PublicOperationsSnapshot {
        cinema=label(cinema,"MyCinema");timezone=zone(timezone);state=code(state);
        if(generatedAt<0||halls==null||plans==null||halls.size()>ManagerState.MAX_HALLS||plans.size()>ManagerState.MAX_SHOWS)throw invalid();
        halls=List.copyOf(halls);plans=List.copyOf(plans);
    }
    /** Do not echo path/URL/credential-shaped authored labels onto a public physical display. */
    public static String label(String value,String fallback) {
        if(value==null||value.isBlank()||value.length()>128||value.chars().anyMatch(Character::isISOControl))return fallback;
        String lower=value.toLowerCase(Locale.ROOT);
        if(value.indexOf('/')>=0||value.indexOf('\\')>=0||lower.matches("^[a-z][a-z0-9+.-]*:.*")
                ||lower.matches(".*\\.[a-z0-9]{1,10}([ ?#].*)?$")||lower.matches(".*(token|password|consent|evidence|owner)\\s*=.*"))return fallback;
        return value.strip();
    }
    public static String code(String value){return value!=null&&value.matches("[A-Z_]{1,40}")?value:"UNAVAILABLE";}
    public static String zone(String value){try{return value==null||value.isBlank()||value.length()>128?"":ZoneId.of(value).getId();}catch(RuntimeException invalid){return "";}}
    public static boolean includes(InfoTile panel,ManagerState.Hall hall){return panel.scope==PanelScope.LOBBY||panel.scope==PanelScope.HALL&&Objects.equals(panel.hallId,hall.id);}
    public static boolean today(ManagerState.Show show,String zone,long now){
        try{var day=Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).toLocalDate();long start=day.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli(),end=day.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
            return show!=null&&show.start<end&&show.end>start&&show.end>show.start;
        }catch(RuntimeException unavailable){return false;}
    }
    public static CinemaProgramme.View programme(ManagerTile manager,ManagerState.Hall hall){
        try{
            var terminal=manager.programmeTerminal(hall.id);var grant=terminal.automationGrant();
            if(manager.owner==null||!terminal.ownedBy(manager.owner)||grant==null||!grant.matches(terminal.identity(),manager.identity,manager.owner,manager.getBlockPos()))return null;
            return manager.playback.terminalView(terminal);
        }catch(RuntimeException unavailable){return null;}
    }
    public static String status(ManagerTile manager,ManagerState.Hall hall,CinemaProgramme.View programme){
        try{manager.programmeTerminal(hall.id);}catch(RuntimeException unavailable){return "UNAVAILABLE";}
        return programme!=null?code(programme.status()):manager.playback.present(hall.id)?"UNAVAILABLE":"NO_PROGRAMME";
    }
    public static List<ManagerState.Show> plans(InfoTile panel,ManagerTile manager,long now){
        String zone=zone(manager.state.timezone);Set<String> halls=new HashSet<>();
        for(var hall:manager.state.halls)if(includes(panel,hall))halls.add(hall.id);
        return manager.state.shows.stream().filter(s->s!=null&&halls.contains(s.hallId)&&s.status!=null&&today(s,zone,now))
                .sorted(Comparator.comparingLong((ManagerState.Show s)->s.start).thenComparing(s->s.id)).limit(ManagerState.MAX_SHOWS).toList();
    }
    public static PublicOperationsSnapshot project(InfoTile panel,long now){
        if(!panel.isOperations())return EMPTY;
        ManagerTile manager=panel.controller();
        if(manager==null||manager.owner==null||!manager.owner.equals(panel.owner))return new PublicOperationsSnapshot("","",now,panel.manager==null?"UNBOUND":"UNAVAILABLE",List.of(),List.of());
        List<Hall> halls=new ArrayList<>();
        for(var hall:manager.state.halls)if(includes(panel,hall)){
            var current=programme(manager,hall);halls.add(new Hall(hall.name,status(manager,hall,current),current==null?"":current.title(),current==null?0:current.currentIndex()+1,current==null?0:current.clips().size()));
        }
        String zone=zone(manager.state.timezone);List<Plan> plans=plans(panel,manager,now).stream()
                .map(s->new Plan(manager.state.hall(s.hallId).name,s.title,s.start,s.end,s.status.name())).toList();
        return new PublicOperationsSnapshot(manager.state.name,zone,now,halls.isEmpty()?"EMPTY":zone.isEmpty()?"TIMEZONE_UNSET":"AVAILABLE",halls,plans);
    }
    public boolean sameContent(PublicOperationsSnapshot other){return other!=null&&cinema.equals(other.cinema)&&timezone.equals(other.timezone)&&state.equals(other.state)&&halls.equals(other.halls)&&plans.equals(other.plans)
            &&local.cinemamanager.panels.PublicTimeRange.dayKey(generatedAt,timezone).equals(local.cinemamanager.panels.PublicTimeRange.dayKey(other.generatedAt,other.timezone));}
    public CompoundTag save(){
        CompoundTag tag=new CompoundTag();tag.putString("cinema",cinema);tag.putString("timezone",timezone);tag.putLong("generated_at",generatedAt);tag.putString("state",state);
        ListTag hallTags=new ListTag();for(Hall row:halls){CompoundTag t=new CompoundTag();t.putString("name",row.name());t.putString("status",row.status());t.putString("playlist",row.playlist());t.putInt("clip",row.clip());t.putInt("clips",row.clips());hallTags.add(t);}tag.put("halls",hallTags);
        ListTag planTags=new ListTag();for(Plan row:plans){CompoundTag t=new CompoundTag();t.putString("hall",row.hall());t.putString("title",row.title());t.putString("status",row.status());t.putLong("start",row.start());t.putLong("end",row.end());planTags.add(t);}tag.put("plans",planTags);return tag;
    }
    public static PublicOperationsSnapshot read(CompoundTag tag){
        ListTag hallTags=tag.getList("halls",Tag.TAG_COMPOUND),planTags=tag.getList("plans",Tag.TAG_COMPOUND);
        if(hallTags.size()>ManagerState.MAX_HALLS||planTags.size()>ManagerState.MAX_SHOWS)throw invalid();
        List<Hall> halls=new ArrayList<>();for(int i=0;i<hallTags.size();i++){var t=hallTags.getCompound(i);halls.add(new Hall(t.getString("name"),t.getString("status"),t.getString("playlist"),t.getInt("clip"),t.getInt("clips")));}
        List<Plan> plans=new ArrayList<>();for(int i=0;i<planTags.size();i++){var t=planTags.getCompound(i);plans.add(new Plan(t.getString("hall"),t.getString("title"),t.getLong("start"),t.getLong("end"),t.getString("status")));}
        return new PublicOperationsSnapshot(tag.getString("cinema"),tag.getString("timezone"),tag.getLong("generated_at"),tag.getString("state"),halls,plans);
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("mycinema.operations.invalid");}
}
