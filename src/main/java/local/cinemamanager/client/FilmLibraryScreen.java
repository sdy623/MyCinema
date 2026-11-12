package local.cinemamanager.client;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.library.FilmNetwork;
import me.srrapero720.waterui.layout.ParentLinear;
import me.srrapero720.waterui.screen.WaterScreen;
import me.srrapero720.waterui.widget.InputText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Owner-only library UI. Mutable drafts never supply media metadata or scheduled snapshots. */
public final class FilmLibraryScreen extends WaterScreen {
    private static final int BROWSE=0, EDIT=1, PREPARE=2, SCHEDULE=3;
    private static final int ROWS=FilmNetwork.PAGE_SIZE;
    private static final String NEW_FILM="new";
    private final Session session;
    private final int page;
    private boolean restoreScroll;
    private FilmPictureActions pictures;

    /** One session survives page changes, confirmation dialogs, and asynchronous responses. */
    private static final class Session {
        FilmNetwork.View view;
        FilmNetwork.Data data;
        final ManagerScreen back;
        final Map<String,FilmDraft> drafts=new HashMap<>();
        final Map<String,ScheduleDraft> schedules=new HashMap<>();
        final Map<Integer,Integer> scrollOffsets=new HashMap<>();
        String selectedId="", editorId="", hallId="", showId="", notice="";
        String request="", action="", requestFilm="", requestSource="";
        int sourceIndex, ticks, sentAt=-100, requestedPage;
        boolean pending, conflict, closed, reloadEditor, passiveRequest;
        Session(FilmNetwork.View view,FilmNetwork.Data data,ManagerScreen back){
            this.view=view;this.data=data;this.back=back;this.hallId=data.selectedHall();
            this.showId=data.selectedShow();this.requestedPage=data.page();
            if(data.selected()!=null)this.selectedId=data.selected().id();
        }
    }
    private static final class FilmDraft {
        final String id;
        final long filmRevision,catalogRevision;
        final FilmCatalog.Film original;
        String title,poster;
        final List<String> sources=new ArrayList<>();
        int selected,page;
        boolean conflict;
        FilmDraft(FilmCatalog.Film film,long catalogRevision){
            original=film;id=film==null?"":film.id();filmRevision=film==null?0:film.revision();
            this.catalogRevision=catalogRevision;title=film==null?"":film.title();poster=film==null?"":film.poster();
            if(film==null)sources.add("");else film.segments().forEach(s->sources.add(s.sourceKey()));
        }
        boolean dirty(){return original==null?!title.isBlank()||!poster.isBlank()||sources.size()!=1||!sources.getFirst().isBlank():
                !title.equals(original.title())||!poster.equals(original.poster())||!sources.equals(original.segments().stream().map(FilmCatalog.Segment::sourceKey).toList());}
    }
    private static final class ScheduleDraft {
        final String filmId;
        final long filmRevision,managerRevision;
        final String initialHall,initialShow,initialTime;
        String hallId,showId,time;
        boolean conflict;
        ScheduleDraft(FilmCatalog.Film film,FilmNetwork.Data data,String hallId,String showId){
            this.filmId=film.id();this.filmRevision=film.revision();this.managerRevision=data.managerRevision();
            this.hallId=hallId;this.showId=showId;this.time=defaultTime(data.timezone());
            FilmNetwork.Plan plan=data.plans().stream().filter(p->p.id().equals(showId)).findFirst().orElse(null);
            if(plan!=null){this.hallId=plan.hallId();this.time=plan.time();}else this.showId="";
            initialHall=this.hallId;initialShow=this.showId;initialTime=this.time;
        }
        boolean dirty(){return !hallId.equals(initialHall)||!showId.equals(initialShow)||!time.equals(initialTime);}
    }

    public static void open(FilmNetwork.View packet){
        Minecraft mc=Minecraft.getInstance();
        if(!packet.open()){
            if(mc.screen instanceof FilmLibraryScreen screen)screen.accept(packet);
            return;
        }
        // An old opening response must not reopen a dismissed or unrelated controller screen.
        if(!(mc.screen instanceof ManagerScreen manager)||!manager.source.equals(packet.source())||!manager.identity.equals(packet.identity()))return;
        try{
            FilmNetwork.Data data=FilmNetwork.JSON.fromJson(packet.json(),FilmNetwork.Data.class);
            if(!validData(data)||!data.editable()||!manager.acceptFilmLibraryOpen())return;
            mc.setScreen(new FilmLibraryScreen(new Session(packet,data,manager),BROWSE));
        }catch(RuntimeException ignored){/* Malformed/late packets do not replace the current screen. */}
    }

