package local.cinemamanager.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A local selection never transmits bytes until its explicit server-upload confirmation is accepted. */
public final class FilmPictureActions {
    public record Snapshot(PanelAssetClient.Context context,long imageBudget,long worldBudget,String poster){}
    private final Screen owner;
    private final Supplier<Snapshot> snapshot;
    private final Consumer<String> apply,notice;
    private long generation;
    private boolean busy,dialog;
    private Screen confirmationScreen;
    private CompletableFuture<?> preparation,transfer;
    public FilmPictureActions(Screen owner,Supplier<Snapshot> snapshot,Consumer<String> apply,Consumer<String> notice){
        this.owner=owner;this.snapshot=snapshot;this.apply=apply;this.notice=notice;
    }
    public boolean busy(){return busy;}
    private static String tr(String key,Object...args){return I18n.get("mycinema.picture."+key,args);}
    private boolean current(long token,Snapshot captured){return token==generation&&captured.equals(snapshot.get());}
    public void choose(){
        if(busy)return;
        Snapshot captured=snapshot.get();if(captured==null)return;
        busy=true;long token=++generation;notice.accept(tr("choosing"));
        Thread picker=new Thread(()->{
            String chosen=null;Throwable failure=null;
            try(MemoryStack stack=MemoryStack.stackPush()){
                PointerBuffer filters=stack.mallocPointer(1);filters.put(stack.UTF8("*")).flip();
                chosen=TinyFileDialogs.tinyfd_openFileDialog(tr("choose_title"),null,filters,tr("choose_filter"),false);
            }catch(RuntimeException|LinkageError unavailable){failure=unavailable;}
            String selected=chosen;Throwable problem=failure;
            Minecraft.getInstance().execute(()->{
                if(!current(token,captured)||Minecraft.getInstance().screen!=owner)return;
                if(problem!=null){busy=false;notice.accept(tr("picker_unavailable"));return;}
                if(selected==null||selected.isBlank()){busy=false;notice.accept("");return;}
                try{prepare(Path.of(selected),token,captured);}catch(RuntimeException invalid){busy=false;notice.accept(tr("asset_local_missing"));}
            });
        },"MyCinema-film-poster-picker");picker.setDaemon(true);picker.start();
    }
    private void prepare(Path path,long token,Snapshot captured){
        notice.accept(tr("checking"));var mc=Minecraft.getInstance();
        var future=PanelAssetClient.prepare(path);preparation=future;
        future.whenComplete((selection,error)->mc.execute(()->{
            if(!current(token,captured)||mc.screen!=owner)return;
            preparation=null;
            if(error!=null){busy=false;notice.accept(PictureActions.failure(error));return;}
            if(selection.bytes()>captured.imageBudget()){busy=false;notice.accept(tr("asset_size_budget",selection.bytes(),captured.imageBudget()));return;}
            dialog=true;
            ContinuousConfirmation confirmation=new ContinuousConfirmation(accepted->{
                if(!current(token,captured)||mc.screen!=confirmationScreen)return;
                confirmationScreen=null;dialog=false;mc.setScreen(owner);
                if(!accepted){busy=false;notice.accept("");return;}
                notice.accept(tr("uploading",0,selection.bytes()));
                var upload=PanelAssetClient.upload(captured.context(),selection,true,p->{if(current(token,captured))notice.accept(tr(p.state().startsWith("asset_")?p.state():"progress",p.transferred(),p.total()));});
                transfer=upload;
                upload.whenComplete((reference,problem)->mc.execute(()->{
                    if(!current(token,captured)||mc.screen!=owner)return;
                    transfer=null;busy=false;
                    if(problem!=null)notice.accept(PictureActions.failure(problem));
                    else{apply.accept(reference);notice.accept(I18n.get("mycinema.catalog.poster_uploaded_draft"));}
                }));
            },Component.translatable("mycinema.picture.upload_title"),Component.literal(I18n.get("mycinema.catalog.poster_upload_confirm",selection.filename(),selection.bytes(),captured.imageBudget()/1048576,captured.worldBudget()/1048576)));
            confirmationScreen=confirmation;mc.setScreen(confirmation);
        }));
    }
    /** Screen replacement cancels our own work, but opening this action's confirmation is intentional. */
    public void removed(){if(!dialog)cancel();}
    public void cancel(){
        generation++;busy=false;dialog=false;confirmationScreen=null;
        if(preparation!=null){preparation.cancel(true);preparation=null;}
        // Cancel only this action's future; never cancel an unrelated panel upload.
        if(transfer!=null){transfer.cancel(true);transfer=null;}
    }
}
