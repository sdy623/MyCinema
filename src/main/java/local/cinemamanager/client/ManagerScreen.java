package local.cinemamanager.client;
import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.model.ScheduleBrowse;
import net.minecraft.client.gui.screens.ConfirmScreen;
import local.cinemamanager.model.ManagerState.*;
import me.srrapero720.waterui.screen.WaterScreen;
import local.cinemamanager.theater.CinemaProgramme;
import local.cinemamanager.theater.CinemaProgrammeNbt;
import local.cinemamanager.theater.CinemaProgrammeUi;
import local.cinemamanager.theater.CinemaProgramme.View;
import local.cinemamanager.theater.CinemaProgramme.Request;
import me.srrapero720.waterui.widget.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Uses the original bundled WaterUI widget toolkit and its WaterFrames theme. */
public final class ManagerScreen extends WaterScreen {
    public final BlockPos source;
    public final String identity;
    private final boolean readOnly, remote;
    private ManagerState state;
    private final Map<String,String> draft;
    private int page, hallIndex, ticks, lastReplyTick;
    private boolean all, libraryRequested;
    private int libraryRequestTick;
    private String localError="";
    private final local.cinemamanager.control.SeekDrag scrub=new local.cinemamanager.control.SeekDrag();
    private int dragStarted, seekSent=-1;
    private View pendingProgramme;
    private String pendingProgrammeTicket="";
    private int programmeSent;
    private boolean emergencyPending;
    private String emergencyTicket="";
    private int emergencySent;

