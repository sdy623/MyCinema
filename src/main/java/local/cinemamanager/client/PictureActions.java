package local.cinemamanager.client;

import local.cinemamanager.panels.PanelNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.*;
import java.util.UUID;
import java.util.function.*;

/** Explicit local selection and private-server upload are separate, visibly named user actions. */
public final class PictureActions {
    private final Screen owner;private final Supplier<PanelNetwork.View> snapshot;private final Consumer<String> apply,notice;
    private long generation;private boolean busy;
    public PictureActions(Screen owner,Supplier<PanelNetwork.View> snapshot,Consumer<String> apply,Consumer<String> notice){this.owner=owner;this.snapshot=snapshot;this.apply=apply;this.notice=notice;}
    public boolean busy(){return busy;}
    private static String tr(String k,Object...args){return I18n.get("mycinema.picture."+k,args);}
    public void choose(boolean upload){
        if(busy)return;busy=true;long token=++generation;notice.accept(tr("choosing"));
        Thread thread=new Thread(()->{
            String chosen=null;String failure=null;
            try(MemoryStack stack=MemoryStack.stackPush()){
                PointerBuffer filters=stack.mallocPointer(1);filters.put(stack.UTF8("*")).flip();
                chosen=TinyFileDialogs.tinyfd_openFileDialog(tr("choose_title"),null,filters,tr("choose_filter"),false);
            }catch(RuntimeException|LinkageError e){failure=tr("picker_unavailable");}
            String selected=chosen,error=failure;
            Minecraft.getInstance().execute(()->{if(token!=generation)return;busy=false;if(Minecraft.getInstance().screen!=owner)return;if(error!=null){notice.accept(error);return;}if(selected==null||selected.isBlank()){notice.accept("");return;}try{selected(Path.of(selected),upload);}catch(InvalidPathException invalid){notice.accept(tr("asset_local_missing"));}});
        },"MyCinema-picture-picker");thread.setDaemon(true);thread.start();
    }
    /** Drag/drop only selects a path; the visible confirmation still precedes transmission. */
    public void selected(Path path,boolean upload){
        if(busy)return;var mc=Minecraft.getInstance();if(mc.screen!=owner)return;
        if(!upload){
            String uri=path.toAbsolutePath().normalize().toUri().toString();long localToken=++generation;var localSnapshot=snapshot.get();
            mc.setScreen(new ContinuousConfirmation(accepted->{mc.setScreen(owner);if(accepted&&localToken==generation&&snapshot.get().identity().equals(localSnapshot.identity())&&snapshot.get().signature().equals(localSnapshot.signature()))apply.accept(uri);},Component.translatable("mycinema.picture.local_title"),Component.literal(tr("local_confirm",path.getFileName().toString()))));return;
        }
        busy=true;long token=++generation;notice.accept(tr("checking"));var captured=snapshot.get();var limits=PanelNetwork.JSON.fromJson(captured.json(),PanelNetwork.Data.class);
        PanelAssetClient.prepare(path).whenComplete((selection,error)->mc.execute(()->{
            if(token!=generation)return;busy=false;if(mc.screen!=owner)return;
            if(error!=null){notice.accept(failure(error));return;}
            if(selection.bytes()>limits.imageBudgetBytes()){notice.accept(tr("asset_size_budget",selection.bytes(),limits.imageBudgetBytes()));return;}
            mc.setScreen(new ContinuousConfirmation(accepted->{mc.setScreen(owner);if(!accepted){notice.accept("");return;}
                if(!snapshot.get().signature().equals(captured.signature())){notice.accept(tr("asset_stale"));return;}
                busy=true;notice.accept(tr("uploading",0,selection.bytes()));
                var context=new PanelAssetClient.Context(mc.level,captured.pos(),UUID.fromString(captured.identity()),captured.signature());
                PanelAssetClient.upload(context,selection,true,p->notice.accept(tr(p.state().startsWith("asset_")?p.state():"progress",p.transferred(),p.total()))).whenComplete((reference,problem)->{
                    if(token!=generation)return;busy=false;if(mc.screen!=owner)return;
                    if(problem!=null)notice.accept(failure(problem));else{notice.accept(tr("uploaded"));apply.accept(reference);}
                });
            },Component.translatable("mycinema.picture.upload_title"),Component.literal(tr("upload_confirm",selection.filename(),selection.bytes(),limits.imageBudgetBytes()/1048576,limits.worldBudgetBytes()/1048576))));
        }));
    }
    public void close(){generation++;busy=false;PanelAssetClient.cancelUpload();}
    public static String failure(Throwable error){Throwable e=error;for(int i=0;i<4&&e.getCause()!=null;i++)e=e.getCause();if(e instanceof local.cinemamanager.panels.assets.AssetFailure dimension)return tr("asset_dimensions_values",dimension.width(),dimension.height(),dimension.limit());String code=e.getMessage();
        if(e instanceof NoSuchFileException)code="asset_local_missing";else if(e instanceof AccessDeniedException||e instanceof SecurityException)code="asset_local_denied";
        if(code==null||!code.matches("asset_[a-z_]{1,32}"))code="asset_unavailable";return tr(code);}
}
