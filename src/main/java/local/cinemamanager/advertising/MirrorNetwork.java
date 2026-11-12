package local.cinemamanager.advertising;

import io.netty.buffer.ByteBuf;
import local.cinemamanager.CinemaManager;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.mirror.CinemaMirrors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;

/** A narrowly scoped explicit claim/revoke dialog. It does not change any media permissions. */
public final class MirrorNetwork {
    private record Pending(UUID token,BlockPos pos,UUID expected,boolean revoke,long until){}
    private static final Map<UUID,Pending> PENDING=new HashMap<>();
    public record Offer(BlockPos pos,String identity,String token,boolean revoke)implements CustomPacketPayload{
        public static final Type<Offer> TYPE=new Type<>(CinemaManager.id("mirror_offer"));
        public static final StreamCodec<ByteBuf,Offer> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Offer::pos,ByteBufCodecs.stringUtf8(36),Offer::identity,ByteBufCodecs.stringUtf8(36),Offer::token,ByteBufCodecs.BOOL,Offer::revoke,Offer::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Confirm(String token,boolean accept)implements CustomPacketPayload{
        public static final Type<Confirm> TYPE=new Type<>(CinemaManager.id("mirror_confirm"));
        public static final StreamCodec<ByteBuf,Confirm> CODEC=StreamCodec.composite(ByteBufCodecs.stringUtf8(36),Confirm::token,ByteBufCodecs.BOOL,Confirm::accept,Confirm::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private MirrorNetwork(){}
    public static void register(PayloadRegistrar registrar){
        registrar.playToClient(Offer.TYPE,Offer.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.MirrorConfirmation.open(p)));
        registrar.playToServer(Confirm.TYPE,Confirm.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer actor)confirm(actor,p);}));
    }
    static void offer(ServerPlayer actor,DisplayTile source,UUID expected,boolean revoke){
        if(expected==null||actor.distanceToSqr(source.getBlockPos().getCenter())>64)return;
        if(!(actor.getMainHandItem().getItem() instanceof DisplayLinkingCard)&&!(actor.getOffhandItem().getItem() instanceof DisplayLinkingCard))return;
        long now=actor.server.getTickCount();PENDING.entrySet().removeIf(e->e.getValue().until()<now);
        if(PENDING.size()>=128&&!PENDING.containsKey(actor.getUUID()))return;
        UUID token=UUID.randomUUID();PENDING.put(actor.getUUID(),new Pending(token,source.getBlockPos().immutable(),expected,revoke,now+1200));
        PacketDistributor.sendToPlayer(actor,new Offer(source.getBlockPos(),expected.toString(),token.toString(),revoke));
    }
    static void confirm(ServerPlayer actor,Confirm request){
        Pending pending=PENDING.get(actor.getUUID());
        if(pending==null||!pending.token().toString().equals(request.token()))return;
        PENDING.remove(actor.getUUID());if(!request.accept())return;
        if(pending.until()<actor.server.getTickCount()||!actor.level().hasChunkAt(pending.pos())
                ||!(actor.level().getBlockEntity(pending.pos()) instanceof DisplayTile source))return;
        if(!(actor.getMainHandItem().getItem() instanceof DisplayLinkingCard)&&!(actor.getOffhandItem().getItem() instanceof DisplayLinkingCard))return;
        var result=pending.revoke()?CinemaMirrors.revoke(source,actor,pending.expected()):CinemaMirrors.claim(source,actor,pending.expected());
        if(result.error()!=null){MirrorLinks.message(actor,"denied");return;}
        if(pending.revoke())MirrorLinks.message(actor,"revoked");else MirrorLinks.select(actor,source,false);
    }
}