    private FilmLibraryScreen(Session session,int page){
        super(CinemaManager.id("film_"+page),448,348);
        this.session=session;this.page=page;
        registerVar("heading",()->"MyCinema · "+tr("title"));
        registerVar("notice",this::notice);
        registerVar("ready",this::ready);
        registerVar("can_reload",()->!session.pending);
        registerVar("can_select",()->ready()&&film()!=null);
        registerVar("selected",this::selectionText);
        registerVar("runtime",this::runtimeText);
        registerVar("hall",this::hallText);
        registerVar("has_hall",()->ready()&&!hallId().isBlank());
        registerVar("zone",()->tr("timezone",session.data.timezone().isBlank()?tr("timezone_unset"):session.data.timezone()));
        registerEvent("done",e->onClose());
        registerEvent("browse",e->navigate(BROWSE));
        registerEvent("reload",e->reload());
        switch(page){
            case BROWSE->registerBrowse();
            case EDIT->registerEditor();
            case PREPARE->registerPrepare();
            case SCHEDULE->registerSchedule();
            default->throw new IllegalArgumentException("Unknown library page");
        }
    }

    private void registerBrowse(){
        registerVar("page",()->tr("page",session.data.page()+1,session.data.pages()));
        registerVar("previous_page",()->ready()&&session.data.page()>0);
        registerVar("next_page",()->ready()&&session.data.page()+1<session.data.pages());
        registerVar("can_create",()->ready()&&session.data.editable());
        for(int i=0;i<ROWS;i++){
            int row=i;
            registerVar("film_row_"+i,()->filmRow(row));
            registerVar("film_available_"+i,()->ready()&&row<session.data.films().size());
            registerEvent("film_row_"+i,e->selectFilm(row));
        }
        registerEvent("previous_page",e->changePage(-1));
        registerEvent("next_page",e->changePage(1));
        registerEvent("new_film",e->openEditor(true));
        registerEvent("catalog_exchange",e->{if(ready())Minecraft.getInstance().setScreen(new CatalogExchangeScreen(this,session.view.source(),session.view.identity(),session.data.catalogRevision(),()->{session.conflict=false;send("view",arguments(),true);}));});
        registerEvent("edit_film",e->openEditor(false));
        registerEvent("delete_film",e->deleteFilm());
        registerEvent("prepare_page",e->navigate(PREPARE));
        registerEvent("schedule_page",e->navigate(SCHEDULE));
    }

    private void registerEditor(){
        final FilmDraft captured=draft();
        pictures=new FilmPictureActions(this,()->{
            if(session.closed||session.conflict||session.pending||captured!=draft()||captured.conflict)return null;
            var target=new local.cinemamanager.panels.assets.AssetNetwork.FilmTarget(session.view.source(),session.view.identity(),captured.id,captured.catalogRevision,captured.filmRevision);
            return new FilmPictureActions.Snapshot(PanelAssetClient.Context.film(Minecraft.getInstance().level,target),session.data.imageBudgetBytes(),session.data.worldBudgetBytes(),captured.poster);
        },reference->{
            captured.poster=reference;
            if(get(CinemaManager.id("film_poster")) instanceof InputText input)input.text(reference);
        },text->session.notice=text);
        registerVar("poster_status",()->posterStatus(draft().poster));
        registerVar("poster_busy",()->pictures.busy());
        registerEvent("poster_upload",e->{if(editReady())pictures.choose();});
        registerEvent("poster_cancel",e->{pictures.cancel();session.notice="";});
        registerVar("editor_heading",()->tr(draft().id.isBlank()?"new_heading":"edit_heading"));
        registerVar("draft_state",()->draft().conflict?tr("draft_conflict"):tr(draft().dirty()?"unsaved":"saved_revision",draft().filmRevision));
        registerVar("draft_editable",this::editReady);
        registerVar("can_add",()->editReady()&&draft().sources.size()<FilmCatalog.MAX_SEGMENTS);
        registerVar("can_remove",()->editReady()&&draft().sources.size()>1);
        registerVar("can_up",()->editReady()&&draft().selected>0);
        registerVar("can_down",()->editReady()&&draft().selected+1<draft().sources.size());
        registerVar("source_page",()->tr("source_page",draft().page+1,sourcePages(),draft().sources.size(),FilmCatalog.MAX_SEGMENTS));
        registerVar("source_selected",()->tr("source_number",draft().selected+1,draft().sources.size()));
        registerVar("source_previous",()->editReady()&&draft().page>0);
        registerVar("source_next",()->editReady()&&draft().page+1<sourcePages());
        for(int i=0;i<ROWS;i++){
            int row=i;
            registerVar("source_row_"+i,()->draftRow(row));
            registerVar("source_available_"+i,()->editReady()&&draft().page*ROWS+row<draft().sources.size());
            registerEvent("source_row_"+i,e->selectDraftSource(row));
        }
        registerEvent("source_previous",e->sourcePage(-1));
        registerEvent("source_next",e->sourcePage(1));
        registerEvent("source_add",e->addSource(false));
        registerEvent("source_duplicate",e->addSource(true));
        registerEvent("source_remove",e->removeSource());
        registerEvent("source_up",e->moveSource(-1));
        registerEvent("source_down",e->moveSource(1));
        registerEvent("save",e->save());
    }

