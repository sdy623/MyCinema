package local.cinemamanager.client;

import local.cinemamanager.*;
import local.cinemamanager.panels.*;
import local.cinemamanager.operations.TerminalRoleSettings;
import me.srrapero720.waterui.screen.WaterScreen;
import me.srrapero720.waterui.widget.InputText;
import me.srrapero720.waterui.widget.Switch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** A draft survives navigation; incoming responses do not silently replace its content. */
public final class PanelEditor extends WaterScreen {
    private PanelNetwork.View snapshot;
    private PanelNetwork.Data data;
    private final Map<String,String> draft;
    private final int page;
    private String localNotice="";
    private boolean pending,conflict;
    private String pendingRequest="";
    private int ticks,sentAt;
    private final PictureActions pictures;
    public static void open(PanelNetwork.View p){Minecraft mc=Minecraft.getInstance();if(mc.screen instanceof AdvertisingEditor&&!p.open()||p.open()&&PanelNetwork.JSON.fromJson(p.json(),PanelNetwork.Data.class).advertising()!=null){AdvertisingEditor.open(p);return;}if(!p.open()){if(mc.screen instanceof PanelEditor s&&s.snapshot.pos().equals(p.pos())&&s.snapshot.identity().equals(p.identity())&&s.pendingRequest.equals(p.request()))s.update(p);return;}mc.setScreen(new PanelEditor(p,0,new HashMap<>()));}
    public static void openInformation(PanelNetwork.View packet){Minecraft.getInstance().setScreen(new PanelEditor(packet,0,new HashMap<>()));}
    private PanelEditor(PanelNetwork.View p,int page,Map<String,String> draft){
        super(CinemaManager.id("panel_"+page),420,page==2?420:450);this.snapshot=p;this.data=PanelNetwork.JSON.fromJson(p.json(),PanelNetwork.Data.class);this.page=page;this.draft=draft;
        this.pictures=new PictureActions(this,()->snapshot,reference->{draft.put("poster",reference);if(get(CinemaManager.id("poster")) instanceof InputText input)input.text(reference);save();},value->localNotice=value);
        registerVar("heading",()->tr("title")+" · "+data.width()+"×"+data.height()+"m");registerVar("editable",()->data.editable()&&!pending&&!conflict&&!pictures.busy());registerVar("can_reload",()->!pending&&!pictures.busy());registerVar("notice",()->localNotice.isBlank()?tr(data.notice().isBlank()?"hint":data.notice().replace("mycinema.panel.","")):localNotice);
        registerVar("layout",()->tr("layout."+layout().name().toLowerCase(Locale.ROOT)));registerVar("scope",()->tr("scope."+scope().name().toLowerCase(Locale.ROOT)));registerVar("hall_editable",()->data.editable()&&!pending&&!conflict&&!pictures.busy()&&scope()!=PanelScope.LOBBY);registerVar("preview",()->page==2?posterDiagnostic():String.join(" | ",data.fields().stream().map(MessageText::display).toList()));
        registerVar("binding_status",this::bindingStatus);registerVar("schedule_status",()->tr("schedule."+data.binding().schedule().name().toLowerCase(Locale.ROOT)));
        registerVar("mode_help",()->helpText("mode_help."+scope().name().toLowerCase(Locale.ROOT)));registerVar("binding_steps",()->helpText("binding_steps."+scope().name().toLowerCase(Locale.ROOT)));
        registerVar("terminal_role_editable",()->editable()&&data.advertising()==null);
        registerVar("terminal_role",()->net.minecraft.client.resources.language.I18n.get("mycinema.operations.role."+terminalRole().role().name().toLowerCase(Locale.ROOT)));
        registerVar("terminal_role_help",()->net.minecraft.client.resources.language.I18n.get(data.invalidRole()?"mycinema.operations.invalid_role":"mycinema.operations.role_help"));
        registerVar("poster_layout_hint",()->tr(PosterViewState.showsPoster(layout())?"poster_layout_visible":"poster_layout_text"));
        for(int i=0;i<3;i++){int tab=i;registerEvent("tab"+i,e->navigate(tab));}
        registerEvent("done",e->onClose());registerEvent("save",e->save());registerEvent("reload",e->{if(pending||pictures.busy())return;draft.clear();conflict=false;send("reload","");});
        if(page==0){registerEvent("terminal_role_toggle",e->{if(!editable()||data.advertising()!=null)return;var role=terminalRole().role()==TerminalRoleSettings.Role.OPERATIONS?TerminalRoleSettings.LEGACY:TerminalRoleSettings.OPERATIONS;draft.put("terminal_role",role.encode());if(role.role()==TerminalRoleSettings.Role.OPERATIONS&&scope()==PanelScope.MANUAL)draft.put("scope",PanelScope.LOBBY.name());navigate(page);});registerEvent("scope_previous",e->cycleScope(-1));registerEvent("scope_next",e->cycleScope(1));}
        if(page==2){registerEvent("poster_retry",e->{var tile=posterTile();if(tile!=null)PosterImages.retry(tile);});registerEvent("choose_local",e->{if(data.editable()&&!pending&&!conflict)pictures.choose(false);});registerEvent("upload_picture",e->{if(data.editable()&&!pending&&!conflict)pictures.choose(true);});}
        if(page==1){
            registerEvent("layout_previous",e->cycle(-1));registerEvent("layout_next",e->cycle(1));
            registerVar("style_entry",()->tr("entry."+styleEntry().name().toLowerCase(Locale.ROOT)));
            registerVar("horizontal",()->tr("horizontal."+safePlacement().horizontal().name().toLowerCase(Locale.ROOT)));
            registerVar("vertical",()->tr("vertical."+safePlacement().vertical().name().toLowerCase(Locale.ROOT)));
            registerVar("entry_flow_editable",()->editable()&&styleEntry()!=PanelStyle.Entry.POSTER&&styleEntry()!=PanelStyle.Entry.TEXT);
            registerEvent("entry_previous",e->cycleEntry(-1));registerEvent("entry_next",e->cycleEntry(1));
            registerEvent("horizontal_previous",e->cyclePlacement(true,-1));registerEvent("horizontal_next",e->cyclePlacement(true,1));
            registerEvent("vertical_previous",e->cyclePlacement(false,-1));registerEvent("vertical_next",e->cyclePlacement(false,1));
            registerEvent("entry_reset",e->{if(!editable())return;setPlacement(styleEntry()==PanelStyle.Entry.POSTER?PanelStyle.POSTER:PanelStyle.FLOW);buildPlacementInputs();});
        }
    }
    private InfoTile posterTile(){return minecraft!=null&&minecraft.level!=null&&minecraft.level.hasChunkAt(snapshot.pos())&&minecraft.level.getBlockEntity(snapshot.pos()) instanceof InfoTile t&&t.identity.toString().equals(snapshot.identity())?PanelGroup.of(t).root():null;}
    private String bindingStatus(){
        String saved=tr("binding."+data.binding().state().name().toLowerCase(Locale.ROOT),MessageText.display(data.binding().hallName()));
        return scope()==data.scope()?saved:tr("binding_pending")+" "+saved;
    }
    private String posterDiagnostic(){
        var tile=posterTile();if(tile==null)return tr("offline");
        if(tile.scope!=data.scope())return tr("poster_changed");
        boolean automatic=data.scope()==PanelScope.HALL&&(!tile.isAdvertising()||tile.screenMode==local.cinemamanager.advertising.ScreenMode.INFORMATION);
        String blocked=posterServerReason("poster_no_programme","poster_current_unavailable","poster_snapshot_invalid",
                "poster_film_empty","poster_saved_empty","poster_local_unpublished","poster_publication_denied");
        String inherited=posterServerReason("poster_inherited");
        String diagnostic=switch(PosterViewState.of(data.settings().poster(),tile.publishedPoster,automatic)){
            case EMPTY->blocked.isBlank()?tr("poster_none"):blocked;
            case WITHHELD->blocked.isBlank()?tr("poster_no_permission"):blocked;
            case CHANGED->tr("poster_changed");
            case AUTOMATIC_EMPTY->!blocked.isBlank()?blocked:tr(switch(data.binding().schedule()){
                case INVALID_TIMEZONE->"poster_auto_no_timezone";case EMPTY->"poster_auto_no_programme";
                case AVAILABLE->"poster_auto_no_approved";default->"poster_auto_none";});
            case AVAILABLE->PosterImages.describe(PosterImages.lookup(tile));
            case AUTOMATIC_AVAILABLE->(inherited.isBlank()?tr("poster_auto_source"):inherited)+" "+PosterImages.describe(PosterImages.lookup(tile));
        };
        // Report the live saved layout, while retaining any source or loading failure beside it.
        return tile.panel.layout()==PanelSettings.Layout.TEXT?tr("poster_text_layout")+" "+diagnostic:diagnostic;
    }
    private String posterServerReason(String... keys){
        // Only known diagnostics are interpreted; hall names and arbitrary server text stay separate.
        for(String key:keys)if(data.fields().contains("mycinema.panel."+key)
                ||data.fields().contains(MessageText.key("panel."+key)))return tr(key);
        return "";
    }
    // WaterUI Text wraps words to its available width; embedded line breaks render as control glyphs.
    private static String helpText(String key){return tr(key).replace('\r',' ').replace('\n',' ');}
    private static String tr(String key,Object...args){return I18n.get("mycinema.panel."+key,args);}
    private PanelSettings.Layout layout(){try{return PanelSettings.Layout.valueOf(draft.getOrDefault("layout",data.settings().layout().name()));}catch(RuntimeException e){return PanelSettings.Layout.TEXT;}}
    private void cycle(int d){if(!data.editable()||pending||conflict||pictures.busy())return;var a=PanelSettings.Layout.values();draft.put("layout",a[Math.floorMod(layout().ordinal()+d,a.length)].name());}
    private TerminalRoleSettings terminalRole(){try{return TerminalRoleSettings.decode(draft.getOrDefault("terminal_role",data.terminalRole().encode()));}catch(RuntimeException invalid){return TerminalRoleSettings.LEGACY;}}
    private PanelScope scope(){try{return PanelScope.valueOf(draft.getOrDefault("scope",data.scope().name()));}catch(RuntimeException e){return PanelScope.HALL;}}
    private void cycleScope(int d){if(!data.editable()||pending||conflict||pictures.busy())return;var scopes=terminalRole().role()==TerminalRoleSettings.Role.OPERATIONS?new PanelScope[]{PanelScope.HALL,PanelScope.LOBBY}:PanelScope.values();int current=java.util.Arrays.asList(scopes).indexOf(scope());draft.put("scope",scopes[Math.floorMod(current+d,scopes.length)].name());navigate(page);}
    private String fallback(String k){PanelSettings p=data.settings();return switch(k){case "format"->p.screeningFormat();case "poster_percent"->Integer.toString(p.style().posterPercent());case "hall"->p.hall();case "title"->p.title();case "time"->p.time();case "next"->p.next();case "footer"->p.footer();case "duration"->p.duration();case "time_end"->p.endTime();case "poster"->p.poster();case "foreground"->PanelSettings.hex(p.foreground());case "background"->PanelSettings.hex(p.background());case "accent"->PanelSettings.hex(p.accent());case "scale"->Integer.toString(p.textPercent());case "visible"->Integer.toString(p.visible());case "hidden_headers"->Integer.toString(p.hiddenHeaders());default->"";};}
    @Override protected void build(){root.size(Math.min(420,Math.max(120,width-12)),Math.min(page==2?420:450,Math.max(160,height-12)));for(int bit=0;bit<8;bit++){final int mask=1<<bit;if(get(CinemaManager.id("show_"+bit)) instanceof Switch toggle){toggle.value((Integer.parseInt(val("visible"))&mask)!=0).onChange(v->{if(!editable())return;int flags=Integer.parseInt(val("visible"));draft.put("visible",Integer.toString(v?flags|mask:flags&~mask));});toggle.enabled=editable();}}buildHeaderSwitches();buildPlacementInputs();for(String k:List.of("hall","title","time","time_end","duration","format","poster_percent","poster","foreground","background","accent","scale","visible"))if(get(CinemaManager.id(k)) instanceof InputText input){input.text(draft.getOrDefault(k,fallback(k))).maxLength(switch(k){case "poster"->2048;case "hall"->80;case "title"->160;case "time"->120;case "duration","format"->80;case "poster_percent"->2;case "time_end"->5;default->240;}).onChange(s->draft.put(k,s));input.enabled=data.editable()&&(!List.of("hall","title","time","time_end","duration","format").contains(k)||scope()!=PanelScope.LOBBY);}}
    private void buildHeaderSwitches(){
        for(int flag:new int[]{PanelSettings.TITLE,PanelSettings.TIME,PanelSettings.DURATION,PanelSettings.FORMAT}){
            if(get(CinemaManager.id("header_"+flag)) instanceof Switch toggle){
                toggle.value((Integer.parseInt(val("hidden_headers"))&flag)==0).onChange(shown->{
                    if(!data.editable()||pending||conflict||pictures.busy())return;
                    int hidden=Integer.parseInt(val("hidden_headers"));
                    draft.put("hidden_headers",Integer.toString(shown?hidden&~flag:hidden|flag));
                });
                toggle.enabled=data.editable()&&!pending&&!conflict&&!pictures.busy();
            }
        }
    }
    private boolean editable(){return data.editable()&&!pending&&!conflict&&!pictures.busy();}
    private PanelStyle.Entry styleEntry(){return PanelStyle.Entry.valueOf(draft.getOrDefault("style_entry","TEXT"));}
    private String styleKey(PanelStyle.Entry entry,String property){return "style_"+entry.name()+"_"+property;}
    private PanelStyle.Placement safePlacement(){try{return placement();}catch(RuntimeException e){return data.settings().style().placement(styleEntry());}}
    private PanelStyle.Placement placement(){return placement(styleEntry());}
    private PanelStyle.Placement placement(PanelStyle.Entry entry){
        var saved=data.settings().style().placement(entry);
        return new PanelStyle.Placement(PanelStyle.Horizontal.valueOf(draft.getOrDefault(styleKey(entry,"horizontal"),saved.horizontal().name())),PanelStyle.Vertical.valueOf(draft.getOrDefault(styleKey(entry,"vertical"),saved.vertical().name())),Integer.parseInt(draft.getOrDefault(styleKey(entry,"x"),Integer.toString(saved.manualX()))),Integer.parseInt(draft.getOrDefault(styleKey(entry,"y"),Integer.toString(saved.manualY()))),Boolean.parseBoolean(draft.getOrDefault(styleKey(entry,"automatic"),Boolean.toString(saved.automatic()))));
    }
    private void setPlacement(PanelStyle.Placement placement){var entry=styleEntry();draft.put(styleKey(entry,"horizontal"),placement.horizontal().name());draft.put(styleKey(entry,"vertical"),placement.vertical().name());draft.put(styleKey(entry,"x"),Integer.toString(placement.manualX()));draft.put(styleKey(entry,"y"),Integer.toString(placement.manualY()));draft.put(styleKey(entry,"automatic"),Boolean.toString(placement.automatic()));}
    private void cycleEntry(int direction){if(!editable())return;var values=PanelStyle.Entry.values();draft.put("style_entry",values[Math.floorMod(styleEntry().ordinal()+direction,values.length)].name());buildPlacementInputs();}
    private void cyclePlacement(boolean horizontal,int direction){
        if(!editable())return;try{var current=placement();var hs=PanelStyle.Horizontal.values();var vs=PanelStyle.Vertical.values();
            setPlacement(new PanelStyle.Placement(horizontal?hs[Math.floorMod(current.horizontal().ordinal()+direction,hs.length)]:current.horizontal(),horizontal?current.vertical():vs[Math.floorMod(current.vertical().ordinal()+direction,vs.length)],current.manualX(),current.manualY(),false));buildPlacementInputs();
        }catch(RuntimeException e){localNotice=tr("style_error");}
    }
    private void buildPlacementInputs(){
        if(page!=1)return;
        var entry=styleEntry();var saved=data.settings().style().placement(entry);
        for(String axis:List.of("x","y"))if(get(CinemaManager.id("manual_"+axis)) instanceof InputText input){
            String key=styleKey(entry,axis);input.onChange(ignored->{});input.text(draft.getOrDefault(key,Integer.toString(axis.equals("x")?saved.manualX():saved.manualY()))).maxLength(4).onChange(value->{if(editable()){draft.put(key,value);draft.put(styleKey(entry,"automatic"),"false");}});input.enabled=editable();
        }
        if(get(CinemaManager.id("entry_auto")) instanceof Switch toggle){toggle.source(()->Boolean.parseBoolean(draft.getOrDefault(styleKey(styleEntry(),"automatic"),Boolean.toString(data.settings().style().placement(styleEntry()).automatic())))).onChange(value->{if(editable())draft.put(styleKey(entry,"automatic"),Boolean.toString(value));});toggle.enabled=editable()&&entry!=PanelStyle.Entry.POSTER&&entry!=PanelStyle.Entry.TEXT;}
    }
    private PanelStyle style(){
        var result=new PanelStyle(Map.of(),Integer.parseInt(val("poster_percent").strip()));
        for(var entry:PanelStyle.Entry.values())result=result.with(entry,placement(entry));
        return result;
    }
    private String val(String k){return draft.getOrDefault(k,fallback(k));}
    private void save(){if(!data.editable()||pending||conflict||pictures.busy())return;try{PanelSettings p=new PanelSettings(layout(),val("hall"),val("title"),val("time"),val("next"),val("footer"),val("poster"),PanelSettings.color(val("foreground")),PanelSettings.color(val("background")),PanelSettings.color(val("accent")),Integer.parseInt(val("scale")),Integer.parseInt(val("visible")),val("duration"),val("time_end"),Integer.parseInt(val("hidden_headers")),val("format"),style());send("save",PanelNetwork.JSON.toJson(new PanelNetwork.Configuration(p,scope(),terminalRole())));}catch(RuntimeException e){localNotice=tr(e.getMessage()!=null&&e.getMessage().startsWith("mycinema.panel.")?e.getMessage().substring("mycinema.panel.".length()):"invalid");}}
    private void send(String action,String json){pending=true;pendingRequest=UUID.randomUUID().toString();sentAt=ticks;localNotice="";PacketDistributor.sendToServer(new PanelNetwork.Edit(snapshot.pos(),snapshot.identity(),snapshot.signature(),action,json,pendingRequest));}
    private void navigate(int next){if(pending||conflict||pictures.busy())return;Minecraft.getInstance().setScreen(new PanelEditor(snapshot,next,draft));}
    private void update(PanelNetwork.View p){snapshot=p;data=PanelNetwork.JSON.fromJson(p.json(),PanelNetwork.Data.class);pending=false;pendingRequest="";localNotice="";conflict=data.notice().equals("mycinema.panel.stale");if(data.notice().isBlank())Minecraft.getInstance().setScreen(new PanelEditor(p,page,draft));}
    @Override public void onClose(){pictures.close();super.onClose();}
    @Override public void onFilesDrop(java.util.List<java.nio.file.Path> paths){if(page==2&&data.editable()&&!pending&&!conflict&&!paths.isEmpty())pictures.selected(paths.getFirst(),true);}
    @Override public void tick(){super.tick();ticks++;if(minecraft.player==null||minecraft.level==null||(posterTile()==null||posterTile().panelDistanceSquared(minecraft.player)>64)){onClose();return;}if(pending&&ticks-sentAt>100){pending=false;pendingRequest="";conflict=true;localNotice=tr("timeout");}}
}
