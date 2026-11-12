package local.cinemamanager.client;

import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.PosterRedirectNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The worker waits off-thread; packets and tile checks always run on the client thread. */
public final class PosterRedirectClient {
    public record Context(ClientLevel level,BlockPos pos,UUID identity,String source,String publicationToken){}
    private static final class Pending {
        final Context context;
        final URI target;
        final CompletableFuture<Boolean> future;
        final long expires;
        boolean sent;
        Pending(Context context,URI target,CompletableFuture<Boolean> future){
            this.context=context;this.target=target;this.future=future;
            this.expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        }
    }
    private static final Map<String,Pending> PENDING=new LinkedHashMap<>();
    private static final PosterRequestAdmission ADMISSION=new PosterRequestAdmission();
    public static boolean current(Context context){
        var client=Minecraft.getInstance();return client.level==context.level()&&client.getConnection()!=null
                &&context.level().hasChunkAt(context.pos())&&context.level().getBlockEntity(context.pos()) instanceof InfoTile tile
                &&tile.identity.equals(context.identity())&&tile.publishedPoster.equals(context.source())&&tile.publishedPosterToken.equals(context.publicationToken());
    }
    public static boolean authorize(Context context,URI target,PosterHttpTransport.Control control)throws IOException{
        String request=UUID.randomUUID().toString();CompletableFuture<Boolean> result=new CompletableFuture<>();
        Minecraft.getInstance().execute(()->{
            if(result.isDone())return;
            enqueue(request,context,target,result);
        });
        try{while(true){control.check();try{return result.get(Math.min(100,control.remainingMillis()),TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.TimeoutException waiting){}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Poster request cancelled",e);}
            catch(java.util.concurrent.ExecutionException e){if(e.getCause() instanceof IOException io)throw io;throw new PosterFailure(PosterFailure.Code.NETWORK);}}}
        finally{result.complete(false);Minecraft.getInstance().execute(()->PENDING.remove(request));}
    }
    /** Render-thread refresh of cached final destinations, without blocking a frame. */
    public static CompletableFuture<Boolean> request(Context context,URI target){
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        enqueue(UUID.randomUUID().toString(),context,target,result);return result;
    }
    private static void enqueue(String id,Context context,URI target,CompletableFuture<Boolean> result){
        if(!current(context)){result.complete(false);return;}
        if(PENDING.size()>=PosterRequestAdmission.MAX_PENDING){result.completeExceptionally(new PosterFailure(PosterFailure.Code.UNAVAILABLE));return;}
        PENDING.put(id,new Pending(context,target,result));
        dispatch();
    }
    /** FIFO, ten dispatches/second maximum, sixteen on the wire; queue time counts toward the five-second deadline. */
    private static void dispatch(){
        long now=System.nanoTime();
        int inFlight=(int)PENDING.values().stream().filter(p->p.sent&&!p.future.isDone()).count();
        for(var item:PENDING.entrySet()){
            Pending p=item.getValue();
            if(p.sent||p.future.isDone()||now>=p.expires||!current(p.context))continue;
            if(!ADMISSION.take(now,inFlight))return;
            p.sent=true;
            Context c=p.context;
            PacketDistributor.sendToServer(new PosterRedirectNetwork.Request(c.pos(),c.identity().toString(),c.source(),p.target.toString(),item.getKey()));
            return;
        }
    }
    public static void tick(){long now=System.nanoTime();PENDING.entrySet().removeIf(e->{Pending p=e.getValue();if(now>=p.expires){p.future.completeExceptionally(new PosterFailure(PosterFailure.Code.TIMEOUT));return true;}if(!current(p.context)){p.future.complete(false);return true;}return p.future.isDone();});dispatch();}
    public static void receive(PosterRedirectNetwork.Reply reply){
        Pending pending=PENDING.get(reply.request());
        if(pending==null||!pending.sent)return;
        PENDING.remove(reply.request());
        if(System.nanoTime()>=pending.expires)pending.future.completeExceptionally(new PosterFailure(PosterFailure.Code.TIMEOUT));
        else pending.future.complete(reply.allowed()&&current(pending.context));
    }
    public static void clear(){for(Pending p:PENDING.values())p.future.complete(false);PENDING.clear();}
    private PosterRedirectClient(){}
}
