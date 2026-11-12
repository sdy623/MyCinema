package local.cinemamanager.panels.assets;

import io.netty.buffer.ByteBuf;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Strict bounded packets. Transfer tokens are minted by the server, never client chosen. */
public final class AssetNetwork {
    // At most 128 queued asset packets (~4 MiB of chunk arrays), four per connection.
    // The existing per-transfer main-thread rate and permission checks remain authoritative.
    private static final AssetIngress INGRESS=new AssetIngress(4,128,96,TimeUnit.SECONDS.toNanos(1));
    private static final StreamCodec<ByteBuf,String> ID=ByteBufCodecs.stringUtf8(36),REFERENCE=ByteBufCodecs.stringUtf8(79);
    public record Begin(BlockPos pos,String identity,String signature,String request,long bytes,String hash)implements CustomPacketPayload{
        public static final Type<Begin> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"asset_begin"));
        public static final StreamCodec<ByteBuf,Begin> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Begin::pos,ID,Begin::identity,ID,Begin::signature,ID,Begin::request,ByteBufCodecs.VAR_LONG,Begin::bytes,ByteBufCodecs.stringUtf8(64),Begin::hash,Begin::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Separate target type: film poster uploads never require or impersonate an information panel. */
    public record FilmTarget(BlockPos pos,String identity,String filmId,long catalogRevision,long filmRevision){
        public static final StreamCodec<ByteBuf,FilmTarget> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,FilmTarget::pos,ID,FilmTarget::identity,ID,FilmTarget::filmId,ByteBufCodecs.VAR_LONG,FilmTarget::catalogRevision,ByteBufCodecs.VAR_LONG,FilmTarget::filmRevision,FilmTarget::new);
    }
    public record FilmBegin(FilmTarget target,String request,long bytes,String hash)implements CustomPacketPayload{
        public static final Type<FilmBegin> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"film_asset_begin"));
        public static final StreamCodec<ByteBuf,FilmBegin> CODEC=StreamCodec.composite(FilmTarget.CODEC,FilmBegin::target,ID,FilmBegin::request,ByteBufCodecs.VAR_LONG,FilmBegin::bytes,ByteBufCodecs.stringUtf8(64),FilmBegin::hash,FilmBegin::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Download(BlockPos pos,String identity,String signature,String request,String reference,long budget)implements CustomPacketPayload{
        public static final Type<Download> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"asset_download"));
        public static final StreamCodec<ByteBuf,Download> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Download::pos,ID,Download::identity,ID,Download::signature,ID,Download::request,REFERENCE,Download::reference,ByteBufCodecs.VAR_LONG,Download::budget,Download::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Control(String token,int sequence,boolean cancel)implements CustomPacketPayload{
        public static final Type<Control> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"asset_control"));
        public static final StreamCodec<ByteBuf,Control> CODEC=StreamCodec.composite(ID,Control::token,ByteBufCodecs.INT,Control::sequence,ByteBufCodecs.BOOL,Control::cancel,Control::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Reply(String request,String token,String state,String reference,long bytes,int sequence)implements CustomPacketPayload{
        // reference is either a successful content reference or canonical numeric dimension detail
        // for asset_dimensions only. No packet field carries a local filename or path;
        // uploads send only the selected file's bytes and their bounded transfer metadata.
        public static final Type<Reply> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"asset_reply"));
        public static final StreamCodec<ByteBuf,Reply> CODEC=StreamCodec.composite(ID,Reply::request,ID,Reply::token,ByteBufCodecs.stringUtf8(40),Reply::state,REFERENCE,Reply::reference,ByteBufCodecs.VAR_LONG,Reply::bytes,ByteBufCodecs.INT,Reply::sequence,Reply::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final StreamCodec<ByteBuf,byte[]> CHUNK=new StreamCodec<>(){
        public byte[] decode(ByteBuf buf){int n=buf.readInt();if(n<1||n>AssetLimits.CHUNK_BYTES||n>buf.readableBytes())throw new IllegalArgumentException("asset_bad_chunk");byte[] bytes=new byte[n];buf.readBytes(bytes);return bytes;}
        public void encode(ByteBuf buf,byte[] bytes){if(bytes==null||bytes.length<1||bytes.length>AssetLimits.CHUNK_BYTES)throw new IllegalArgumentException("asset_bad_chunk");buf.writeInt(bytes.length);buf.writeBytes(bytes);}
    };
    public record UploadChunk(String token,int sequence,byte[] bytes)implements CustomPacketPayload{
        public static final Type<UploadChunk> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"asset_upload_chunk"));
        public static final StreamCodec<ByteBuf,UploadChunk> CODEC=StreamCodec.composite(ID,UploadChunk::token,ByteBufCodecs.INT,UploadChunk::sequence,CHUNK,UploadChunk::bytes,UploadChunk::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record DownloadChunk(String token,int sequence,byte[] bytes)implements CustomPacketPayload{
        public static final Type<DownloadChunk> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"asset_download_chunk"));
        public static final StreamCodec<ByteBuf,DownloadChunk> CODEC=StreamCodec.composite(ID,DownloadChunk::token,ByteBufCodecs.INT,DownloadChunk::sequence,CHUNK,DownloadChunk::bytes,DownloadChunk::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(PayloadRegistrar r){
        PayloadRegistrar ingress=r.executesOn(HandlerThread.NETWORK);
        ingress.playToServer(Begin.TYPE,Begin.CODEC,(p,c)->enqueue(c,actor->PanelAssets.begin(actor,p)));
        ingress.playToServer(FilmBegin.TYPE,FilmBegin.CODEC,(p,c)->enqueue(c,actor->PanelAssets.beginFilm(actor,p)));
        ingress.playToServer(Download.TYPE,Download.CODEC,(p,c)->enqueue(c,actor->PanelAssets.download(actor,p)));
        ingress.playToServer(UploadChunk.TYPE,UploadChunk.CODEC,(p,c)->enqueue(c,actor->PanelAssets.chunk(actor,p)));
        ingress.playToServer(Control.TYPE,Control.CODEC,(p,c)->enqueue(c,actor->PanelAssets.control(actor,p)));
        r.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.PanelAssetClient.receive(p)));
        r.playToClient(DownloadChunk.TYPE,DownloadChunk.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.PanelAssetClient.receive(p)));
    }
    private static void enqueue(IPayloadContext context,Consumer<ServerPlayer> action){
        if(!context.connection().isConnected())return;
        AssetIngress.Permit permit=INGRESS.acquire(context.connection(),System.nanoTime());
        if(permit==null){context.disconnect(Component.translatable("disconnect.exceeded_packet_rate"));return;}
        try{
            context.enqueueWork(()->{
                // All player, permission, tile and transfer access stays on the server thread.
                if(context.player() instanceof ServerPlayer actor&&actor.connection!=null&&actor.connection.getConnection()==context.connection())action.accept(actor);
            }).whenComplete((ignored,error)->permit.close());
        }catch(RuntimeException error){permit.close();throw error;}
    }
    private AssetNetwork(){}
}
