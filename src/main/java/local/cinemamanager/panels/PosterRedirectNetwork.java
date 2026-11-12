package local.cinemamanager.panels;

import io.netty.buffer.ByteBuf;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoTile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Public viewers can ask about one currently published poster, never about arbitrary authors. */
public final class PosterRedirectNetwork {
    private static final Map<UUID,long[]> WINDOWS=new HashMap<>();
    public record Request(BlockPos pos,String identity,String source,String target,String request) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(CinemaManager.id("poster_target"));
        public static final StreamCodec<ByteBuf,Request> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Request::pos,
                ByteBufCodecs.stringUtf8(36),Request::identity,ByteBufCodecs.stringUtf8(2048),Request::source,
                ByteBufCodecs.stringUtf8(2048),Request::target,ByteBufCodecs.stringUtf8(36),Request::request,Request::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Reply(String request,boolean allowed) implements CustomPacketPayload {
        public static final Type<Reply> TYPE=new Type<>(CinemaManager.id("poster_target_reply"));
        public static final StreamCodec<ByteBuf,Reply> CODEC=StreamCodec.composite(ByteBufCodecs.stringUtf8(36),Reply::request,ByteBufCodecs.BOOL,Reply::allowed,Reply::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(PayloadRegistrar r){
        r.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer actor)
            PacketDistributor.sendToPlayer(actor,new Reply(p.request(),allow(actor,p)));}));
        r.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.PosterRedirectClient.receive(p)));
    }
    /** 24 requests per second permits four five-hop posters; excess receives a safe denial. */
    private static boolean rate(ServerPlayer actor){
        long now=actor.getServer().getTickCount();long[] window=WINDOWS.computeIfAbsent(actor.getUUID(),id->new long[]{now,0});
        if(now<window[0]||now-window[0]>=20){window[0]=now;window[1]=0;}
        if(WINDOWS.size()>128)WINDOWS.entrySet().removeIf(e->now-e.getValue()[0]>400||now<e.getValue()[0]);
        return ++window[1]<=24;
    }
    public static boolean allow(ServerPlayer actor,Request request){
        if(actor==null||actor.getServer()==null||!actor.getServer().isSameThread())return false;
        try{UUID.fromString(request.request());UUID.fromString(request.identity());}catch(RuntimeException e){return false;}
        if(!rate(actor)||!actor.isAlive()||!actor.level().hasChunkAt(request.pos()))return false;
        if(!(actor.level().getBlockEntity(request.pos()) instanceof InfoTile tile)||tile.isRemoved()
                ||!tile.identity.toString().equals(request.identity())||actor.distanceToSqr(request.pos().getCenter())>64*64
                ||!actor.level().mayInteract(actor,request.pos()))return false;
        return tile.permitsPosterTarget(request.source(),request.target());
    }
    private PosterRedirectNetwork(){}
}
