package local.cinemamanager.seats.map;

import io.netty.buffer.ByteBuf;
import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.panels.PanelScope;
import local.cinemamanager.seats.SeatRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Read-only request protocol. One immutable, bounded, expiring snapshot per viewer. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class SeatMapNetwork {
    public static final class Args { public String hall="",floorKey="",zoneKey="",snapshot=""; public int index; }
    /** Kept here as the client-facing DTO; SeatMapWire validates and serializes the identical shape. */
    public record Data(String status,boolean configured,String notice,PublicSeatMap.Page page){}
    public record Request(BlockPos source,String identity,String request,String action,String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(CinemaManager.id("seat_map_request"));
        public static final StreamCodec<ByteBuf,Request> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Request::source,ByteBufCodecs.stringUtf8(36),Request::identity,ByteBufCodecs.stringUtf8(36),Request::request,ByteBufCodecs.stringUtf8(8),Request::action,ByteBufCodecs.stringUtf8(512),Request::json,Request::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final StreamCodec<ByteBuf,String> JSON_CODEC=new StreamCodec<>(){
        private final StreamCodec<ByteBuf,String> strings=ByteBufCodecs.stringUtf8(SeatMapWire.MAX_JSON_BYTES);
        public String decode(ByteBuf buf){String raw=strings.decode(buf);if(SeatMapWire.bytes(raw)>SeatMapWire.MAX_JSON_BYTES)throw SeatMapGeometry.invalid("wire");return raw;}
        public void encode(ByteBuf buf,String raw){if(SeatMapWire.bytes(raw)>SeatMapWire.MAX_JSON_BYTES)throw SeatMapGeometry.invalid("wire");strings.encode(buf,raw);}
    };
    public record View(BlockPos source,String identity,String request,String json) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(CinemaManager.id("seat_map_view"));
        public static final StreamCodec<ByteBuf,View> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,View::source,ByteBufCodecs.stringUtf8(36),View::identity,ByteBufCodecs.stringUtf8(36),View::request,JSON_CODEC,View::json,View::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    record Authority(ManagerTile manager,String hall,String sourceStamp){}
    private record Session(Object server,ServerPlayer actor,Object connection,String dimension,BlockPos source,String identity,String request,UUID controller,String hall,String sourceStamp,String snapshot,long created,List<String> pages,int bytes){}
    private record Rate(Object server,Object connection,long tick,int count,long opened){}
    private static final Map<UUID,Session> SESSIONS=new LinkedHashMap<>();
    private static final Map<UUID,Rate> RATES=new LinkedHashMap<>();
    private static final int MAX_SESSIONS=16,MAX_MEMORY=8*1024*1024,TTL=1200;
    public static void register(RegisterPayloadHandlersEvent event){
        var registrar=event.registrar("mycinema-seat-map-1");
        registrar.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer actor)apply(actor,p);}));
        registrar.playToClient(View.TYPE,View.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.seats.SeatMapScreen.receive(p)));
    }
    public static boolean managerAllowed(ServerPlayer actor,ManagerTile manager){
        return manager!=null&&actor.level()==manager.getLevel()&&actor.level().hasChunkAt(manager.getBlockPos())
            &&actor.level().getBlockEntity(manager.getBlockPos())==manager&&manager.permits(actor,8);
    }
    static Authority authorize(ServerPlayer actor,Request packet,String requestedHall){
        if(!actor.isAlive()||actor.isSpectator()||!actor.level().hasChunkAt(packet.source())||actor.distanceToSqr(packet.source().getCenter())>64)throw SeatMapGeometry.invalid("permission");
        var tile=actor.level().getBlockEntity(packet.source());ManagerTile manager;String hall;String sourceStamp;
        if(tile instanceof ManagerTile m){if(!m.identity.toString().equals(packet.identity())||!managerAllowed(actor,m))throw SeatMapGeometry.invalid("permission");manager=m;hall=requestedHall;sourceStamp="manager:"+m.identity;}
        else if(tile instanceof InfoTile info){
            var block=info.getBlockState().getBlock();
            if((block!=CinemaManager.INFO.get()&&block!=CinemaManager.WALL_INFO.get())||info.isAdvertising()||info.scope!=PanelScope.HALL||!info.identity.toString().equals(packet.identity()))throw SeatMapGeometry.invalid("permission");
            var group=local.cinemamanager.panels.PanelGroup.of(info);var root=group.root();
            if(!info.isAudienceInformation()||!root.isAudienceInformation()||root.scope!=PanelScope.HALL
                ||!local.cinemamanager.panels.PanelNetwork.guestAllowed(actor,info))throw SeatMapGeometry.invalid("permission");
            manager=root.controller();hall=root.hallId;sourceStamp=group.signature();
            if(manager==null||manager.getLevel()!=actor.level())throw SeatMapGeometry.invalid("offline");
        }else throw SeatMapGeometry.invalid("permission");
        SeatRegistry.uuid(hall);
        if(manager.state.halls.stream().noneMatch(h->h.id.equals(hall)))throw SeatMapGeometry.invalid("hall_missing");
        return new Authority(manager,hall,sourceStamp);
    }
    private static boolean rate(ServerPlayer actor,long tick,boolean open){
        UUID player=actor.getUUID();Rate old=RATES.get(player);if(old!=null&&(old.server!=actor.getServer()||old.connection!=actor.connection))old=null;int count=old!=null&&old.tick==tick?old.count+1:1;
        long opened=old==null||tick<old.tick?Long.MIN_VALUE:old.opened;
        boolean allowed=count<=4&&(!open||opened==Long.MIN_VALUE||tick-opened>=20);
        if(open&&allowed)opened=tick;
        if(!RATES.containsKey(player)&&RATES.size()>=128)RATES.remove(RATES.keySet().iterator().next());
        RATES.put(player,new Rate(actor.getServer(),actor.connection,tick,count,opened));return allowed;
    }
    public static void apply(ServerPlayer actor,Request packet){
        long now=actor.getServer().getTickCount();UUID player=actor.getUUID();
        SESSIONS.entrySet().removeIf(e->e.getValue().server!=actor.getServer()||now<e.getValue().created||now-e.getValue().created>TTL);
        try{SeatRegistry.uuid(packet.request());SeatRegistry.uuid(packet.identity());}catch(RuntimeException bad){return;}
        if(!Set.of("open","page","cancel").contains(packet.action()))return;
        Args args;
        try{args=SeatRegistry.JSON.fromJson(packet.json(),Args.class);if(args==null||args.hall==null||args.floorKey==null||args.zoneKey==null||args.snapshot==null||args.index<0||args.index>=SeatMapWire.MAX_PAGES)throw SeatMapGeometry.invalid("wire");
            SeatMapGeometry.optionalKey(args.floorKey);SeatMapGeometry.optionalKey(args.zoneKey);SeatMapGeometry.optionalKey(args.snapshot);
        }catch(RuntimeException bad){return;}
        Session current=SESSIONS.get(player);
        if(packet.action().equals("cancel")){
            if(current!=null&&sameSource(actor,packet,current)&&current.request.equals(packet.request())&&(args.snapshot.isEmpty()||current.snapshot.equals(args.snapshot)))SESSIONS.remove(player);return;
        }
        if(!rate(actor,now,packet.action().equals("open"))){send(actor,packet,SeatMapWire.encode(new SeatMapWire.Data("error",false,"mycinema.seats.map.rate_limited",null)));return;}
        try{
            if(packet.action().equals("open")){
                // A requested refresh invalidates the previous server snapshot even if capture fails.
                SESSIONS.remove(player);Authority auth=authorize(actor,packet,args.hall);
                var authored=SeatMapStorage.get((ServerLevel)actor.level(),auth.manager.identity,auth.hall).document();
                var snapshot=SeatMapServer.capture(actor,auth.manager,auth.hall,authored);
                var projected=SeatMapServer.project(snapshot,auth.manager.identity,auth.hall,args.floorKey,args.zoneKey);
                List<String> pages=SeatMapWire.pages(projected,authored.isPresent());int bytes=pages.stream().mapToInt(SeatMapWire::bytes).sum();
                int memory=SESSIONS.values().stream().mapToInt(Session::bytes).sum();
                if(SESSIONS.size()>=MAX_SESSIONS||memory+bytes>MAX_MEMORY)throw SeatMapGeometry.invalid("busy");
                Session session=new Session(actor.getServer(),actor,actor.connection,actor.level().dimension().location().toString(),packet.source().immutable(),packet.identity(),packet.request(),auth.manager.identity,auth.hall,auth.sourceStamp,snapshot.snapshotId(),now,pages,bytes);
                SESSIONS.put(player,session);send(actor,packet,pages.getFirst());
            }else{
                if(current==null||!sameSource(actor,packet,current)||!current.request.equals(packet.request())||!current.snapshot.equals(args.snapshot))throw SeatMapGeometry.invalid("stale");
                Authority auth=authorize(actor,packet,current.hall);
                if(!auth.manager.identity.equals(current.controller)||!auth.hall.equals(current.hall)||!auth.sourceStamp.equals(current.sourceStamp)||args.index>=current.pages.size())throw SeatMapGeometry.invalid("stale");
                send(actor,packet,current.pages.get(args.index));
            }
        }catch(RuntimeException bad){
            if(current!=null&&current.request.equals(packet.request()))SESSIONS.remove(player);
            String notice=SeatMapWire.notice(bad,"invalid");
            send(actor,packet,SeatMapWire.encode(new SeatMapWire.Data("error",false,notice,null)));
        }
    }
    private static boolean sameSource(ServerPlayer actor,Request packet,Session session){return session.server==actor.getServer()&&session.actor==actor&&session.connection==actor.connection&&session.dimension.equals(actor.level().dimension().location().toString())&&session.source.equals(packet.source())&&session.identity.equals(packet.identity());}
    static boolean hasSession(ServerPlayer actor){Session session=SESSIONS.get(actor.getUUID());return session!=null&&session.actor==actor&&session.connection==actor.connection;}
    static String activeSnapshot(ServerPlayer actor){return hasSession(actor)?SESSIONS.get(actor.getUUID()).snapshot:"";}
    static void disconnect(ServerPlayer actor){
        Session session=SESSIONS.get(actor.getUUID());if(session!=null&&session.actor==actor&&session.connection==actor.connection)SESSIONS.remove(actor.getUUID());
        Rate rate=RATES.get(actor.getUUID());if(rate!=null&&rate.server==actor.getServer()&&rate.connection==actor.connection)RATES.remove(actor.getUUID());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer actor)disconnect(actor);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){SESSIONS.entrySet().removeIf(e->e.getValue().server==event.getServer());RATES.entrySet().removeIf(e->e.getValue().server==event.getServer());}
    private static void send(ServerPlayer actor,Request packet,String json){PacketDistributor.sendToPlayer(actor,new View(packet.source(),packet.identity(),packet.request(),json));}
    private SeatMapNetwork(){}
}
