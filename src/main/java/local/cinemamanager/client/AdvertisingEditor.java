package local.cinemamanager.client;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoTile;
import local.cinemamanager.advertising.AdvertisingGeometry;
import local.cinemamanager.panels.*;
import me.srrapero720.waterui.screen.WaterScreen;
import me.srrapero720.waterui.widget.InputText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** One editor for the image and the physical sign. Geometry is a draft until explicitly applied. */
public final class AdvertisingEditor extends WaterScreen {
    private PanelNetwork.View snapshot;
    private PanelNetwork.Data data;
    private AdvertisingGeometry geometry;
    private String source,notice="",request="",action="";
    private int step=1,ticks,sentAt;
    private boolean pending,conflict;
    private final PictureActions pictures;

    public static void open(PanelNetwork.View packet){
        var mc=Minecraft.getInstance();
        if(packet.open()){mc.setScreen(new AdvertisingEditor(packet));return;}
        if(mc.screen instanceof AdvertisingEditor editor&&editor.snapshot.pos().equals(packet.pos())
                &&editor.snapshot.identity().equals(packet.identity())&&editor.request.equals(packet.request()))editor.update(packet);
    }
    private AdvertisingEditor(PanelNetwork.View packet){
        super(CinemaManager.id("advertising"),430,460);
        snapshot=packet;data=PanelNetwork.JSON.fromJson(packet.json(),PanelNetwork.Data.class);
        geometry=data.advertising();source=data.settings().poster();
        pictures=new PictureActions(this,()->snapshot,reference->{source=reference;if(get(CinemaManager.id("poster")) instanceof InputText input)input.text(source);savePicture();},value->notice=value);
        registerVar("heading",()->tr("title")+" · "+tr(data.surface()));
        registerVar("assembly",()->tr("assembly",data.assemblyUnits()));
        registerVar("can_assemble",()->editable()&&data.assemblyUnits()>1);
        registerVar("shape_label",()->tr(data.imageShape()?"shape.enabled":"shape.rectangle"));
        registerVar("shape_editable",()->editable()&&data.screenMode()==local.cinemamanager.advertising.ScreenMode.PICTURE);
        registerVar("mode_label",()->tr("mode."+data.screenMode().name().toLowerCase(Locale.ROOT)));
        registerVar("information_mode",()->editable()&&data.screenMode()==local.cinemamanager.advertising.ScreenMode.INFORMATION);
        registerVar("editable",this::editable);registerVar("can_reload",()->!pending&&!pictures.busy());
        registerVar("dimensions",()->tr("dimensions",number(geometry.widthBlocks()),number(geometry.heightBlocks())));
        registerVar("step_label",()->tr("step",step==1?"0.125":"1"));
        registerVar("notice",()->notice.isBlank()?tr("hint"):notice);
        registerVar("preview",this::posterDiagnostic);
        for(var mode:local.cinemamanager.advertising.ScreenMode.values())registerEvent("mode_"+mode.name().toLowerCase(Locale.ROOT),e->{if(editable())send("ad_mode",mode.name());});
        registerEvent("assemble",e->{if(editable()&&data.assemblyUnits()>1){
            String token=data.assemblyToken();var mc=Minecraft.getInstance();mc.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes->{mc.setScreen(this);if(yes&&editable())send("ad_merge",token);},net.minecraft.network.chat.Component.translatable("mycinema.ad.assembly_title"),net.minecraft.network.chat.Component.translatable("mycinema.ad.assembly_explain",data.assemblyUnits())));
        }});
        registerEvent("shape_apply",e->{if(editable())send("ad_shape","true");});
        registerEvent("shape_clear",e->{if(editable())send("ad_shape","false");});
        registerEvent("info_settings",e->{if(editable()&&data.screenMode()==local.cinemamanager.advertising.ScreenMode.INFORMATION)PanelEditor.openInformation(snapshot);});
        registerEvent("choose_local",e->{if(editable())pictures.choose(false);});
        registerEvent("upload_picture",e->{if(editable())pictures.choose(true);});
        registerEvent("save_picture",e->savePicture());
        registerEvent("clear_picture",e->{if(editable()){source="";if(get(CinemaManager.id("poster")) instanceof InputText input)input.text("");savePicture();}});
        registerEvent("poster_retry",e->{InfoTile tile=tile();if(tile!=null)PosterImages.retry(tile);});
        registerEvent("step",e->{if(editable())step=step==1?8:1;});
        for(String edge:List.of("left","right","bottom","top"))for(int sign:new int[]{-1,1}){
            registerEvent(edge+(sign<0?"_less":"_more"),e->resize(edge,sign));
        }
        registerEvent("reset_size",e->{if(editable())geometry=AdvertisingGeometry.DEFAULT;});
        registerEvent("apply_size",e->{if(editable())send("ad_size",PanelNetwork.JSON.toJson(geometry));});
        registerEvent("reload",e->{if(!pending&&!pictures.busy()){conflict=false;send("reload","");}});
        registerEvent("done",e->onClose());
    }
    private static String tr(String key,Object...args){return I18n.get("mycinema.ad."+key,args);}
    private static String number(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private boolean editable(){return data.editable()&&!pending&&!conflict&&!pictures.busy();}
    @Override protected void build(){root.size(Math.min(430,Math.max(120,width-12)),Math.min(460,Math.max(160,height-12)));if(get(CinemaManager.id("poster")) instanceof InputText input)input.text(source).maxLength(2048).onChange(value->source=value);}
    private InfoTile tile(){var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.hasChunkAt(snapshot.pos())&&mc.level.getBlockEntity(snapshot.pos()) instanceof InfoTile tile&&tile.identity.toString().equals(snapshot.identity())&&tile.isAdvertising()?tile:null;}
    private String posterDiagnostic(){InfoTile tile=tile();if(tile==null)return I18n.get("mycinema.panel.offline");
        if(tile.scope!=data.scope()||tile.screenMode!=data.screenMode())return I18n.get("mycinema.panel.poster_changed");
        boolean automatic=data.scope()==PanelScope.HALL&&data.screenMode()==local.cinemamanager.advertising.ScreenMode.INFORMATION;
        return switch(PosterViewState.of(data.settings().poster(),tile.publishedPoster,automatic)){
        case EMPTY->I18n.get("mycinema.panel.poster_none");case WITHHELD->I18n.get("mycinema.panel.poster_no_permission");
        case CHANGED->I18n.get("mycinema.panel.poster_changed");case AVAILABLE->PosterImages.describe(PosterImages.lookup(tile));
        case AUTOMATIC_EMPTY->I18n.get("mycinema.panel."+switch(data.binding().schedule()){
            case INVALID_TIMEZONE->"poster_auto_no_timezone";case EMPTY->"poster_auto_no_programme";
            case AVAILABLE->"poster_auto_no_approved";default->"poster_auto_none";});
        case AUTOMATIC_AVAILABLE->I18n.get("mycinema.panel.poster_auto_source")+" "+PosterImages.describe(PosterImages.lookup(tile));};}
    private void resize(String edge,int sign){if(!editable())return;try{geometry=geometry.edge(edge,sign*step);notice="";}catch(IllegalArgumentException error){notice=tr("geometry_error");}}
    private void savePicture(){if(!editable())return;send("save",PanelNetwork.JSON.toJson(new PanelNetwork.Configuration(data.settings().withPoster(source),data.scope())));}
    private void send(String action,String json){this.action=action;pending=true;request=UUID.randomUUID().toString();sentAt=ticks;notice="";PacketDistributor.sendToServer(new PanelNetwork.Edit(snapshot.pos(),snapshot.identity(),snapshot.signature(),action,json,request));}
    private void update(PanelNetwork.View packet){
        snapshot=packet;data=PanelNetwork.JSON.fromJson(packet.json(),PanelNetwork.Data.class);pending=false;request="";
        conflict=data.notice().equals("mycinema.panel.stale");notice=data.notice().isBlank()?"":I18n.get(data.notice());
        if(action.equals("reload")){geometry=data.advertising();source=data.settings().poster();if(get(CinemaManager.id("poster")) instanceof InputText input)input.text(source);}
        else if((action.equals("ad_size")||action.equals("ad_merge"))&&data.notice().equals("mycinema.panel.saved"))geometry=data.advertising();
        action="";
    }
    @Override public void onFilesDrop(List<java.nio.file.Path> paths){if(editable()&&!paths.isEmpty())pictures.selected(paths.getFirst(),true);}
    @Override public void onClose(){pictures.close();super.onClose();}
    @Override public void tick(){super.tick();ticks++;InfoTile tile=tile();if(tile==null||minecraft.player==null||tile.panelDistanceSquared(minecraft.player)>64){onClose();return;}
        if(pending&&ticks-sentAt>100){pending=false;request="";conflict=true;notice=I18n.get("mycinema.panel.timeout");}}
}
