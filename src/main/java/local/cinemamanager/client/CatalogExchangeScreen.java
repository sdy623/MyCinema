package local.cinemamanager.client;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.catalog.*;
import local.cinemamanager.library.CatalogExchangeNetwork;
import local.cinemamanager.library.FilmNetwork;
import me.srrapero720.waterui.core.AbstractParent;
import me.srrapero720.waterui.core.Element;
import me.srrapero720.waterui.screen.WaterScreen;
import me.srrapero720.waterui.widget.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.InputStream;
import java.util.*;

/** Explicit selected-file exchange; imports never follow media/poster references or open a decoder. */
public final class CatalogExchangeScreen extends WaterScreen {
    private final Screen back;
    private final BlockPos source;
    private final String identity;
    private final long revision;
    private final Runnable refresh;
    private CatalogExchange.Conflict conflict=CatalogExchange.Conflict.SKIP;
    private CatalogExchange.Document document;
    private CatalogExchangeNetwork.Review review;
    private String selectedJson="", filename="", notice="", request="", direction="";
    private final StringBuilder output=new StringBuilder();
    private List<String> upload=List.of();
    private int received, ticks, sentAt;
    private volatile long generation;
    private volatile boolean closed;
    private boolean pending, picking, changed;
    public CatalogExchangeScreen(Screen back,BlockPos source,String identity,long revision,Runnable refresh){
        super(CinemaManager.id("catalog_exchange"),448,348);
        this.back=back;this.source=source;this.identity=identity;this.revision=revision;this.refresh=refresh;
        registerVar("notice",()->notice);
        registerVar("selection",()->filename.isEmpty()?tr("select_hint"):tr("selection",filename,document==null?0:document.films().size()));
        registerVar("conflict",()->tr("conflict_"+conflict.name().toLowerCase(Locale.ROOT)));
        registerVar("ready",()->!pending&&!picking&&!closed&&!changed);
        registerVar("can_preview",()->!pending&&!picking&&!closed&&!changed&&document!=null);
        registerVar("can_commit",()->!pending&&!picking&&!closed&&!changed&&review!=null&&review.preview().applicable()&&!review.confirmation().isEmpty());
        registerEvent("choose",e->choose());
        registerEvent("export",e->export());
        registerEvent("policy",e->{if(ready()){cancelServer();conflict=CatalogExchange.Conflict.values()[(conflict.ordinal()+1)%3];review=null;rebuild();}});
        registerEvent("preview",e->preview());
        registerEvent("commit",e->confirm());
        registerEvent("done",e->onClose());
    }
    private boolean ready(){return !pending&&!picking&&!closed&&!changed;}
    private static String tr(String key,Object...args){return I18n.get("mycinema.catalog.exchange_"+key,args);}
    private static String message(String key){return key!=null&&I18n.exists(key)?I18n.get(key):tr("invalid");}
    private void rebuild(){if(minecraft!=null&&minecraft.screen==this)rebuildWidgets();}
    @Override protected void build(){
        if(get(CinemaManager.id("exchange_review")) instanceof AbstractParent content){
            List<String> lines=new ArrayList<>();
            lines.add(tr("limits",CatalogExchange.VERSION,CatalogExchange.MAX_BYTES/1024));
            if(review!=null){
                lines.add(tr("review_revision",review.preview().revision()));
                for(CatalogExchange.Row row:review.preview().rows()){
                    lines.add(row.title()+" · "+tr("action_"+row.action().toLowerCase(Locale.ROOT)));
                    for(String warning:row.warnings())lines.add("  "+message(warning));
                    if(!row.error().isEmpty())lines.add("  "+message(row.error()));
                }
            }else lines.add(tr("intro"));
            for(String line:lines)content.add(new Text(Component.literal(line)).lines(32).width(Element.FILL));
        }
    }
    private void choose(){
        if(!ready())return;cancelServer();review=null;picking=true;long token=++generation;notice=tr("choosing");
        Thread picker=new Thread(()->{
            String selected=null,json=null,error=null;
            try(MemoryStack stack=MemoryStack.stackPush()){
                PointerBuffer filters=stack.mallocPointer(1);filters.put(stack.UTF8("*.json")).flip();
                selected=TinyFileDialogs.tinyfd_openFileDialog(tr("choose_title"),null,filters,"JSON",false);
                if(selected!=null&&!closed&&token==generation){
                    Path path=Path.of(selected);
                    // Read only the exact user-selected regular JSON file; never expand a reference from its content.
                    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))throw new IllegalArgumentException();
                    if(Files.size(path)>CatalogExchange.MAX_BYTES)throw new CatalogException("byte_limit");
                    try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
                        byte[] bytes=in.readNBytes(CatalogExchange.MAX_BYTES+1);if(bytes.length>CatalogExchange.MAX_BYTES)throw new CatalogException("byte_limit");
                        json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    }
                    CatalogExchange.decode(json);
                }
            }catch(Throwable failure){error=failure instanceof CatalogException?failure.getMessage():"mycinema.catalog.exchange_file_error";}
            String path=selected,text=json,failure=error;
            Minecraft.getInstance().execute(()->{
                if(closed||token!=generation||Minecraft.getInstance().screen!=this)return;picking=false;
                if(failure!=null){notice=message(failure);return;}
                if(path==null){notice="";return;}
                selectedJson=text;document=CatalogExchange.decode(text);filename=Path.of(path).getFileName().toString();notice=tr("selected_local");rebuild();
            });
        },"MyCinema-catalog-json-picker");picker.setDaemon(true);picker.start();
    }
    private void preview(){
        if(!ready()||document==null)return;cancelServer();review=null;request=UUID.randomUUID().toString();direction="preview";
        output.setLength(0);received=0;upload=split(selectedJson);pending=true;notice=tr("uploading");send("begin",0,conflict.name());
    }
    private void export(){
        if(!ready())return;cancelServer();review=null;rebuild();request=UUID.randomUUID().toString();direction="export";output.setLength(0);received=0;pending=true;notice=tr("exporting");send("export",0,"");
    }
    private void send(String action,int sequence,String body){sentAt=ticks;PacketDistributor.sendToServer(new CatalogExchangeNetwork.Request(source,identity,request,action,sequence,revision,body));}
    public static void receive(CatalogExchangeNetwork.Reply packet){if(Minecraft.getInstance().screen instanceof CatalogExchangeScreen screen)screen.accept(packet);}
    private void accept(CatalogExchangeNetwork.Reply p){
        if(closed||!pending||!request.equals(p.request()))return;
        if(p.state().equals("error")){pending=false;review=null;notice=message(p.body());return;}
        if(p.state().equals("committed")){pending=false;review=null;changed=true;notice=tr("imported");rebuild();return;}
        if(p.state().equals("upload")&&direction.equals("preview")){
            int index=p.sequence();if(index<0||index>upload.size()){fail();return;}
            if(index<upload.size())send("chunk",index,upload.get(index));else send("preview",index,"");return;
        }
        if(!(p.state().equals(direction+"_part")||p.state().equals(direction+"_done"))||p.sequence()!=received){fail();return;}
        if(output.length()+p.body().length()>CatalogExchange.MAX_BYTES){fail();return;}
        output.append(p.body());received++;
        if(p.state().endsWith("_part")){send("next",received,"");return;}
        pending=false;
        try{
            if(direction.equals("export")){String json=output.toString();CatalogExchange.decode(json);saveExport(json);}
            else{
                review=FilmNetwork.JSON.fromJson(output.toString(),CatalogExchangeNetwork.Review.class);
                if(review==null||review.preview()==null||review.preview().revision()!=revision||review.preview().conflict()!=conflict||review.preview().rows().size()!=document.films().size())throw new IllegalArgumentException();
                notice=tr(review.preview().applicable()?"review_ready":"review_failed");rebuild();
            }
        }catch(RuntimeException invalid){fail();}
    }
    private void confirm(){
        if(!ready()||review==null||!review.preview().applicable()||review.confirmation().isEmpty())return;
        StringBuilder body=new StringBuilder(tr("confirm_intro",revision,tr("conflict_"+conflict.name().toLowerCase(Locale.ROOT))));
        for(int i=0;i<document.films().size();i++){
            var film=document.films().get(i);var row=review.preview().rows().get(i);
            body.append("\n\n").append(film.title()).append(" · ").append(tr("action_"+row.action().toLowerCase(Locale.ROOT))).append("\n").append(tr("metadata",film.format(),film.poster()));
            for(int j=0;j<film.playlist().size();j++){var clip=film.playlist().get(j);body.append("\n").append(j+1).append(". ").append(clip.source()).append(" [").append(clip.durationMs()).append(" ms]");}
            for(String warning:row.warnings())body.append("\n").append(message(warning));
        }
        String confirmation=review.confirmation();
        minecraft.setScreen(new LocalProgrammeConfirmation(body.toString(),accepted->{
            if(closed)return;minecraft.setScreen(this);if(accepted){pending=true;notice=tr("committing");send("commit",0,confirmation);}
        }));
    }
    private void saveExport(String json){
        picking=true;long token=++generation;notice=tr("save_location");
        Thread picker=new Thread(()->{
            String result="",failure=null;
            try(MemoryStack stack=MemoryStack.stackPush()){
                PointerBuffer filters=stack.mallocPointer(1);filters.put(stack.UTF8("*.json")).flip();
                String selected=TinyFileDialogs.tinyfd_saveFileDialog(tr("save_title"),"mycinema-catalog.json",filters,"JSON");
                if(selected!=null&&!closed&&token==generation){
                    Path path=Path.of(selected);if(!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))path=Path.of(selected+".json");
                    // CREATE_NEW preserves original files even if the native dialog offers overwrite.
                    Files.writeString(path,json,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);result=path.toString();
                }
            }catch(FileAlreadyExistsException exists){failure="mycinema.catalog.exchange_file_exists";}
            catch(Throwable error){failure="mycinema.catalog.exchange_file_error";}
            String saved=result,error=failure;
            Minecraft.getInstance().execute(()->{if(closed||token!=generation||Minecraft.getInstance().screen!=this)return;picking=false;notice=error!=null?message(error):saved.isEmpty()?"":tr("saved",saved);});
        },"MyCinema-catalog-json-save");picker.setDaemon(true);picker.start();
    }
    private void fail(){cancelServer();pending=false;review=null;notice=tr("invalid");}
    private void cancelServer(){if(!request.isEmpty()&&minecraft!=null&&minecraft.getConnection()!=null)send("cancel",0,"");request="";}
    private static List<String> split(String text){List<String> result=new ArrayList<>();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+CatalogExchangeNetwork.CHUNK_CHARS);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;result.add(text.substring(start,end));start=end;}return result;}
    @Override public void onClose(){if(closed)return;closed=true;generation++;cancelServer();minecraft.setScreen(back);refresh.run();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){super.tick();ticks++;if(minecraft.player==null||minecraft.level==null){closed=true;generation++;super.onClose();return;}if(pending&&ticks-sentAt>200){cancelServer();pending=false;review=null;notice=tr("timeout");}}
}