    public ManagerScreen(ManagerNetwork.Snapshot p){this(p,p.readOnly()?3:0,new HashMap<>(),0,false);}
    private ManagerScreen(ManagerNetwork.Snapshot p,int page,Map<String,String> draft,int hallIndex,boolean all){
        super(CinemaManager.id("manager_"+page),448,page==2||page==0?472:428);this.source=p.source();this.identity=p.identity();this.readOnly=p.readOnly();this.remote=p.remote();this.state=StateCodec.decode(p.json());this.draft=draft;this.page=page;this.hallIndex=hallIndex;this.all=all;
        registerVar("heading",()->"MyCinema"+(readOnly?" · "+tr("readonly"):""));
        registerVar("emergency_status",()->tr(state.emergencyActive?"emergency.active_status":"emergency.normal_status",state.emergencyDoors,state.emergencyPendingDoors));
        registerVar("can_emergency_unlock",()->!readOnly&&state.emergencyAllowed&&!state.emergencyActive&&!emergencyPending);
        registerVar("can_emergency_restore",()->!readOnly&&state.emergencyAllowed&&state.emergencyActive&&!emergencyPending);
        registerVar("hall_status",()->hall()==null?tr("status.no_hall"):(this.all?tr("status.all")+" · ":"")+tx(hall().name)+" · "+tr(hall().open?"status.open":"status.closed")+" · "+deviceStatus(hall()));
        registerVar("door_status",()->hall()==null?tr("status.no_output"):tr("status.door",tr(hall().admissionHeld?"status.on":"status.off"),tx(hall().admissionOutputStatus),tr(hall().override?"status.manual":"status.auto")));
        registerVar("detail",()->hall()==null?tr("status.shared"):tx(hall().current)+" | "+deviceStatus(hall())+" | "+tx(ManagerState.presetName(hall().preset)));
        registerVar("notice",()->tx(localError.isBlank()?visibleNotice():localError));
        registerVar("zone_status",()->tr("status.zone",state.timezone.isBlank()?tr("status.unset"):state.timezone,tr(state.schedulerEnabled?"status.enabled":"status.disabled"))+(hall()==null?"":" | "+tr(hall().override?"status.manual":"status.auto")));
        registerVar("binding",()->hall()==null?tr("status.bind_hint"):tr("status.binding",tx(hall().name),BlockPos.of(hall().terminalPos).toShortString(),hall().outputs.size()));
        registerVar("show_status",this::showText);registerVar("show_reason",()->selectedShow()==null?"":tx(selectedShow().outcome));
        registerVar("source_origin",this::sourceOrigin);
        registerVar("schedule_filter",()->tr("schedule.filter",tr(allShows()?"schedule.all_halls":"schedule.current_hall")));
        registerVar("schedule_page",()->tr("schedule.page",schedulePage()+1,ScheduleBrowse.pages(scheduleShows()),scheduleShows().size()));
        registerVar("edit_heading",()->tr(draft.getOrDefault("editing_show","").isBlank()?"schedule.new_heading":"schedule.edit_heading",hall()==null?"":tx(hall().name)));
        registerVar("edit_impact",()->tr("schedule.edit_impact",state.timezone));
        for(int i=0;i<ScheduleBrowse.PAGE_SIZE;i++){int row=i;registerVar("show_row_"+i,()->rowText(row));}
        registerVar("preset_name",()->tr("status.preset",tx(ManagerState.presetName(editPreset()))));
        for(int i=0;i<7;i++){int line=i;registerVar("info"+i,()->infoLine(line));}
        registerVar("editable",!readOnly);
        registerVar("can_open_library",()->!readOnly&&!libraryRequested);
        registerVar("seek_label",this::seekLabel);
        registerVar("seek_hint",()->hall()!=null&&hall().programme!=null&&!all
                ? tr("programme.seek_hint") : tr("seek."+(all?"single_hall":hall()==null?"unavailable":hall().seekReason)));
        registerVar("programme_summary",()->hall()==null||hall().programme==null?tr("programme.unavailable"):CinemaProgrammeUi.summary(hall().programme).getString());
        registerVar("can_open_programme",()->!all&&hall()!=null&&hall().programme!=null);
        syncScrub();
        registerEvent("tab0",e->switchPage(0));registerEvent("tab1",e->switchPage(1));registerEvent("tab2",e->switchPage(2));registerEvent("tab3",e->switchPage(3));registerEvent("tab4",e->switchPage(4));registerEvent("done",e->onClose());
        registerEvent("emergency_unlock",e->requestEmergency(true));
        registerEvent("emergency_restore",e->requestEmergency(false));
        registerEvent("previous",e->select(-1));registerEvent("next",e->select(1));registerEvent("scope",e->{this.all=!this.all;scrub.cancel();syncScrub();});
        // WaterUI resolves every registered element event against this page's document.
        // Navigation is shared; action callbacks belong only to the page containing their buttons.
        switch(page){
            case 0 -> {
                for(Scene s:Scene.values())registerEvent(s.name().toLowerCase(Locale.ROOT),e->scene(s));
                registerEvent("programme",e->openProgramme());
                registerEvent("load",e->{this.all=false;var a=args();a.putString("url",value("url"));a.putString("title",value("title"));send("media",a);});
                registerEvent("load_show",e->confirmShow("load_show"));
            }
            case 1 -> {
                registerEvent("nearest",e->send("nearest",args()));
                registerEvent("rename",e->{var a=args();a.putString("value",value("name"));send("name",a);});
                registerEvent("remove",e->{this.all=false;send("remove",args());});
            }
            case 2 -> {
                registerEvent("films",e->openFilmLibrary());
                registerEvent("zone_save",e->{var a=args();a.putString("value",value("timezone"));send("timezone",a);});
                registerEvent("enable",e->{var a=args();a.putBoolean("enabled",true);send("scheduler",a);});
                registerEvent("disable",e->{var a=args();a.putBoolean("enabled",false);send("scheduler",a);});
                for(int i=0;i<ScheduleBrowse.PAGE_SIZE;i++){int row=i;registerEvent("show_row_"+i,e->selectShowRow(row));}
                registerEvent("filter",e->{draft.put("all_shows",Boolean.toString(!allShows()));draft.put("schedule_page","0");syncSchedule();});
                registerEvent("show_previous",e->changeSchedulePage(-1));registerEvent("show_next",e->changeSchedulePage(1));
                registerEvent("add_show",e->openEditor(false));registerEvent("edit_show",e->openEditor(true));
                registerEvent("load_show",e->confirmShow("load_show"));
                registerEvent("cancel",e->confirmShow("cancel"));registerEvent("delete_show",e->confirmShow("delete_show"));
                registerEvent("arm_show",e->confirmShow("arm_show"));
                registerEvent("show_details",e->showDetails());
            }
            case 5 -> {
                registerEvent("save_show",e->saveShow());
                registerEvent("editor_back",e->switchPage(2));
            }
            case 3 -> registerEvent("programme",e->openProgramme());
            case 4 -> {
                for(LightPreset preset:LightPreset.values())registerEvent("preset_"+preset.name().toLowerCase(Locale.ROOT),e->{var a=args();a.putString("preset",preset.name());send("preset",a);});
                registerEvent("preset_previous",e->selectPreset(-1));registerEvent("preset_next",e->selectPreset(1));
                registerEvent("preset_save",e->{try{this.all=false;var a=args();a.putString("preset",editPreset().name());for(String group:List.of("main","aisle","exit","accent"))a.putInt(group,Integer.parseInt(value("light_"+group)));send("save_preset",a);}catch(NumberFormatException ex){localError=tr("error.lights");}});
            }
            default -> throw new IllegalArgumentException("Unknown manager page: "+page);
        }
    }
    private void syncEmergencyUi(){
        if(get(CinemaManager.id("emergency_status")) instanceof Text label)label.color(state.emergencyActive?0xFFFF5555:0);
    }
    private void requestEmergency(boolean active){
        if(readOnly||!state.emergencyAllowed||ticks-lastReplyTick>200||emergencyPending||state.emergencyActive==active)return;
        long revision=state.emergencyRevision;
        Runnable commit=()->{
            if(readOnly||!state.emergencyAllowed||ticks-lastReplyTick>200||emergencyPending||state.emergencyRevision!=revision||state.emergencyActive==active){localError=tr("emergency.stale");return;}
            CompoundTag args=new CompoundTag();args.putLong("emergency_revision",revision);args.putBoolean("confirmed",!active);
            emergencyTicket=UUID.randomUUID().toString();args.putString("ticket",emergencyTicket);
            emergencyPending=true;emergencySent=ticks;send(active?"emergency_unlock":"emergency_restore",args);
        };
        if(active)commit.run();else confirm("mycinema.emergency.restore",tr("emergency.confirm_restore"),commit);
    }
    private void openProgramme(){
        Hall selected=hall();if(all||selected==null||selected.programme==null)return;
        scrub.cancel();syncScrub();String target=selected.id;
        openDialog(new CinemaProgrammeDialog(()->programme(target),request->sendProgramme(target,request),
                ()->!readOnly&&!all&&hall()!=null&&hall().id.equals(target)&&ticks-lastReplyTick<=200&&pendingProgramme==null));
    }
    private View programme(String hallId){
        return state.halls.stream().filter(h->h.id.equals(hallId)).findFirst().map(h->h.programme).orElse(null);
    }
    private void sendProgramme(String hallId,Request request){
        View latest=programme(hallId);
        if(readOnly||all||hall()==null||!hall().id.equals(hallId)||ticks-lastReplyTick>200||pendingProgramme!=null||latest==null
                ||!request.run().equals(latest.run())||request.operationRevision()!=latest.operationRevision()||request.currentIndex()!=latest.currentIndex()){
            localError=tr("programme.stale");return;
        }
        CompoundTag a=new CompoundTag();a.putString("hall",hallId);a.put("programme_request",CinemaProgrammeNbt.writeRequest(request));
        pendingProgrammeTicket=UUID.randomUUID().toString();a.putString("ticket",pendingProgrammeTicket);
        pendingProgramme=latest;programmeSent=ticks;send("programme_control",a);syncScrub();
    }
    private void scene(Scene scene){
        if(scene==Scene.PLAY||scene==Scene.PAUSE||scene==Scene.STOP){
            Hall selected=hall();View current=selected==null?null:selected.programme;
            if(all&&state.halls.stream().anyMatch(h->h.programme!=null)){localError=tr("programme.single_hall");return;}
            if(current!=null){
                sendProgramme(selected.id,new Request(CinemaProgramme.Action.valueOf(scene.name()),current.run(),current.operationRevision(),current.currentIndex(),0));
                return;
            }
        }
        CompoundTag a=args();a.putString("scene",scene.name());send("scene",a);
    }
    private void syncProgrammeControls(){
        boolean wasPending=pendingProgramme!=null;
        // State changes and unrelated refreshes are not acknowledgement of this exact request.
        if(pendingProgramme!=null&&ticks-programmeSent>100){pendingProgramme=null;pendingProgrammeTicket="";localError=tr("seek.no_reply");}
        if(wasPending!=(pendingProgramme!=null))syncScrub();
        if(page==0){
            boolean managedAll=all&&state.halls.stream().anyMatch(h->h.programme!=null);
            View current=hall()==null?null:hall().programme;
            boolean ready=!managedAll&&pendingProgramme==null;
            boolean active=current==null||CinemaProgrammeUi.active(current);
            for(String id:List.of("play","pause"))setEnabled(id,ready&&active);
            // Ended playback may still project its last image. Explicit Stop must remain reachable.
            setEnabled("stop",current==null?ready:ProgrammeStopPolicy.enabled(!readOnly,!all,
                    ticks-lastReplyTick<=200,pendingProgramme!=null,current.status()));
        }
    }
    private void openFilmLibrary(){
        if(readOnly||libraryRequested)return;
        CompoundTag args=new CompoundTag();
        args.putString("hall",hall()==null?"":hall().id);
        args.putString("show",selectedShow()==null?"":selectedShow().id);
        libraryRequested=true;libraryRequestTick=ticks;send("films",args);
        localError=tr("catalog.opening");
    }
    /** Only the matching outstanding navigation may consume an opening reply. */
    public boolean acceptFilmLibraryOpen(){
        if(!libraryRequested||ticks-libraryRequestTick>100)return false;
        libraryRequested=false;localError="";return true;
    }
    /** Resume the same manager draft/selection and request a fresh server snapshot. */
    public void returnFromFilmLibrary(){
        libraryRequested=false;lastReplyTick=ticks;
        minecraft.setScreen(this);send("refresh",new CompoundTag());
    }
    /** The caller names one stable hall, so programme playback controls cannot target All halls. */
    public void controlsFromFilmLibrary(String hallId){
        int target=-1;for(int i=0;i<state.halls.size();i++)if(state.halls.get(i).id.equals(hallId)){target=i;break;}
        if(target<0){returnFromFilmLibrary();return;}
        clearSelectedMedia();
        var snapshot=new ManagerNetwork.Snapshot(source,identity,readOnly,remote,true,StateCodec.encode(state));
        var controls=new ManagerScreen(snapshot,0,draft,target,false);
        minecraft.setScreen(controls);controls.send("refresh",new CompoundTag());
    }
    private static String transport(Transport value){return tr("transport."+value.name().toLowerCase(Locale.ROOT));}
    private Hall hall(){return state.halls.isEmpty()?null:state.halls.get(Math.clamp(hallIndex,0,state.halls.size()-1));}
    private Show selectedShow(){return hall()==null?null:ScheduleBrowse.selected(state,draft.getOrDefault("selected_show",""),hall().id);}
    private boolean allShows(){return Boolean.parseBoolean(draft.getOrDefault("all_shows","false"));}
    private List<Show> scheduleShows(){return ScheduleBrowse.filtered(state,allShows()?"":hall()==null?"missing":hall().id);}
    private int schedulePage(){int requested;try{requested=Integer.parseInt(draft.getOrDefault("schedule_page","0"));}catch(NumberFormatException e){requested=0;}return ScheduleBrowse.clampPage(scheduleShows(),requested);}
    private void changeSchedulePage(int delta){draft.put("schedule_page",Integer.toString(ScheduleBrowse.clampPage(scheduleShows(),schedulePage()+delta)));syncSchedule();}
    private String rowText(int row){
        var list=ScheduleBrowse.page(scheduleShows(),schedulePage());if(row>=list.size())return "";
        Show show=list.get(row);String prefix=show.id.equals(draft.getOrDefault("selected_show",""))?"▶ ":"";
        return prefix+state.format(show.start)+" · "+tx(state.hall(show.hallId).name)+" · "+show.title+" · "+tr("show."+show.status.name().toLowerCase(Locale.ROOT));
    }
    private void selectShowRow(int row){
        var list=ScheduleBrowse.page(scheduleShows(),schedulePage());if(row>=list.size())return;
        Show show=list.get(row);draft.put("selected_show",show.id);
        for(int i=0;i<state.halls.size();i++)if(state.halls.get(i).id.equals(show.hallId)){hallIndex=i;break;}
        all=false;copySelectedMedia(show);scrub.cancel();syncScrub();syncSchedule();localError="";
    }
    private void copySelectedMedia(Show show){
        draft.put("url",show.url);draft.put("title",show.title);
        if(get(CinemaManager.id("url")) instanceof InputText input)input.text(show.url);
        if(get(CinemaManager.id("title")) instanceof InputText input)input.text(show.title);
    }
    private String showText(){Show show=selectedShow();return show==null?tr("schedule.select_hint"):state.format(show.start)+"–"+state.format(show.end)+" · "+show.title+" · "+tr("show."+show.status.name().toLowerCase(Locale.ROOT))+(show.outcome.isBlank()?"":" · "+tx(show.outcome));}
    private String sourceOrigin(){
        Show show=selectedShow();if(show==null)return tr("schedule.typed_source");
        boolean same=value("url").equals(show.url)&&value("title").equals(show.title);
        return tr(same?"schedule.saved_source":"schedule.edited_source",tx(state.hall(show.hallId).name),show.title);
    }
    private void openEditor(boolean edit){
        Show show=edit?selectedShow():null;
        if(hall()==null || edit&&show==null){localError=tr("schedule.select_hint");return;}
        if(edit&&(ManagerState.active(show)||show.status!=Status.PENDING||show.start<=state.serverTime)){localError=tr(ManagerState.active(show)?"schedule.active_edit":"schedule.history_edit");return;}
        draft.put("editing_show",show==null?"":show.id);draft.put("editing_hall",hall().id);draft.put("editing_revision",Long.toString(state.revision));
        draft.put("show_title",show==null?"":show.title);draft.put("show_url",show==null?"":show.url);
        draft.put("show_time",show==null?state.inputTime(((state.serverTime/60000)+2)*60000):state.inputTime(show.start));
        draft.put("show_minutes",show==null?"120":Long.toString((show.end-show.start)/60000));switchPage(5);
    }
    private CompoundTag showRequest(Show show){CompoundTag a=new CompoundTag();a.putString("show",show.id);a.putLong("revision",state.revision);return a;}
    private void confirmShow(String action){
        Show show=selectedShow();if(show==null){localError=tr("schedule.select_hint");return;}
        if(action.equals("delete_show")&&ManagerState.active(show)){localError=tr("schedule.active_delete");return;}
        CompoundTag a=showRequest(show);if(!action.equals("load_show"))a.putBoolean("confirmed",true);
        String key=action.equals("arm_show")?"schedule.confirm_arm":action.equals("load_show")?"schedule.confirm_load":action.equals("delete_show")?"schedule.confirm_delete":ManagerState.active(show)?"schedule.confirm_cancel_active":"schedule.confirm_cancel";
        confirm(tr(key,tx(state.hall(show.hallId).name),show.title,state.format(show.start),show.url),()->send(action,a));
    }
    private void showDetails(){
        Show show=selectedShow();if(show==null)return;
        Hall hall=state.hall(show.hallId);
        String body=showText()+"\n\n"+tr("status.zone",state.timezone,tr(state.schedulerEnabled?"status.enabled":"status.disabled"))+"\n"+tr(hall.override?"status.manual":"status.auto")+"\n\n"+tr("schedule.reason_help");
        minecraft.setScreen(new ContinuousConfirmation(ignored->minecraft.setScreen(this),Component.translatable("mycinema.schedule.details"),Component.literal(body),Component.translatable("gui.done"),Component.translatable("gui.back")));
    }
    private void saveShow(){
        try{
            CompoundTag a=new CompoundTag();a.putString("hall",draft.getOrDefault("editing_hall",""));a.putLong("revision",Long.parseLong(draft.getOrDefault("editing_revision","-1")));
            String id=draft.getOrDefault("editing_show","");a.putString("show",id);
            a.putString("title",value("show_title"));a.putString("url",value("show_url"));a.putString("time",value("show_time"));a.putInt("minutes",Integer.parseInt(value("show_minutes")));
            confirm(tr(id.isBlank()?"schedule.confirm_create":"schedule.confirm_edit",a.getString("title"),a.getString("time"),state.timezone),()->{send(id.isBlank()?"schedule":"edit_show",a);switchPage(2);});
        }catch(NumberFormatException e){localError=tr("error.minutes");}
    }
    private void confirm(String message,Runnable action){confirm("mycinema.schedule.confirm_title",message,action);}
    private void confirm(String titleKey,String message,Runnable action){
        minecraft.setScreen(new ContinuousConfirmation(accepted->{minecraft.setScreen(this);if(accepted)action.run();},Component.translatable(titleKey),Component.literal(message)));
    }
    private void syncSchedule(){
        if(page==2){
            var rows=ScheduleBrowse.page(scheduleShows(),schedulePage());
            for(int i=0;i<ScheduleBrowse.PAGE_SIZE;i++)if(get(CinemaManager.id("show_row_"+i)) instanceof Button button){
                String full=rowText(i);button.text(Component.literal(font==null?full:font.plainSubstrByWidth(full,Math.max(60,button.bounds.width>0?button.bounds.width-20:410))));
                button.enabled=i<rows.size();button.tooltip(List.of(Component.literal(full)));
            }
            if(get(CinemaManager.id("filter")) instanceof Button button)button.text(Component.translatable("mycinema.schedule.filter",tr(allShows()?"schedule.all_halls":"schedule.current_hall")));
            setEnabled("show_previous",schedulePage()>0);setEnabled("show_next",schedulePage()+1<ScheduleBrowse.pages(scheduleShows()));
            setEnabled("add_show",hall()!=null);
            for(String action:List.of("edit_show","delete_show","cancel","load_show","arm_show","show_details"))setEnabled(action,selectedShow()!=null);
        }
        if(page==0)setEnabled("load_show",selectedShow()!=null);
    }
    private void setEnabled(String id,boolean enabled){if(get(CinemaManager.id(id)) instanceof Button button)button.enabled=!readOnly&&enabled;}
    private void clearSelectedMedia(){
        if(draft.remove("selected_show")==null)return;
        draft.remove("url");draft.remove("title");
        if(get(CinemaManager.id("url")) instanceof InputText input)input.text("");
        if(get(CinemaManager.id("title")) instanceof InputText input)input.text("");
    }
    private String visibleNotice(){
        var halls=state.halls.stream().map(h->new ProgrammeStopPolicy.HallStatus(
                h.programme==null?null:h.programme.status(),h.programme==null?null:h.programme.failureCode(),h.issue)).toList();
        return ProgrammeStopPolicy.hideObsoletePendingNotice(state.notice,halls,
                state.shows.stream().anyMatch(s->s.status==Status.STOP_PENDING))?"":state.notice;
    }
    private String deviceStatus(Hall hall){
        if("mycinema.catalog.programme_delayed".equals(hall.issue)){
            if(hall.transport==Transport.PLAYING)return tr("catalog.programme_playing_late");
            if(hall.deviceState.equals("mycinema.catalog.programme_preparing")||hall.deviceState.equals("mycinema.catalog.programme_ready"))return tr("catalog.programme_preparing_late");
            if(hall.transport==Transport.PAUSED)return tr("catalog.programme_paused_late");
        }
        return tx(hall.deviceState);
    }
    private String infoLine(int line){
        if(line==0)return tr("info.clock",java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(state.timezone.isBlank()?java.time.ZoneOffset.UTC:java.time.ZoneId.of(state.timezone)).format(java.time.Instant.ofEpochMilli(state.serverTime)),state.timezone.isBlank()?"UTC ("+tr("status.unset")+")":state.timezone);
        if(hall()==null)return line==1?tr("status.no_hall"):"";
        Hall h=hall();Show next=state.next(h,state.serverTime>0?state.serverTime:System.currentTimeMillis());
        return switch(line){
            case 1->tr("info.title",tx(h.current))+state.shows.stream().filter(show->show.hallId.equals(h.id)&&(show.status==Status.PREPARING||show.status==Status.RUNNING||show.status==Status.STOP_PENDING)).findFirst().map(show->" | "+tr("info.current",state.format(show.start),state.format(show.end))).orElse("");
            case 2->tr("info.next",next==null?tr("status.none"):state.format(next.start)+" "+next.title);
            case 3->tr("info.admission",tr(h.open?"status.open":"status.closed"),tr(h.admissionHeld?"status.on":"status.off"),tx(h.admissionOutputStatus));
            case 4->tr("info.media",tx(h.deviceState),h.durationMs>0?clockText(h.durationMs):tr("status.unconfirmed"));
            case 5->h.issue.isBlank()?tr("info.offline"):tx(h.issue);
            case 6->tr("info.exit");default->"";
        };
    }
    private static final java.util.regex.Pattern CATALOG_MESSAGE=java.util.regex.Pattern.compile("\\{tr:(mycinema\\.catalog\\.[a-z0-9_]+)\\}|(mycinema\\.catalog\\.[a-z0-9_]+)");
    /** Owner diagnostics show a translated explanation or bounded reason code, never a raw key. */
    static String catalogMessage(String key){
        if(key==null||!key.matches("mycinema\\.catalog\\.[a-z0-9_]{1,160}"))return tr("catalog.invalid");
        if(net.minecraft.client.resources.language.I18n.exists(key))return net.minecraft.client.resources.language.I18n.get(key);
        String reason=key.substring("mycinema.catalog.".length());
        if(reason.startsWith("stop_unconfirmed_"))return tr("catalog.stop_unconfirmed",catalogMessage("mycinema.catalog."+reason.substring("stop_unconfirmed_".length())));
        String sourceReason=reason;
        while(sourceReason.startsWith("source_")){
            sourceReason=sourceReason.substring("source_".length());
            String translated="mycinema.catalog."+sourceReason;
            if(net.minecraft.client.resources.language.I18n.exists(translated))return net.minecraft.client.resources.language.I18n.get(translated);
        }
        return tr("catalog.diagnostic",reason);
    }
    private static String tx(String value){
        if(value==null)return "";
        var matcher=CATALOG_MESSAGE.matcher(value);StringBuffer out=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(catalogMessage(matcher.group(1)==null?matcher.group(2):matcher.group(1))));
        matcher.appendTail(out);return MessageText.display(out.toString());
    }
    private static String tr(String key,Object... args){return net.minecraft.client.resources.language.I18n.get("mycinema."+key,args);}
    private static String clockText(long ms){long seconds=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);}
    private String seekLabel(){return (scrub.dragging()?tr("seek.preview")+" ":"")+clockText(scrub.preview())+" / "+(scrub.duration()>0?clockText(scrub.duration()):"--:--:--")+(scrub.pending()?" · "+tr("seek.waiting"):"");}
    private void syncScrub(){
        Hall h=hall();scrub.update(h==null?null:new local.cinemamanager.control.SeekDrag.View(h.id,h.seekSession,h.seekToken,h.programme==null?h.durationMs:h.programme.clipDurationMs(),h.programme==null?h.playheadMs:h.programme.clipPositionMs(),!readOnly&&!all&&pendingProgramme==null&&!h.seekToken.isBlank()&&(h.programme==null||CinemaProgrammeUi.seekable(h.programme))));
        if(get(CinemaManager.id("seek_bar")) instanceof Slider slider){
            slider.enabled=!readOnly&&!all&&pendingProgramme==null&&h!=null&&!h.seekToken.isBlank()&&!scrub.pending()&&(h.programme==null||CinemaProgrammeUi.seekable(h.programme));
            if(!scrub.dragging()&&!scrub.pending())slider.range(0,Math.max(1,scrub.duration())).value(scrub.preview());
        }
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(topDialog()==null&&page==0&&get(CinemaManager.id("seek_bar")) instanceof Slider slider&&slider.bounds.contains(x,y)){
            if(button!=0)return true; // Avoid the generic numeric editor: this action requires a frozen drag lease.
            if(!scrub.begin())return true;dragStarted=ticks;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        boolean result=super.mouseReleased(x,y,button);
        if(button==0&&scrub.dragging()){
            if(get(CinemaManager.id("seek_bar")) instanceof Slider slider)scrub.move(Math.round(slider.value()));
            var commit=scrub.release();
            if(commit!=null){CompoundTag a=new CompoundTag();a.putString("hall",commit.hall());a.putString("ticket",commit.ticket());a.putLong("target",commit.target());seekSent=ticks;send("seek",a);}
            syncScrub();
        }
        return result;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==256&&scrub.dragging()){scrub.cancel();syncScrub();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override protected void init(){if(scrub.dragging())scrub.cancel();super.init();}
    @Override public void removed(){scrub.cancel();super.removed();}
    @Override protected void build(){
        if(get(CinemaManager.id("seek_bar")) instanceof Slider slider)slider.onChange(v->scrub.move(Math.round(v))).format((v,max)->clockText(Math.round(v)));
        syncScrub();
        for(String key:List.of("name","url","title","timezone","time","minutes","light_main","light_aisle","light_exit","light_accent","show_title","show_url","show_time","show_minutes"))if(get(CinemaManager.id(key)) instanceof InputText input){
            String fallback=switch(key){case "name"->tx(hall()==null?state.name:hall().name);case "timezone"->state.timezone;case "minutes"->"120";case "light_main"->presetValue(0);case "light_aisle"->presetValue(1);case "light_exit"->presetValue(2);case "light_accent"->presetValue(3);default->"";};
            input.text(draft.getOrDefault(key,fallback)).maxLength(key.equals("url")||key.equals("show_url")?2048:80).onChange(v->draft.put(key,v));
        }
        syncSchedule();syncProgrammeControls();syncEmergencyUi();
    }
    private LightPreset editPreset(){try{return LightPreset.valueOf(draft.getOrDefault("edit_preset","FEATURE"));}catch(IllegalArgumentException e){return LightPreset.FEATURE;}}
    private String presetValue(int group){return Integer.toString((hall()==null?ManagerState.defaultPresets():hall().presets).get(editPreset().name())[group]);}
    private void selectPreset(int delta){LightPreset[] values=LightPreset.values();draft.put("edit_preset",values[Math.floorMod(editPreset().ordinal()+delta,values.length)].name());String[] keys={"light_main","light_aisle","light_exit","light_accent"};for(int i=0;i<4;i++){draft.remove(keys[i]);if(get(CinemaManager.id(keys[i])) instanceof InputText input)input.text(presetValue(i));}}
    private String value(String key){if(get(CinemaManager.id(key)) instanceof InputText input)return input.text().trim();return draft.getOrDefault(key,"").trim();}
    private CompoundTag args(){CompoundTag a=new CompoundTag();a.putString("hall",all?"*":hall()==null?"":hall().id);return a;}
    private void send(String action,CompoundTag args){if(readOnly&&!action.equals("refresh"))return;if(!action.equals("refresh"))localError="";PacketDistributor.sendToServer(new ManagerNetwork.Request(source,identity,action,args));}
    private void select(int delta){scrub.cancel();if(!state.halls.isEmpty())hallIndex=Math.floorMod(hallIndex+delta,state.halls.size());all=false;draft.remove("selected_show");draft.remove("url");draft.remove("title");draft.put("schedule_page","0");if(get(CinemaManager.id("url")) instanceof InputText input)input.text("");if(get(CinemaManager.id("title")) instanceof InputText input)input.text("");selectPreset(0);draft.remove("name");if(get(CinemaManager.id("name")) instanceof InputText input&&hall()!=null)input.text(tx(hall().name));syncScrub();}
    private void switchPage(int next){scrub.cancel();if(readOnly&&next!=3)return;var p=new ManagerNetwork.Snapshot(source,identity,readOnly,remote,true,StateCodec.encode(state));minecraft.setScreen(new ManagerScreen(p,next,draft,hallIndex,all));}
    /** QA-only page traversal uses the same constructor and document loading as a real tab click. */
    void qaSwitchPage(int next){if(Boolean.getBoolean("mycinema.clientQa"))switchPage(next);}
    public void update(ManagerNetwork.Snapshot p){
        lastReplyTick=ticks;String before=hall()==null?"":hall().id;var incoming=StateCodec.decode(p.json());
        if(incoming.snapshotSequence<state.snapshotSequence)return;state=incoming;
        if(emergencyPending&&!emergencyTicket.isEmpty()&&emergencyTicket.equals(incoming.seekAcknowledgement)){emergencyPending=false;emergencyTicket="";localError="";}
        if(pendingProgramme!=null&&!pendingProgrammeTicket.isEmpty()&&pendingProgrammeTicket.equals(incoming.seekAcknowledgement)){
            pendingProgramme=null;pendingProgrammeTicket="";localError="";
        }else if(scrub.acknowledge(incoming.seekAcknowledgement))seekSent=-1;
        int previousHall=-1;for(int i=0;i<state.halls.size();i++)if(state.halls.get(i).id.equals(before)){previousHall=i;break;}
        hallIndex=previousHall>=0?previousHall:Math.clamp(hallIndex,0,Math.max(0,state.halls.size()-1));
        if(selectedShow()==null)clearSelectedMedia();
        String after=hall()==null?"":hall().id;
        if(!before.equals(after)){clearSelectedMedia();draft.remove("name");selectPreset(0);if(get(CinemaManager.id("name")) instanceof InputText input)input.text(tx(hall()==null?state.name:hall().name));}
        draft.put("schedule_page",Integer.toString(schedulePage()));syncScrub();syncSchedule();syncEmergencyUi();
    }
    @Override public void tick(){if(emergencyPending&&ticks-emergencySent>100){emergencyPending=false;emergencyTicket="";localError=tr("emergency.no_reply");}if(libraryRequested&&ticks-libraryRequestTick>100){libraryRequested=false;localError=tr("catalog.open_timeout");}if(scrub.dragging()&&(!minecraft.isWindowActive()||org.lwjgl.glfw.GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)!=org.lwjgl.glfw.GLFW.GLFW_PRESS)){scrub.cancel();syncScrub();}super.tick();syncSchedule();syncProgrammeControls();if(minecraft.player==null||minecraft.level==null||(!remote&&minecraft.player.distanceToSqr(source.getX()+.5,source.getY()+.5,source.getZ()+.5)>64)||(remote&&!TabletItem.boundTo(minecraft.player,source,identity))){onClose();return;}if(++ticks==10)QaClientEvidence.ui(this,page);if(scrub.dragging()&&!minecraft.isWindowActive()){scrub.cancel();syncScrub();}if(ticks-lastReplyTick>200){minecraft.player.displayClientMessage(Component.translatable("mycinema.error.disconnected"),true);onClose();return;}if(scrub.dragging()&&ticks-dragStarted>280){scrub.cancel();localError=tr("seek.expired");syncScrub();}if(scrub.pending()&&ticks-seekSent>100){scrub.cancel();localError=tr("seek.no_reply");syncScrub();}if(ticks%40==0)send("refresh",new CompoundTag());}
}
