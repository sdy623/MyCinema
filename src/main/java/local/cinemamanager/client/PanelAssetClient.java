package local.cinemamanager.client;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.assets.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Client files are only read after the local editor selects them. Server downloads remain in memory. */
@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT)
public final class PanelAssetClient {
    public record Context(ClientLevel level,BlockPos pos,UUID identity,String signature,AssetNetwork.FilmTarget film){
        public Context(ClientLevel level,BlockPos pos,UUID identity,String signature){this(level,pos,identity,signature,null);}
        public static Context film(ClientLevel level,AssetNetwork.FilmTarget target){return new Context(level,target.pos(),UUID.fromString(target.identity()),"",target);}
    }
    /** Local-only selection. Neither a local path nor filename is transmitted to the server. */
    public static final class Selection {
        private final Path path;private final String filename,hash;private final long bytes,modified;private final Object fileKey;
        private Selection(Path path,String hash,BasicFileAttributes attributes){this.path=path;filename=path.getFileName().toString();this.hash=hash;bytes=attributes.size();modified=attributes.lastModifiedTime().toMillis();fileKey=attributes.fileKey();}
        public String filename(){return filename;}
        public long bytes(){return bytes;}
        private boolean current()throws IOException{BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return a.isRegularFile()&&a.size()==bytes&&a.lastModifiedTime().toMillis()==modified&&Objects.equals(a.fileKey(),fileKey);}
    }
    public record Progress(String state,long transferred,long total){}
    private static final ThreadPoolExecutor FILES=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"MyCinema-local-picture");t.setDaemon(true);return t;});
    private static Upload upload;private static Read download;
    private static final AssetRequestAdmission START_ADMISSION=new AssetRequestAdmission();
    private static final long IDLE_NANOS=TimeUnit.SECONDS.toNanos(15),TOTAL_NANOS=TimeUnit.MINUTES.toNanos(15);
    private static final class Upload {
        final Context context;final Selection selection;final Consumer<Progress> progress;final CompletableFuture<String> result=new CompletableFuture<>();final String request=UUID.randomUUID().toString();
        String token="";int sequence;long sent,acknowledged,started=System.nanoTime(),touched=started;boolean waiting=true,requested;volatile boolean closed;CompletableFuture<byte[]> next;
        Upload(Context context,Selection selection,Consumer<Progress> progress){this.context=context;this.selection=selection;this.progress=progress;}
        void notifyProgress(String state){if(progress!=null)try{progress.accept(new Progress(state,acknowledged,selection.bytes));}catch(RuntimeException ignored){}}
        void close(){closed=true;if(next!=null)next.cancel(true);}
        byte[] nextChunk(long offset)throws IOException{
            if(closed||!selection.current())throw new IOException("asset_local_changed");
            int wanted=(int)Math.min(AssetLimits.CHUNK_BYTES,selection.bytes-offset);if(offset<0||wanted<1)throw new IOException("asset_bad_length");
            byte[] chunk=new byte[wanted];ByteBuffer buffer=ByteBuffer.wrap(chunk);
            // Each bounded read owns its channel. Cancel never waits for file I/O on the client thread.
            try(var input=Files.newByteChannel(selection.path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                input.position(offset);
                while(buffer.hasRemaining()){
                    if(closed||Thread.currentThread().isInterrupted())throw new IOException("asset_cancelled");
                    if(input.read(buffer)<0)throw new IOException("asset_local_changed");
                }
            }
            if(closed||Thread.currentThread().isInterrupted())throw new IOException("asset_cancelled");return chunk;
        }
    }
    private static final class Read {
        final PosterRedirectClient.Context context;final CompletableFuture<byte[]> result;final String request=UUID.randomUUID().toString();final int budget;final long started=System.nanoTime();
        final QaRead qa;
        String token="",signature;long touched=started;byte[] bytes;int received,sequence;boolean requested;final MessageDigest digest=AssetStore.sha256();
        Read(PosterRedirectClient.Context context,int budget,CompletableFuture<byte[]> result,String signature,QaRead qa){this.context=context;this.budget=budget;this.result=result;this.signature=signature;this.qa=qa;}
    }
    /** Resolve, structurally inspect and hash one local file without blocking the render thread. */
    public static CompletableFuture<Selection> prepare(Path path){
        return async(()->{
            Path file=path.toRealPath();BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile())throw new IOException("asset_local_missing");AssetImageValidator.validate(file,AssetLimits.ABSOLUTE_IMAGE_BYTES);
            MessageDigest digest=AssetStore.sha256();long total=0;
            try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] chunk=new byte[32768];for(int n;(n=in.read(chunk))>=0;){if(Thread.currentThread().isInterrupted())throw new IOException("asset_cancelled");if(n>attributes.size()-total)throw new IOException("asset_local_changed");if(n>0){digest.update(chunk,0,n);total+=n;}}}
            if(total!=attributes.size())throw new IOException("asset_local_changed");
            Selection selection=new Selection(file,HexFormat.of().formatHex(digest.digest()),attributes);if(!selection.current())throw new IOException("asset_local_changed");return selection;
        });
    }
    /** Call on the client thread only after displaying filename, byte count and this server as destination. */
    public static CompletableFuture<String> upload(Context context,Selection selection,boolean confirmed,Consumer<Progress> progress){
        if(!confirmed)return CompletableFuture.failedFuture(new IOException("asset_confirmation_required"));
        if(upload!=null)return CompletableFuture.failedFuture(new IOException("asset_busy"));
        if(selection==null||!current(context))return CompletableFuture.failedFuture(new IOException("asset_stale"));
        Upload u=new Upload(context,selection,progress);upload=u;u.notifyProgress("starting");
        startRequests();return u.result;
    }
    public static void cancelUpload(){Upload u=upload;if(u!=null)failUpload(u,"asset_cancelled",true);}
    /** Existing PosterImages loader calls this, then its unchanged full decode/memory/GPU-budget pipeline. */
    public static byte[] read(PosterRedirectClient.Context context,int budget,BooleanSupplier cancelled)throws IOException{
        return read(context,budget,cancelled,"");
    }
    static byte[] read(PosterRedirectClient.Context context,int budget,BooleanSupplier cancelled,String qaAttempt)throws IOException{
        if(!AssetReference.isAsset(context.source())||budget<1)throw new IOException("asset_invalid_reference");
        QaRead qa=Boolean.getBoolean("mycinema.qa")?new QaRead(qaAttempt):null;qa(qa,"start","none");
        CompletableFuture<byte[]> result=new CompletableFuture<>();
        Minecraft.getInstance().execute(()->{
            if(result.isDone())return;
            if(!PosterRedirectClient.current(context)||download!=null){result.completeExceptionally(new IOException("asset_busy"));return;}
            InfoTile tile=(InfoTile)context.level().getBlockEntity(context.pos());Read r=new Read(context,Math.min(budget,AssetLimits.ABSOLUTE_IMAGE_BYTES),result,tile.panelGroup.toString(),qa);download=r;
            if(qa!=null)qa.request=r.request;qa(qa,"queued","none");
            startRequests();
        });
        try{
            while(true){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){qa(qa,"cancel","CANCELLED");throw new PosterFailure(PosterFailure.Code.CANCELLED);}
                try{byte[] bytes=result.get(100,TimeUnit.MILLISECONDS);qa(qa,"ready","none");return bytes;}catch(TimeoutException ignored){}
                catch(InterruptedException e){Thread.currentThread().interrupt();qa(qa,"cancel","CANCELLED");throw new PosterFailure(PosterFailure.Code.CANCELLED);}
                catch(ExecutionException e){
                    if(Boolean.getBoolean("mycinema.qa")){
                        String original=e.getCause() instanceof PosterFailure f?f.code().name():qaFailure(e.getCause()==null?null:e.getCause().getMessage());
                        qa(qa,original.equals("CANCELLED")||original.equals("asset_cancelled")||original.equals("asset_stale")?"cancel":"failure",original);
                    }
                    if(e.getCause() instanceof PosterFailure f)throw f;String reason=e.getCause()==null?"":e.getCause().getMessage();throw new PosterFailure(PosterFailure.assetCode(reason));}
            }
        }finally{result.completeExceptionally(new IOException("asset_cancelled"));Minecraft.getInstance().execute(()->{if(download!=null&&download.result==result)failRead(download,"asset_cancelled",true);});}
    }
    private static boolean current(Context c){
        var mc=Minecraft.getInstance();
        if(c==null||c.level()==null||mc.level!=c.level()||mc.getConnection()==null||mc.player==null||!mc.player.isAlive())return false;
        // Tablet control may target a manager outside the client's loaded chunks. Its server-issued
        // identity stays in the request; the server rechecks loaded instance, controller, owner and revisions.
        if(c.film()!=null)return c.pos().equals(c.film().pos())&&c.identity().toString().equals(c.film().identity());
        return c.level().hasChunkAt(c.pos())&&c.level().getBlockEntity(c.pos()) instanceof InfoTile tile&&tile.identity.equals(c.identity())&&tile.panelGroup.toString().equals(c.signature())&&tile.panelDistanceSquared(mc.player)<=64;
    }
    private static boolean current(Read r){var player=Minecraft.getInstance().player;return player!=null&&player.isAlive()&&PosterRedirectClient.current(r.context)&&r.context.level().getBlockEntity(r.context.pos()) instanceof InfoTile tile&&tile.panelGroup.toString().equals(r.signature)&&tile.panelDistanceSquared(player)<=4096;}
    private static boolean expired(long start,long touched){long now=System.nanoTime();return now-touched>IDLE_NANOS||now-start>TOTAL_NANOS;}
    /** At most one waiting read and upload; oldest start wins, and waiting counts toward existing deadlines. */
    private static void startRequests(){
        Upload u=upload;Read r=download;
        boolean uploadReady=u!=null&&!u.requested&&!u.result.isDone()&&current(u.context)&&!expired(u.started,u.touched);
        boolean readReady=r!=null&&!r.requested&&!r.result.isDone()&&current(r)&&!expired(r.started,r.touched);
        if(!uploadReady&&!readReady||!START_ADMISSION.take(System.nanoTime()))return;
        if(readReady&&(!uploadReady||r.started<=u.started)){
            r.requested=true;qa(r.qa,"request","none");
            PacketDistributor.sendToServer(new AssetNetwork.Download(r.context.pos(),r.context.identity().toString(),r.signature,r.request,r.context.source(),r.budget));
        }else{
            u.requested=true;
            if(u.context.film()!=null)PacketDistributor.sendToServer(new AssetNetwork.FilmBegin(u.context.film(),u.request,u.selection.bytes,u.selection.hash));
            else PacketDistributor.sendToServer(new AssetNetwork.Begin(u.context.pos(),u.context.identity().toString(),u.context.signature(),u.request,u.selection.bytes,u.selection.hash));
        }
    }
    public static void receive(AssetNetwork.Reply reply){
        Upload u=upload;if(u!=null&&u.request.equals(reply.request())){
            if(!u.requested)return;
            if(!current(u.context)){failUpload(u,"asset_stale",true);return;}
            if(!u.token.isEmpty()&&!u.token.equals(reply.token()))return;
            if(reply.state().startsWith("asset_")){failUpload(u,AssetFailure.fromWire(reply.state(),reply.reference()),false);return;}
            if(reply.state().equals("ready")){
                if(!u.token.isEmpty()||!validToken(reply.token())||reply.bytes()!=u.selection.bytes||reply.sequence()!=0){failUpload(u,"asset_bad_sequence",true);return;}
                u.token=reply.token();u.waiting=false;u.touched=System.nanoTime();u.notifyProgress("uploading");return;
            }
            if(u.token.isEmpty())return;
            if(reply.state().equals("next")||reply.state().equals("validating")){
                if(!u.waiting||reply.sequence()!=u.sequence||reply.bytes()!=u.sent){failUpload(u,"asset_bad_sequence",true);return;}
                u.acknowledged=reply.bytes();u.touched=System.nanoTime();u.waiting=reply.state().equals("validating");u.notifyProgress(reply.state().equals("validating")?"validating":"uploading");return;
            }
            if(reply.state().equals("complete")){
                if(reply.bytes()!=u.selection.bytes||reply.sequence()!=u.sequence||!reply.reference().equals(AssetReference.ofHash(u.selection.hash))||u.sent!=u.selection.bytes){failUpload(u,"asset_hash_mismatch",true);return;}
                upload=null;u.close();u.acknowledged=reply.bytes();u.notifyProgress("complete");u.result.complete(reply.reference());
            }
            return;
        }
        Read r=download;if(r==null||!r.request.equals(reply.request()))return;
        if(!r.requested)return;
        if(!current(r)){failRead(r,"asset_stale",true);return;}
        if(!r.token.isEmpty()&&!r.token.equals(reply.token()))return;
        if(reply.state().startsWith("asset_")){if(Boolean.getBoolean("mycinema.qa"))qa(r.qa,"response_error",qaFailure(reply.state()));if(reply.state().equals("asset_too_large")&&reply.bytes()>0&&reply.bytes()<=AssetLimits.ABSOLUTE_IMAGE_BYTES)failRead(r,new PosterFailure(PosterFailure.Code.DOWNLOAD_BUDGET,reply.bytes(),r.budget,false),false);else failRead(r,reply.state(),false);return;}
        if(reply.state().equals("download")){
            if(!r.token.isEmpty()||!validToken(reply.token())||!reply.reference().equals(r.context.source())||reply.bytes()<12||reply.bytes()>r.budget||reply.sequence()!=0){failRead(r,"asset_bad_length",true);return;}
            r.token=reply.token();
            qa(r.qa,"response","none");
            Runtime heap=Runtime.getRuntime();long available=heap.maxMemory()-(heap.totalMemory()-heap.freeMemory());
            if(reply.bytes()>Math.max(0,available-32L*1024*1024)/3){failRead(r,new PosterFailure(PosterFailure.Code.MEMORY_BUDGET,reply.bytes()*3,Math.max(0,available-32L*1024*1024)),true);return;}
            r.bytes=new byte[(int)reply.bytes()];r.touched=System.nanoTime();return;
        }
        if(reply.state().equals("end")){
            if(r.bytes==null||r.received!=r.bytes.length||reply.bytes()!=r.received||reply.sequence()!=r.sequence||!reply.reference().equals(r.context.source())||!HexFormat.of().formatHex(r.digest.digest()).equals(AssetReference.hash(r.context.source()))){failRead(r,"asset_hash_mismatch",true);return;}
            download=null;r.result.complete(r.bytes);r.bytes=null;
        }
    }
    public static void receive(AssetNetwork.DownloadChunk chunk){
        Read r=download;if(r==null||!r.token.equals(chunk.token()))return;
        if(!current(r)||r.bytes==null){failRead(r,"asset_stale",true);return;}
        byte[] bytes=chunk.bytes();if(chunk.sequence()!=r.sequence||bytes.length<1||bytes.length>AssetLimits.CHUNK_BYTES||bytes.length>r.bytes.length-r.received){failRead(r,"asset_bad_sequence",true);return;}
        System.arraycopy(bytes,0,r.bytes,r.received,bytes.length);r.digest.update(bytes);r.received+=bytes.length;r.sequence++;r.touched=System.nanoTime();PacketDistributor.sendToServer(new AssetNetwork.Control(r.token,r.sequence,false));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        Upload u=upload;if(u!=null){
            if(u.result.isCancelled()){failUpload(u,"asset_cancelled",true);}
            else if(!current(u.context)){failUpload(u,"asset_stale",true);}
            else if(expired(u.started,u.touched)){failUpload(u,"asset_timeout",true);}
            else if(!u.waiting){
                if(u.next==null){long offset=u.sent;u.next=async(()->u.nextChunk(offset));}
                if(u.next.isDone())try{byte[] bytes=u.next.join();u.next=null;if(!current(u.context)){failUpload(u,"asset_stale",true);return;}
                    PacketDistributor.sendToServer(new AssetNetwork.UploadChunk(u.token,u.sequence,bytes));u.sequence++;u.sent+=bytes.length;u.waiting=true;u.touched=System.nanoTime();
                }catch(CompletionException e){failUpload(u,code(e.getCause()),true);}catch(CancellationException e){failUpload(u,"asset_cancelled",true);}
            }
        }
        Read r=download;if(r!=null){if(r.result.isDone())failRead(r,"asset_cancelled",true);else if(!current(r))failRead(r,"asset_stale",true);else if(expired(r.started,r.touched))failRead(r,"asset_timeout",true);}
        startRequests();
    }
    public static void clear(){cancelUpload();Read r=download;if(r!=null)failRead(r,"asset_cancelled",true);}
    private static void failUpload(Upload u,String code,boolean cancel){failUpload(u,new IOException(code),cancel);}
    private static void failUpload(Upload u,IOException failure,boolean cancel){if(upload!=u)return;upload=null;if(cancel&&!u.token.isEmpty()&&Minecraft.getInstance().getConnection()!=null)PacketDistributor.sendToServer(new AssetNetwork.Control(u.token,u.sequence,true));u.close();u.notifyProgress(code(failure));u.result.completeExceptionally(failure);}
    private static void failRead(Read r,String code,boolean cancel){failRead(r,new IOException(code),cancel);}
    private static void failRead(Read r,Throwable failure,boolean cancel){if(download!=r)return;download=null;if(cancel&&!r.token.isEmpty()&&Minecraft.getInstance().getConnection()!=null)PacketDistributor.sendToServer(new AssetNetwork.Control(r.token,r.sequence,true));r.bytes=null;r.result.completeExceptionally(failure);}
    private static boolean validToken(String token){try{return UUID.fromString(token).toString().equals(token);}catch(RuntimeException e){return false;}}
    private static String code(Throwable error){String code=error==null?null:error.getMessage();return code!=null&&code.matches("asset_[a-z_]{1,32}")?code:"asset_local_missing";}
    /** Diagnostic identity only; it is never used to authorize, deduplicate or cancel a transfer. */
    private static final class QaRead {
        final String attempt;final long started=System.nanoTime();volatile String request="none";
        QaRead(String attempt){this.attempt=attempt==null||attempt.isEmpty()?UUID.randomUUID().toString():attempt;}
    }
    private static void qa(QaRead read,String event,String failure){
        if(!Boolean.getBoolean("mycinema.qa")||read==null)return;
        try{org.slf4j.LoggerFactory.getLogger("MyCinemaPosterQA").info(
                "asset event={} source_kind=private_asset attempt={} request={} elapsed_ms={} failure={}",
                event,read.attempt,read.request,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-read.started),failure);
        }catch(RuntimeException ignored){/* Diagnostics must not change transfer outcomes. */}
    }
    /** Do not echo arbitrary exception or server response text into QA logs. */
    private static String qaFailure(String code){
        if(code==null)return "asset_unknown";
        return switch(code){
            case "asset_author_limit","asset_bad_chunk","asset_bad_length","asset_bad_sequence","asset_busy",
                    "asset_cancelled","asset_confirmation_required","asset_dimensions","asset_empty_alpha",
                    "asset_hash_mismatch","asset_invalid_alpha","asset_invalid_image","asset_invalid_metadata",
                    "asset_invalid_reference","asset_local_changed","asset_local_missing","asset_missing",
                    "asset_permission","asset_stale","asset_storage_unavailable","asset_timeout","asset_too_large",
                    "asset_unsupported_format","asset_world_quota"->code;
            default->"asset_unknown";
        };
    }
    interface IoSupplier<T>{T get()throws IOException;}
    static <T> CompletableFuture<T> async(IoSupplier<T> supplier){
        CompletableFuture<T> result=new CompletableFuture<>();
        FutureTask<Void> task=new FutureTask<>(()->{
            if(result.isCancelled())return null;
            try{result.complete(supplier.get());}catch(IOException|RuntimeException e){result.completeExceptionally(e);}return null;
        });
        result.whenComplete((value,error)->{if(result.isCancelled()){task.cancel(true);FILES.remove(task);}});
        try{FILES.execute(task);}catch(RejectedExecutionException e){task.cancel(false);result.completeExceptionally(new IOException("asset_busy"));}
        return result;
    }
    private PanelAssetClient(){}
}
