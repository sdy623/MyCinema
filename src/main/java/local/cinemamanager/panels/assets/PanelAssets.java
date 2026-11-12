package local.cinemamanager.panels.assets;

import local.cinemamanager.*;
import local.cinemamanager.panels.PanelGroup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** All live-world decisions run on the server thread. Validation alone runs on one bounded worker. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class PanelAssets {
    private static final Map<MinecraftServer,RuntimeState> SERVERS=new IdentityHashMap<>();
    private static final long IDLE_TICKS=300,MAX_TICKS=20*900;
    private static final int MAX_UPLOADS=4,MAX_DOWNLOADS=8;
    private static final class RuntimeState{
        final AssetStore store;final Map<UUID,Upload> uploads=new HashMap<>();final Map<UUID,Download> downloads=new HashMap<>();
        final Map<UUID,Window> windows=new HashMap<>();
        final ThreadPoolExecutor validator=new ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,new ArrayBlockingQueue<>(MAX_UPLOADS),r->{Thread t=new Thread(r,"MyCinema-asset-validation");t.setDaemon(true);return t;});
        RuntimeState(MinecraftServer server)throws IOException{store=new AssetStore(server.getWorldPath(LevelResource.ROOT).resolve("mycinema-panel-assets-v1"));}
        void close(){validator.shutdownNow();for(Download d:downloads.values())d.close();downloads.clear();store.close();uploads.clear();windows.clear();}
    }
    private static final class Window{long start;int count;final Set<UUID> used=new HashSet<>();Window(long tick){start=tick;}}
    private static class Transfer{
        final ServerPlayer actor;final AssetPanelLease lease;final AssetFilmLease filmLease;final String request,token=UUID.randomUUID().toString();final long started;
        long touched,packetTick=Long.MIN_VALUE;int packetCount;
        Transfer(ServerPlayer actor,InfoTile tile,String signature,String request){this.actor=actor;this.lease=new AssetPanelLease(tile,signature);this.filmLease=null;this.request=request;started=touched=tick(actor);}
        Transfer(ServerPlayer actor,AssetFilmLease lease,String request){this.actor=actor;this.lease=null;this.filmLease=lease;this.request=request;started=touched=tick(actor);}
        boolean current(boolean upload,String ref){return validActor(actor)&&(filmLease!=null?upload&&filmLease.uploadCurrent(actor):upload?lease.uploadCurrent(actor):lease.downloadCurrent(actor,ref));}
        boolean rate(){long now=tick(actor);if(now!=packetTick){packetTick=now;packetCount=0;}return ++packetCount<=2;}
        boolean expired(){long now=tick(actor);return now<touched||now-touched>IDLE_TICKS||now-started>MAX_TICKS;}
    }
    private static final class Upload extends Transfer{
        final AssetStore.Upload data;Future<AssetStore.Validated> validation;
        Upload(ServerPlayer a,InfoTile t,String s,String r,AssetStore.Upload data){super(a,t,s,r);this.data=data;}
        Upload(ServerPlayer a,AssetFilmLease lease,String r,AssetStore.Upload data){super(a,lease,r);this.data=data;}
    }
    private static final class Download extends Transfer{
        final String reference;final long bytes;final InputStream input;int sequence;long sent;boolean waiting;
        Download(ServerPlayer a,InfoTile t,String s,String r,String reference,long bytes,InputStream input){super(a,t,s,r);this.reference=reference;this.bytes=bytes;this.input=input;}
        void close(){try{input.close();}catch(IOException ignored){}}
    }
    private static long tick(ServerPlayer actor){return actor.getServer().getTickCount();}
    private static boolean validActor(ServerPlayer actor){return actor!=null&&actor.getServer()!=null&&actor.getServer().isSameThread()&&actor.isAlive()&&!(actor instanceof FakePlayer)&&actor.connection!=null&&actor.connection.isAcceptingMessages()&&actor.getServer().getPlayerList().getPlayer(actor.getUUID())==actor;}
    private static boolean loaded(ServerPlayer actor,InfoTile tile){return tile!=null&&tile.getLevel()==actor.level()&&!tile.isRemoved()&&actor.level().hasChunkAt(tile.getBlockPos())&&actor.level().getBlockEntity(tile.getBlockPos())==tile;}
    private static boolean editable(ServerPlayer actor,InfoTile tile){if(!tile.permits(actor,8))return false;for(InfoTile member:PanelGroup.of(tile).members())if(!member.permits(actor,Double.POSITIVE_INFINITY))return false;return true;}
    private static RuntimeState runtime(MinecraftServer server)throws IOException{RuntimeState state=SERVERS.get(server);if(state==null){state=new RuntimeState(server);SERVERS.put(server,state);}return state;}
    private static RuntimeState runtime(InfoTile tile)throws IOException{if(!(tile.getLevel() instanceof net.minecraft.server.level.ServerLevel level)||!level.getServer().isSameThread())throw new IOException("asset_permission");return runtime(level.getServer());}
    public static boolean exists(InfoTile tile,String reference){try{return AssetReference.isAsset(reference)&&runtime(tile).store.exists(reference);}catch(IOException e){return false;}}
    /** Grant helper after InfoTile authorizes the clicked panel at eight metres. Group members may be farther away. */
    public static boolean mayUseAsset(ServerPlayer actor,InfoTile tile,String reference){try{return validActor(actor)&&loaded(actor,tile)&&tile.permits(actor,Double.POSITIVE_INFINITY)&&runtime(tile).store.authorized(actor.getUUID(),reference);}catch(IOException e){return false;}}
    /** Parent grant also checks its captured panel identity and owner before calling this. */
    public static boolean current(InfoTile tile,UUID author,String reference){try{return author!=null&&runtime(tile).store.authorized(author,reference);}catch(IOException e){return false;}}
    public static boolean authorizesAsset(ServerLevel level,UUID author,String reference){try{return level!=null&&level.getServer().isSameThread()&&author!=null&&AssetReference.isAsset(reference)&&runtime(level.getServer()).store.authorized(author,reference);}catch(IOException failure){return false;}}
    /** No image decoding, URL fetch, or arbitrary path access on the server tick thread. */
    public static local.cinemamanager.advertising.AdvertisingShape advertisingShape(InfoTile tile,String reference){
        try{
            if(!AssetReference.isAsset(reference)||!reference.equals(tile.approvedPoster()))return local.cinemamanager.advertising.AdvertisingShape.RECTANGLE;
            AssetStore store=runtime(tile).store;
            return store.exists(reference)?local.cinemamanager.advertising.AdvertisingShape.fromAsset(reference,store.metadata(reference)):
                    local.cinemamanager.advertising.AdvertisingShape.RECTANGLE;
        }catch(IOException unavailable){return local.cinemamanager.advertising.AdvertisingShape.RECTANGLE;}
    }
    private static boolean request(RuntimeState state,ServerPlayer actor,String request)throws IOException{
        UUID id;try{id=UUID.fromString(request);}catch(RuntimeException e){return false;}
        long now=tick(actor);Window w=state.windows.computeIfAbsent(actor.getUUID(),x->new Window(now));
        if(now<w.start||now-w.start>=20){w.start=now;w.count=0;}
        // Keep the same admission limit, but do not make legitimate congestion look like a lost packet.
        if(++w.count>4)throw new IOException("asset_busy");
        if(w.used.contains(id))return false;
        // Session-local bounded replay tombstones. At capacity, deny until disconnect, never reuse tokens.
        if(w.used.size()>=4096)return false;w.used.add(id);return true;
    }
    private static InfoTile target(ServerPlayer actor,net.minecraft.core.BlockPos pos,String identity,String signature){
        if(!actor.level().hasChunkAt(pos)||!(actor.level().getBlockEntity(pos) instanceof InfoTile tile)||tile.isRemoved()||!tile.identity.toString().equals(identity)||!PanelGroup.of(tile).signature().equals(signature))return null;return tile;
    }
    private static void reply(ServerPlayer actor,String request,String token,String status,String reference,long bytes,int sequence){PacketDistributor.sendToPlayer(actor,new AssetNetwork.Reply(request,token,status,reference,bytes,sequence));}
    private static String error(Throwable error){String code=error.getMessage();return code!=null&&code.matches("asset_[a-z_]{1,32}")?code:"asset_storage_unavailable";}
    public static void begin(ServerPlayer actor,AssetNetwork.Begin packet){
        if(!validActor(actor))return;
        try{
            RuntimeState state=runtime(actor.getServer());if(!request(state,actor,packet.request()))return;
            InfoTile tile=target(actor,packet.pos(),packet.identity(),packet.signature());
            if(tile==null||!editable(actor,tile))throw new IOException("asset_permission");
            if(state.uploads.containsKey(actor.getUUID())||state.uploads.size()>=MAX_UPLOADS)throw new IOException("asset_busy");
            AssetStore.Upload data=state.store.begin(packet.bytes(),packet.hash(),AssetLimits.imageBytes(),AssetLimits.worldBytes());
            Upload upload=new Upload(actor,tile,packet.signature(),packet.request(),data);state.uploads.put(actor.getUUID(),upload);
            reply(actor,upload.request,upload.token,"ready","",packet.bytes(),0);
        }catch(IOException e){reply(actor,packet.request(),"",error(e),"",0,0);}
    }
    public static void beginFilm(ServerPlayer actor,AssetNetwork.FilmBegin packet){
        if(!validActor(actor))return;
        try{
            RuntimeState state=runtime(actor.getServer());if(!request(state,actor,packet.request()))return;
            AssetNetwork.FilmTarget target=packet.target();
            if(!actor.level().hasChunkAt(target.pos())||!(actor.level().getBlockEntity(target.pos()) instanceof ManagerTile manager))throw new IOException("asset_permission");
            AssetFilmLease lease=new AssetFilmLease(manager,target);
            if(!lease.uploadCurrent(actor))throw new IOException("asset_permission");
            if(state.uploads.containsKey(actor.getUUID())||state.uploads.size()>=MAX_UPLOADS)throw new IOException("asset_busy");
            AssetStore.Upload data=state.store.begin(packet.bytes(),packet.hash(),AssetLimits.imageBytes(),AssetLimits.worldBytes());
            Upload upload=new Upload(actor,lease,packet.request(),data);state.uploads.put(actor.getUUID(),upload);
            reply(actor,upload.request,upload.token,"ready","",packet.bytes(),0);
        }catch(IOException e){reply(actor,packet.request(),"",error(e),"",0,0);}
    }
    public static void chunk(ServerPlayer actor,AssetNetwork.UploadChunk packet){
        if(!validActor(actor))return;RuntimeState state=SERVERS.get(actor.getServer());if(state==null)return;Upload u=state.uploads.get(actor.getUUID());if(u==null||!u.token.equals(packet.token()))return;
        try{
            if(!u.current(true,""))throw new IOException("asset_permission");if(u.expired())throw new IOException("asset_timeout");if(!u.rate()||u.validation!=null)throw new IOException("asset_bad_sequence");
            u.data.append(packet.sequence(),packet.bytes());u.touched=tick(actor);
            if(u.data.received()==u.data.expectedBytes()){
                u.validation=state.validator.submit(()->u.data.validate(AssetLimits.imageBytes()));reply(actor,u.request,u.token,"validating","",u.data.received(),u.data.nextSequence());
            }else reply(actor,u.request,u.token,"next","",u.data.received(),u.data.nextSequence());
        }catch(IOException|RejectedExecutionException e){failUpload(state,u,e);}
    }
    public static void download(ServerPlayer actor,AssetNetwork.Download packet){
        if(!validActor(actor))return;
        try{
            RuntimeState state=runtime(actor.getServer());if(!request(state,actor,packet.request()))return;
            InfoTile tile=target(actor,packet.pos(),packet.identity(),packet.signature());
            if(tile==null||tile.panelDistanceSquared(actor)>4096||!actor.level().mayInteract(actor,tile.getBlockPos())||!AssetReference.isAsset(packet.reference())||!packet.reference().equals(tile.approvedPoster()))throw new IOException("asset_permission");
            if(state.downloads.containsKey(actor.getUUID())||state.downloads.size()>=MAX_DOWNLOADS)throw new IOException("asset_busy");
            AssetStore.Metadata metadata=state.store.metadata(packet.reference());if(metadata==null)throw new IOException("asset_missing");
            if(packet.budget()<1||packet.budget()>AssetLimits.ABSOLUTE_IMAGE_BYTES)throw new IOException("asset_too_large");
            if(metadata.bytes()>packet.budget()){reply(actor,packet.request(),"","asset_too_large","",metadata.bytes(),0);return;}
            Download d=new Download(actor,tile,packet.signature(),packet.request(),packet.reference(),metadata.bytes(),state.store.open(packet.reference()));state.downloads.put(actor.getUUID(),d);
            reply(actor,d.request,d.token,"download",d.reference,d.bytes,0);
        }catch(IOException e){reply(actor,packet.request(),"",error(e),"",0,0);}
    }
    public static void control(ServerPlayer actor,AssetNetwork.Control packet){
        if(!validActor(actor))return;RuntimeState state=SERVERS.get(actor.getServer());if(state==null)return;
        Upload u=state.uploads.get(actor.getUUID());if(u!=null&&u.token.equals(packet.token())){if(packet.cancel())failUpload(state,u,"asset_cancelled");else if(!u.current(true,""))failUpload(state,u,"asset_permission");else if(u.expired())failUpload(state,u,"asset_timeout");return;}
        Download d=state.downloads.get(actor.getUUID());if(d==null||!d.token.equals(packet.token()))return;
        if(packet.cancel()){failDownload(state,d,"asset_cancelled");return;}
        if(!d.current(false,d.reference)){failDownload(state,d,"asset_permission");return;}
        if(d.expired()){failDownload(state,d,"asset_timeout");return;}
        if(!d.rate()||!d.waiting||packet.sequence()!=d.sequence){failDownload(state,d,"asset_bad_sequence");return;}
        d.waiting=false;d.touched=tick(actor);
        if(d.sent==d.bytes){reply(actor,d.request,d.token,"end",d.reference,d.bytes,d.sequence);state.downloads.remove(actor.getUUID());d.close();}
    }
    private static void failUpload(RuntimeState state,Upload u,String reason){failUpload(state,u,new IOException(reason));}
    private static void failUpload(RuntimeState state,Upload u,Throwable failure){state.uploads.remove(u.actor.getUUID());if(u.validation!=null)u.validation.cancel(true);state.store.cancel(u.data);reply(u.actor,u.request,u.token,error(failure),AssetFailure.detail(failure),u.data.received(),u.data.nextSequence());}
    private static void failDownload(RuntimeState state,Download d,String reason){state.downloads.remove(d.actor.getUUID());d.close();reply(d.actor,d.request,d.token,reason,"",d.sent,d.sequence);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        RuntimeState state=SERVERS.get(event.getServer());if(state==null)return;
        if(event.getServer().getTickCount()%20==0)state.store.retryCleanup();
        for(Upload u:List.copyOf(state.uploads.values())){
            if(!u.current(true,"")){failUpload(state,u,"asset_permission");continue;}
            if(u.expired()){failUpload(state,u,"asset_timeout");continue;}
            if(u.validation!=null&&u.validation.isDone())try{
                AssetStore.Validated validated=u.validation.get();
                // Both permission and policy limits can have changed while validation was off-thread.
                if(!u.current(true,""))throw new IOException("asset_permission");
                if(u.data.expectedBytes()>AssetLimits.imageBytes())throw new IOException("asset_too_large");
                String reference=state.store.commit(u.data,validated,u.actor.getUUID(),AssetLimits.worldBytes());state.uploads.remove(u.actor.getUUID());reply(u.actor,u.request,u.token,"complete",reference,u.data.received(),u.data.nextSequence());
            }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();failUpload(state,u,e instanceof ExecutionException&&e.getCause()!=null?e.getCause():e);}
        }
        for(Download d:List.copyOf(state.downloads.values())){
            if(!d.current(false,d.reference)){failDownload(state,d,"asset_permission");continue;}
            if(d.expired()){failDownload(state,d,"asset_timeout");continue;}
            if(!d.waiting)try{int wanted=(int)Math.min(AssetLimits.CHUNK_BYTES,d.bytes-d.sent);if(wanted<1)throw new IOException("asset_bad_length");byte[] bytes=d.input.readNBytes(wanted);if(bytes.length!=wanted)throw new IOException("asset_bad_length");
                PacketDistributor.sendToPlayer(d.actor,new AssetNetwork.DownloadChunk(d.token,d.sequence,bytes));d.sequence++;d.sent+=bytes.length;d.waiting=true;
            }catch(IOException e){failDownload(state,d,error(e));}
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer actor){RuntimeState state=SERVERS.get(actor.getServer());if(state==null)return;Upload u=state.uploads.remove(actor.getUUID());if(u!=null){if(u.validation!=null)u.validation.cancel(true);state.store.cancel(u.data);}Download d=state.downloads.remove(actor.getUUID());if(d!=null)d.close();state.windows.remove(actor.getUUID());}}
    @SubscribeEvent public static void stop(ServerStoppedEvent event){RuntimeState state=SERVERS.remove(event.getServer());if(state!=null)state.close();}
    private PanelAssets(){}
}