    private void registerPrepare(){
        registerVar("source_selected",()->film()==null?tr("select_hint"):tr("source_number",session.sourceIndex+1,film().segments().size()));
        registerVar("source_text",()->segment()==null?"":segment().sourceKey());
        registerVar("metadata",()->segment()==null?tr("pending_metadata"):segment().live()?tr("live_metadata"):
                segment().durationMs().isPresent()?tr("source_runtime",clock(segment().durationMs().getAsLong())):tr("pending_metadata"));
        registerVar("source_previous",()->ready()&&film()!=null&&session.sourceIndex>0);
        registerVar("source_next",()->ready()&&film()!=null&&session.sourceIndex+1<film().segments().size());
        registerVar("can_prepare",this::canPrepare);
        registerVar("can_load_programme",this::canLoadProgramme);
        registerVar("can_authorize_programme",()->ready()&&hallDataCurrent()&&!session.hallId.isBlank()&&session.data.orderedPlayback());
        registerVar("can_authorize_local_programme",()->canPrepare()&&session.data.orderedPlayback());
        registerVar("can_revoke_local_programme",()->ready()&&hallDataCurrent()&&!session.hallId.isBlank()&&session.data.orderedPlayback());
        registerVar("can_claim_unowned_hall",()->ready()&&hallDataCurrent()&&!session.hallId.isBlank()&&session.data.claimReview()!=null&&session.data.claimReview().available());
        registerVar("hall_authority",()->hallDataCurrent()?message(session.data.programmeAuthority()):tr("waiting"));
        registerVar("local_consent_state",()->hallDataCurrent()&&filmDataCurrent()?message(session.data.localSourceConsent()):tr("waiting"));
        registerVar("claim_state",()->!hallDataCurrent()?tr("waiting"):session.data.claimReview()==null?"":message(session.data.claimReview().status()));
        registerVar("programme_state",this::programmeState);
        registerEvent("source_previous",e->selectPreparedSource(-1));
        registerEvent("source_next",e->selectPreparedSource(1));
        registerEvent("hall_previous",e->changeHall(-1));
        registerEvent("hall_next",e->changeHall(1));
        registerEvent("prepare_source",e->prepareSource());
        registerEvent("prepare_all",e->prepareAllSources());
        registerEvent("load_programme",e->loadProgramme());
        registerEvent("auth_programme",e->authorizeProgramme());
        registerEvent("claim_unowned_hall",e->claimUnownedHall());
        registerEvent("auth_local_programme",e->authorizeLocalProgramme());
        registerEvent("revoke_local_programme",e->revokeLocalProgramme());
        registerEvent("hall_controls",e->closeToControls());
        registerEvent("read_length",e->{if(canPrepare())send("read",arguments(),false);});
        registerEvent("schedule_page",e->navigate(SCHEDULE));
    }

    private void registerSchedule(){
        registerVar("schedule_editable",()->ready()&&!schedule().conflict);
        registerVar("schedule_ready",this::canSchedule);
        registerVar("replace_ready",()->canSchedule()&&selectedPlan()!=null);
        registerVar("reschedule_ready",()->ready()&&!schedule().conflict&&selectedPlan()!=null&&!session.data.timezone().isBlank());
        registerVar("schedule_state",this::scheduleState);
        registerVar("plan",this::planText);
        registerVar("plan_available",()->ready()&&!schedule().conflict&&!session.data.plans().isEmpty());
        registerEvent("hall_previous",e->changeHall(-1));
        registerEvent("hall_next",e->changeHall(1));
        registerEvent("plan_previous",e->changePlan(-1));
        registerEvent("plan_next",e->changePlan(1));
        registerEvent("schedule_new",e->scheduleFilm(false));
        registerEvent("schedule_replace",e->scheduleFilm(true));
        registerEvent("reschedule",e->reschedule());
    }

    private static boolean validData(FilmNetwork.Data data){
        return data!=null&&data.catalogRevision()>=0&&data.managerRevision()>=0&&data.timezone()!=null&&
                data.page()>=0&&data.pages()>data.page()&&data.films()!=null&&data.films().size()<=ROWS&&
                data.films().stream().allMatch(f->f!=null&&f.id()!=null&&f.title()!=null)&&
                data.halls()!=null&&data.halls().stream().allMatch(h->h!=null&&h.id()!=null&&h.name()!=null&&h.terminalIdentity()!=null)&&
                data.plans()!=null&&data.plans().stream().allMatch(p->p!=null&&p.id()!=null&&p.title()!=null&&p.hallId()!=null&&p.time()!=null&&p.filmSnapshot()!=null)&&
                data.selectedHall()!=null&&data.selectedShow()!=null&&data.notice()!=null;
    }
    private static String tr(String key,Object... arguments){return I18n.get("mycinema.catalog."+key,arguments);}
    private static String message(String key){return key!=null&&key.startsWith("mycinema.catalog.")?ManagerScreen.catalogMessage(key):key!=null&&key.matches("mycinema\\.[a-z0-9_.]+")&&I18n.exists(key)?I18n.get(key):tr("invalid");}
    private static String clock(long duration){
        if(duration<=0)return tr("pending_metadata");
        long seconds=duration/1000;
        return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
    }
    private static String defaultTime(String zone){
        try{return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",Locale.ROOT).withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli((System.currentTimeMillis()/60000+2)*60000));}
        catch(RuntimeException ignored){return "";}
    }
    private boolean ready(){return !session.pending&&!session.conflict&&!session.closed&&session.ticks-session.sentAt>=3&&session.data.editable();}
    private boolean editReady(){return ready()&&!draft().conflict&&(pictures==null||!pictures.busy());}
    private FilmCatalog.Film film(){return session.data.selected()!=null&&session.data.selected().id().equals(session.selectedId)?session.data.selected():null;}
    /** Authorization facts belong only to the hall/film named by the accepted server snapshot. */
    private boolean hallDataCurrent(){return session.hallId.equals(session.data.selectedHall());}
    private boolean filmDataCurrent(){return session.selectedId.isBlank()?session.data.selected()==null:film()!=null;}
    private FilmCatalog.Segment segment(){return film()==null||session.sourceIndex<0||session.sourceIndex>=film().segments().size()?null:film().segments().get(session.sourceIndex);}
    private FilmDraft draft(){return session.drafts.get(session.editorId);}
    private ScheduleDraft schedule(){return session.schedules.get(session.selectedId);}
    private String hallId(){return page==SCHEDULE?schedule().hallId:session.hallId;}
    private String hallText(){return session.data.halls().stream().filter(h->h.id().equals(hallId())).map(FilmNetwork.Hall::name).findFirst().orElse(tr("no_hall"));}
    private FilmNetwork.Plan selectedPlan(){return session.data.plans().stream().filter(p->p.id().equals(schedule().showId)).findFirst().orElse(null);}
    private int sourcePages(){return Math.max(1,(draft().sources.size()+ROWS-1)/ROWS);}

    private static String posterStatus(String raw){
        String normalized=local.cinemamanager.panels.PosterPolicy.normalize(raw);
        if(normalized==null)return tr("poster_invalid_type");
        if(normalized.isEmpty())return tr("poster_empty");
        if(local.cinemamanager.panels.assets.AssetReference.isAsset(normalized))return tr("poster_asset_type");
        if(local.cinemamanager.panels.PosterPolicy.resource(normalized))return tr("poster_resource_type");
        if(normalized.startsWith("http://")||normalized.startsWith("https://"))return tr("poster_web_type");
        return tr("poster_local_unpublished");
    }

    private String notice(){
        if(session.pending&&!session.passiveRequest)return tr("waiting");
        if(!session.notice.isBlank())return session.notice;
        if(session.conflict)return tr("reload_required");
        if(!session.data.notice().isBlank())return message(session.data.notice());
        return tr(page==EDIT?"draft_hint":page==PREPARE?"prepare_hint":page==SCHEDULE?"schedule_hint":"browse_hint");
    }
    private String selectionText(){return film()==null?tr("select_hint"):tr("selection",film().title(),film().revision());}
    private String runtimeText(){
        FilmCatalog.Film value=page==EDIT?draft().original:film();
        return value!=null&&value.totalDurationMs().isPresent()?tr("film_runtime_with_gaps",clock(value.requireDurationMs()),clock(programmeDuration(value))):tr("film_runtime",tr("pending_metadata"));
    }
    private static long programmeDuration(FilmCatalog.Film film){return Math.addExact(film.requireDurationMs(),Math.multiplyExact((long)film.segments().size()-1,FilmCatalog.DEFAULT_TRANSITION_GAP_MS));}
    private String filmRow(int row){
        if(row>=session.data.films().size())return row==0?tr("empty_library"):"";
        FilmNetwork.Summary film=session.data.films().get(row);
        return (film.id().equals(session.selectedId)?"▶ ":"")+film.title()+" · "+tr("row_status",film.prepared(),film.segments(),film.durationMs()>0?clock(film.durationMs()):tr("pending_metadata"));
    }
    private String draftRow(int row){
        int index=draft().page*ROWS+row;if(index>=draft().sources.size())return "";
        String source=draft().sources.get(index);
        return (index==draft().selected?"▶ ":"")+(index+1)+". "+(source.isBlank()?tr("empty_source"):source);
    }
    private String scheduleState(){
        if(schedule().conflict)return tr("schedule_conflict");
        if(film()==null)return tr("film_missing");
        if(!session.data.orderedPlayback()&&film().segments().size()>1)return tr("ordered_unavailable");
        if(!film().schedulable())return tr("duration_unknown");
        if(session.data.timezone().isBlank())return tr("timezone_required");
        return tr("snapshot_hint",schedule().filmRevision);
    }
    private String planText(){
        FilmNetwork.Plan plan=selectedPlan();
        if(plan==null)return tr("no_plan");
        String hall=session.data.halls().stream().filter(h->h.id().equals(plan.hallId())).map(FilmNetwork.Hall::name).findFirst().orElse(tr("no_hall"));
        return tr("plan_details",plan.title(),hall,plan.time(),plan.filmSnapshot().isBlank()?tr("existing_source_plan"):tr("saved_programme"));
    }

    @Override protected void init(){rememberScroll();restoreScroll=true;super.init();}
    @Override public void removed(){rememberScroll();if(pictures!=null)pictures.removed();super.removed();}
    private void rememberScroll(){
        if(get(CinemaManager.id("film_scroll")) instanceof ParentLinear content)session.scrollOffsets.put(page,content.scrollOffsetY());
    }
    @Override protected void afterReflow(){
        if(!restoreScroll)return;
        restoreScroll=false;
        if(get(CinemaManager.id("film_scroll")) instanceof ParentLinear content)content.scrollBy(0,session.scrollOffsets.getOrDefault(page,0)-content.scrollOffsetY());
    }
    @Override protected void build(){
        if(page==EDIT){
            bind("film_title",draft().title,FilmCatalog.MAX_TITLE_LENGTH,value->draft().title=value);
            bind("film_poster",draft().poster,FilmCatalog.MAX_SOURCE_LENGTH,value->draft().poster=value);
            bind("film_source",draft().sources.get(draft().selected),FilmCatalog.MAX_SOURCE_LENGTH,value->draft().sources.set(draft().selected,value));
        }else if(page==SCHEDULE)bind("schedule_time",schedule().time,32,value->schedule().time=value);
    }
    private void bind(String id,String value,int length,java.util.function.Consumer<String> changed){
        if(get(CinemaManager.id(id)) instanceof InputText input)input.maxLength(length).text(value).onChange(changed);
    }
    private void navigate(int target){
        if(!ready())return;
        if(target==SCHEDULE&&film()==null){session.notice=tr("select_hint");return;}
        if(target==PREPARE&&session.hallId.isBlank()){session.notice=tr("no_hall");return;}
        if(target==SCHEDULE)session.schedules.computeIfAbsent(session.selectedId,id->new ScheduleDraft(film(),session.data,session.hallId,session.showId));
        session.notice="";var next=new FilmLibraryScreen(session,target);Minecraft.getInstance().setScreen(next);
        if(target==PREPARE&&(!next.hallDataCurrent()||!next.filmDataCurrent()))next.send("view",next.arguments(),false);
    }
    private void rebuild(){Minecraft.getInstance().setScreen(new FilmLibraryScreen(session,page));}
    private void changePage(int delta){
        if(!ready())return;
        session.requestedPage=Math.clamp(session.data.page()+delta,0,session.data.pages()-1);
        send("view",arguments(),false);
    }
    private void selectFilm(int row){
        if(!ready()||row>=session.data.films().size())return;
        session.selectedId=session.data.films().get(row).id();session.sourceIndex=0;
        send("view",arguments(),false);
    }
    private void openEditor(boolean create){
        if(!ready()||!create&&film()==null)return;
        session.editorId=create?NEW_FILM:session.selectedId;
        session.drafts.computeIfAbsent(session.editorId,id->new FilmDraft(create?null:film(),session.data.catalogRevision()));
        navigate(EDIT);
    }
    private void selectDraftSource(int row){
        if(!editReady())return;
        int index=draft().page*ROWS+row;if(index>=draft().sources.size())return;
        draft().selected=index;rebuild();
    }
    private void sourcePage(int delta){if(editReady()){draft().page=Math.clamp(draft().page+delta,0,sourcePages()-1);rebuild();}}
    private void addSource(boolean duplicate){
        if(!editReady()||draft().sources.size()>=FilmCatalog.MAX_SEGMENTS)return;
        String value=duplicate?draft().sources.get(draft().selected):"";
        int index=duplicate?draft().selected+1:draft().sources.size();draft().sources.add(index,value);
        draft().selected=index;draft().page=index/ROWS;rebuild();
    }
    private void removeSource(){
        if(!editReady()||draft().sources.size()<2)return;
        draft().sources.remove(draft().selected);draft().selected=Math.min(draft().selected,draft().sources.size()-1);draft().page=draft().selected/ROWS;rebuild();
    }
    private void moveSource(int delta){
        if(!editReady())return;
        int from=draft().selected,to=from+delta;if(to<0||to>=draft().sources.size())return;
        String value=draft().sources.remove(from);draft().sources.add(to,value);draft().selected=to;draft().page=to/ROWS;rebuild();
    }
    private void selectPreparedSource(int delta){
        if(!ready()||film()==null)return;
        session.sourceIndex=Math.clamp(session.sourceIndex+delta,0,film().segments().size()-1);
    }
    private void changeHall(int delta){
        if(!ready()||session.data.halls().isEmpty()||page==SCHEDULE&&schedule().conflict)return;
        List<FilmNetwork.Hall> halls=session.data.halls();int index=0;
        for(int i=0;i<halls.size();i++)if(halls.get(i).id().equals(hallId()))index=i;
        String selected=halls.get(Math.floorMod(index+delta,halls.size())).id();
        session.hallId=selected;
        if(page==SCHEDULE){schedule().hallId=selected;schedule().showId="";session.showId="";}
        send("view",arguments(),false);
    }
    private void changePlan(int delta){
        if(!ready()||schedule().conflict||session.data.plans().isEmpty())return;
        List<FilmNetwork.Plan> plans=session.data.plans();int index=-1;
        for(int i=0;i<plans.size();i++)if(plans.get(i).id().equals(schedule().showId))index=i;
        int next=Math.floorMod(index+1+delta,plans.size()+1)-1;
        if(next<0){schedule().showId="";session.showId="";return;}
        String previousHall=session.hallId;
        FilmNetwork.Plan plan=plans.get(next);schedule().showId=plan.id();schedule().hallId=plan.hallId();schedule().time=plan.time();
        session.showId=plan.id();session.hallId=plan.hallId();
        if(get(CinemaManager.id("schedule_time")) instanceof InputText input)input.text(plan.time());
        if(!session.hallId.equals(previousHall))send("view",arguments(),false);
    }
    private void save(){
        if(!editReady())return;
        try{
            FilmDraft draft=draft();FilmNetwork.Arguments args=arguments();
            args.draft=new FilmCatalog.Draft(draft.title,draft.poster,draft.sources);
            args.filmId=draft.id;args.filmRevision=draft.filmRevision;args.catalogRevision=draft.catalogRevision;
            confirm(tr(draft.id.isBlank()?"confirm_create":"confirm_update",draft.title,draft.sources.size()),()->send(draft.id.isBlank()?"create":"update",args,false));
        }catch(RuntimeException failure){session.notice=message(failure.getMessage());}
    }
    private void deleteFilm(){
        if(!ready()||film()==null)return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        confirm(tr("confirm_delete",film().title(),film().revision()),()->send("delete",args,false));
    }
    private boolean canPrepare(){return ready()&&hallDataCurrent()&&film()!=null&&!session.hallId.isBlank();}
    private void prepareSource(){
        if(!canPrepare())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        confirm(tr("confirm_prepare",hallText(),film().title(),session.sourceIndex+1),()->send("prepare",args,false));
    }
    private void prepareAllSources(){
        if(!canPrepare())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        long uniqueSources=film().segments().stream().map(FilmCatalog.Segment::sourceKey).distinct().count();
        confirm(tr("confirm_prepare_all",film().title(),film().revision(),uniqueSources,hallText()),
                ()->send("prepare_all",args,false));
    }
    private void authorizeProgramme(){
        if(!ready()||!hallDataCurrent()||session.hallId.isBlank()||!session.data.orderedPlayback())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        confirm(tr("confirm_authorize_programme",hallText()),()->send("auth_programme",args,false));
    }
    private void claimUnownedHall(){
        var review=session.data.claimReview();
        if(!ready()||!hallDataCurrent()||session.hallId.isBlank()||review==null||!review.available())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;args.claimFingerprint=review.fingerprint();
        String body=tr("confirm_claim_unowned_hall",hallText(),review.terminal(),String.join("\n",review.projectors()),session.view.identity());
        confirmLocal(body,()->send("claim_unowned_hall",args,false));
    }
    private String terminalIdentity(){return session.data.halls().stream().filter(h->h.id().equals(session.hallId)).map(FilmNetwork.Hall::terminalIdentity).findFirst().orElse("");}
    private void authorizeLocalProgramme(){
        if(!canPrepare()||!session.data.orderedPlayback())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        StringBuilder sources=new StringBuilder();
        for(int i=0;i<film().segments().size();i++)sources.append(i+1).append(". ").append(film().segments().get(i).sourceKey()).append('\n');
        String body=tr("confirm_authorize_local_programme",film().title(),film().revision(),hallText(),terminalIdentity(),session.view.identity(),sources.toString().stripTrailing(),film().id());
        confirmLocal(body,()->send("auth_local_programme",args,false));
    }
    private void revokeLocalProgramme(){
        if(!ready()||!hallDataCurrent()||session.hallId.isBlank()||!session.data.orderedPlayback())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        confirmLocal(tr("confirm_revoke_local_programme",hallText(),terminalIdentity()),()->send("revoke_local_programme",args,false));
    }
    private void confirmLocal(String body,Runnable accepted){
        Minecraft mc=Minecraft.getInstance();
        mc.setScreen(new LocalProgrammeConfirmation(body,yes->{mc.setScreen(this);if(yes&&!session.closed)accepted.run();}));
    }
    private boolean canLoadProgramme(){
        return canPrepare()&&film().schedulable()&&session.data.orderedPlayback();
    }
    private String programmeState(){
        if(!session.data.orderedPlayback())return tr("programme_unavailable");
        if(film()==null||!film().schedulable())return tr("duration_unknown");
        return tr("load_programme_hint");
    }
    private void loadProgramme(){
        if(!canLoadProgramme())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        confirm(tr("confirm_load_programme",film().title(),film().revision(),film().segments().size(),hallText(),clock(programmeDuration(film()))),
                ()->send("load_programme",args,false));
    }
    private boolean canSchedule(){
        return ready()&&!schedule().conflict&&film()!=null&&film().revision()==schedule().filmRevision&&film().schedulable()&&
                !schedule().hallId.isBlank()&&!session.data.timezone().isBlank()&&orderedAvailable();
    }
    private boolean orderedAvailable(){return session.data.orderedPlayback()||film()!=null&&film().segments().size()==1;}
    private void scheduleFilm(boolean replace){
        if(!canSchedule()||replace&&selectedPlan()==null)return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        String details=tr("confirm_schedule",film().title(),schedule().filmRevision,hallText(),schedule().time,session.data.timezone(),clock(programmeDuration(film())));
        if(replace)details=tr("confirm_replace",selectedPlan().title(),selectedPlan().time())+"\n\n"+details;
        else args.showId="";
        confirm(details,()->send(replace?"replace":"schedule",args,false));
    }
    private void reschedule(){
        if(!ready()||schedule().conflict||selectedPlan()==null||session.data.timezone().isBlank())return;
        FilmNetwork.Arguments args=arguments();args.confirmed=true;
        confirm(tr("confirm_reschedule",selectedPlan().title(),selectedPlan().time(),schedule().time,session.data.timezone()),()->send("reschedule",args,false));
    }
    private void confirm(String body,Runnable accepted){
        Minecraft mc=Minecraft.getInstance();
        mc.setScreen(new ContinuousConfirmation(yes->{mc.setScreen(this);if(yes&&!session.closed)accepted.run();},Component.translatable("mycinema.catalog.confirm_title"),Component.literal(body)));
    }

    private FilmNetwork.Arguments arguments(){
        FilmNetwork.Arguments args=new FilmNetwork.Arguments();args.catalogRevision=session.data.catalogRevision();args.managerRevision=session.data.managerRevision();
        args.filmId=session.selectedId;args.filmRevision=film()==null?0:film().revision();args.hallId=session.hallId;args.showId=session.showId;
        args.segment=session.sourceIndex;args.page=session.requestedPage;
        if(page==SCHEDULE){ScheduleDraft draft=schedule();args.hallId=draft.hallId;args.showId=draft.showId;args.time=draft.time;args.managerRevision=draft.managerRevision;args.filmRevision=draft.filmRevision;}
        return args;
    }
    private void send(String action,FilmNetwork.Arguments args,boolean reload){send(action,args,reload,false);}
    private void send(String action,FilmNetwork.Arguments args,boolean reload,boolean passive){
        if(session.pending||session.closed)return;
        session.pending=true;session.request=UUID.randomUUID().toString();session.action=action;session.requestFilm=args.filmId;
        session.requestSource=(action.equals("prepare")||action.equals("read"))&&segment()!=null?segment().sourceKey():"";
        session.sentAt=session.ticks;if(!passive)session.notice="";session.reloadEditor=reload;session.passiveRequest=passive;
        PacketDistributor.sendToServer(new FilmNetwork.Request(session.view.source(),session.view.identity(),action,session.request,FilmNetwork.JSON.toJson(args)));
    }
    private void accept(FilmNetwork.View packet){
        if(session.closed||!session.pending||!session.view.source().equals(packet.source())||!session.view.identity().equals(packet.identity())||!session.request.equals(packet.request()))return;
        FilmNetwork.Data incoming;
        try{incoming=FilmNetwork.JSON.fromJson(packet.json(),FilmNetwork.Data.class);if(!validData(incoming))throw new IllegalArgumentException();}
        catch(RuntimeException invalid){failResponse("invalid_response");return;}
        String action=session.action;
        boolean success=switch(action){
            case "view"->true;
            case "prepare_all"->incoming.notice().equals("mycinema.catalog.preparing_all");
            case "create"->incoming.notice().equals("mycinema.catalog.created");
            case "update"->incoming.notice().equals("mycinema.catalog.updated");
            case "delete"->incoming.notice().equals("mycinema.catalog.deleted");
            case "schedule"->incoming.notice().equals("mycinema.catalog.scheduled");
            case "replace"->incoming.notice().equals("mycinema.catalog.replaced");
            case "reschedule"->incoming.notice().equals("mycinema.schedule.updated");
            case "load_programme"->incoming.notice().equals("mycinema.catalog.programme_loaded");
            case "auth_programme"->incoming.notice().equals("mycinema.catalog.programme_authorized");
            case "claim_unowned_hall"->incoming.notice().equals("mycinema.catalog.hall_claimed");
            case "auth_local_programme"->incoming.notice().equals("mycinema.catalog.local_programme_authorized");
            case "revoke_local_programme"->incoming.notice().equals("mycinema.catalog.local_programme_revoked");
            default->incoming.notice().isBlank()||incoming.notice().equals("mycinema.catalog.preparing")||incoming.notice().equals("mycinema.catalog.metadata_ready");
        };
        FilmCatalog.Film returned=incoming.selected();
        if(returned!=null&&!returned.id().equals(session.requestFilm)&&!(action.equals("create")&&success))return;
        if(!session.requestSource.isBlank()&&returned!=null&&(session.sourceIndex>=returned.segments().size()||!session.requestSource.equals(returned.segments().get(session.sourceIndex).sourceKey()))){
            failResponse("source_changed");return;
        }
        boolean inspectionCompleted=action.equals("view")&&page==PREPARE&&segment()!=null&&segment().durationMs().isEmpty()&&
                returned!=null&&session.sourceIndex<returned.segments().size()&&returned.segments().get(session.sourceIndex).durationMs().isPresent();
        session.view=packet;session.data=incoming;session.pending=false;session.request="";session.requestedPage=incoming.page();
        session.hallId=incoming.selectedHall();session.showId=incoming.selectedShow();
        session.conflict=isConflict(incoming.notice());
        if(!incoming.notice().isBlank())session.notice=message(incoming.notice());else if(!session.passiveRequest)session.notice="";
        session.passiveRequest=false;
        if(inspectionCompleted&&incoming.notice().isBlank())session.notice=tr("metadata_ready");
        if(action.equals("create")&&success&&returned!=null)session.selectedId=returned.id();
        if(action.equals("delete")&&success){session.drafts.remove(session.selectedId);session.schedules.remove(session.selectedId);session.selectedId="";}
        if(returned!=null)session.sourceIndex=Math.clamp(session.sourceIndex,0,returned.segments().size()-1);
        for(FilmDraft draft:session.drafts.values())if(returned!=null&&draft.id.equals(returned.id())&&draft.filmRevision!=returned.revision())draft.conflict=true;
        for(ScheduleDraft draft:session.schedules.values())if(draft.managerRevision!=incoming.managerRevision()||returned!=null&&draft.filmId.equals(returned.id())&&draft.filmRevision!=returned.revision())draft.conflict=true;
        if(page==EDIT&&success&&(action.equals("create")||action.equals("update"))&&returned!=null){
            session.drafts.remove(session.editorId);session.editorId=returned.id();session.drafts.put(session.editorId,new FilmDraft(returned,incoming.catalogRevision()));
            rebuild();
        }else if(page==SCHEDULE&&success&&(action.equals("schedule")||action.equals("replace")||action.equals("reschedule"))&&returned!=null){
            session.schedules.put(session.selectedId,new ScheduleDraft(returned,incoming,session.hallId,session.showId));rebuild();
        }else if(session.reloadEditor&&success){
            session.conflict=false;
            if(page==EDIT){
                if(session.editorId.equals(NEW_FILM))session.drafts.put(NEW_FILM,new FilmDraft(null,incoming.catalogRevision()));
                else if(returned!=null)session.drafts.put(session.editorId,new FilmDraft(returned,incoming.catalogRevision()));
                else {session.notice=tr("film_missing");Minecraft.getInstance().setScreen(new FilmLibraryScreen(session,BROWSE));return;}
            }else if(page==SCHEDULE&&returned!=null)session.schedules.put(session.selectedId,new ScheduleDraft(returned,incoming,session.hallId,session.showId));
            rebuild();
        }
        session.reloadEditor=false;
    }
    private static boolean isConflict(String key){return key.equals("mycinema.catalog.stale")||key.equals("mycinema.catalog.stale_film")||key.equals("mycinema.catalog.stale_manager")||key.equals("mycinema.schedule.stale")||key.equals("mycinema.error.stale");}
    private void failResponse(String key){session.pending=false;session.passiveRequest=false;session.request="";session.conflict=true;session.notice=tr(key);}
    private void reload(){
        if(session.pending)return;
        if(pictures!=null)pictures.cancel();
        Runnable reload=()->{session.conflict=false;send("view",arguments(),true);};
        if(page==EDIT&&draft().dirty()||page==SCHEDULE)confirm(tr(page==EDIT?"confirm_reload_draft":"confirm_reload_schedule"),reload);else reload.run();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){
        if(session.closed)return;
        if(session.pending){session.notice=tr("wait_before_close");return;}
        boolean unsaved=session.drafts.values().stream().anyMatch(FilmDraft::dirty)||session.schedules.values().stream().anyMatch(ScheduleDraft::dirty);
        if(unsaved){confirm(tr("confirm_close"),this::closeNow);return;}
        closeNow();
    }
    private void closeToControls(){
        if(!ready()||session.hallId.isBlank())return;
        Runnable close=()->{session.closed=true;session.back.controlsFromFilmLibrary(session.hallId);};
        boolean unsaved=session.drafts.values().stream().anyMatch(FilmDraft::dirty)||session.schedules.values().stream().anyMatch(ScheduleDraft::dirty);
        if(unsaved)confirm(tr("confirm_close"),close);else close.run();
    }
    private void closeNow(){session.closed=true;session.back.returnFromFilmLibrary();}
    @Override public void tick(){
        super.tick();session.ticks++;
        if(minecraft.player==null||minecraft.level==null){session.closed=true;super.onClose();return;}
        if(session.pending&&session.ticks-session.sentAt>100){failResponse("timeout");return;}
        // Browse/inspection can observe newly accepted metadata. Typing drafts and dates never polls.
        if((page==BROWSE||page==PREPARE)&&ready()&&session.ticks%60==0)send("view",arguments(),false,true);
    }
}
