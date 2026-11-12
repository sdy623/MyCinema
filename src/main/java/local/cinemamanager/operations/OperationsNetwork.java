package local.cinemamanager.operations;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;

/** Actor-specific operations responses; neither packets nor public wall data contain source credentials. */
public final class OperationsNetwork {
    public static final Gson JSON=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    public static final int MAX_RESPONSE_CHARS=196608;
    private static final Map<UUID,Long> VIEWS=new HashMap<>(),COMMANDS=new HashMap<>();
    private static final Map<String,Long> HANDLED=new LinkedHashMap<>();
    public static final class Arguments {
        public String controller="",signature="",hall="",show="";
        public long panelRevision,managerRevision;
        public boolean confirmed;
        public CinemaProgramme.Request programme;
    }
    public record Hall(String id,String name,String status,CinemaProgramme.View programme,String authority){
        public Hall{OperationsAccess.uuid(id);name=PublicOperationsSnapshot.label(name,"Hall");status=PublicOperationsSnapshot.code(status);
            if(authority==null||!authority.matches("mycinema\\.catalog\\.[a-z0-9_]{1,80}"))throw OperationsAccess.denied("invalid");}
    }
    public record Plan(String id,String hall,String title,long start,long end,String status,boolean loadable){
        public Plan{OperationsAccess.uuid(id);OperationsAccess.uuid(hall);title=PublicOperationsSnapshot.label(title,"Programme");status=PublicOperationsSnapshot.code(status);if(start<0||end<=start)throw OperationsAccess.denied("invalid");}
    }
    public record Data(String controller,long panelRevision,String signature,long managerRevision,boolean authorized,boolean controllable,
                       String cinema,String timezone,long serverTime,List<Hall> halls,List<Plan> plans,String notice) {
        public Data {
            if(halls==null||plans==null||halls.size()>ManagerState.MAX_HALLS||plans.size()>ManagerState.MAX_SHOWS
                    ||panelRevision<0||managerRevision<0||serverTime<0||controller==null||signature==null||notice==null
                    ||controller.length()>36||signature.length()>36||notice.length()>128)throw OperationsAccess.denied("invalid");
            if(authorized){OperationsAccess.uuid(controller);OperationsAccess.uuid(signature);}
            if(!authorized&&(!halls.isEmpty()||!plans.isEmpty()||controllable))throw OperationsAccess.denied("invalid");
            cinema=PublicOperationsSnapshot.label(cinema,"");timezone=PublicOperationsSnapshot.zone(timezone);halls=List.copyOf(halls);plans=List.copyOf(plans);
        }
    }
    public record Request(BlockPos pos,String identity,String action,String request,String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(CinemaManager.id("operations_request"));
        public static final StreamCodec<ByteBuf,Request> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Request::pos,ByteBufCodecs.stringUtf8(36),Request::identity,ByteBufCodecs.stringUtf8(16),Request::action,ByteBufCodecs.stringUtf8(36),Request::request,ByteBufCodecs.stringUtf8(8192),Request::json,Request::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record View(BlockPos pos,String identity,String request,boolean open,String json) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(CinemaManager.id("operations_view"));
        public static final StreamCodec<ByteBuf,View> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,View::pos,ByteBufCodecs.stringUtf8(36),View::identity,ByteBufCodecs.stringUtf8(36),View::request,ByteBufCodecs.BOOL,View::open,ByteBufCodecs.stringUtf8(MAX_RESPONSE_CHARS),View::json,View::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(PayloadRegistrar registrar){
        registrar.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer actor)apply(actor,p);}));
        registrar.playToClient(View.TYPE,View.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.OperationsScreen.open(p)));
    }
    private static String reason(RuntimeException failure){String key=failure.getMessage();return key!=null&&key.matches("mycinema\\.(operations|catalog|schedule|show)\\.[a-z0-9_]{1,80}")?key:"mycinema.operations.unavailable";}
    private static Data denied(String notice){return new Data("",0,"",0,false,false,"","",System.currentTimeMillis(),List.of(),List.of(),notice);}
    public static String encode(Data data){String json=JSON.toJson(data);return json.length()<=MAX_RESPONSE_CHARS?json:JSON.toJson(denied("mycinema.operations.busy"));}
    public static Data data(ServerPlayer actor,InfoTile tile,String notice){
        try{
            ManagerTile manager=OperationsAccess.requireView(actor,tile);InfoTile root=PanelGroup.of(tile).root();long now=System.currentTimeMillis();
            List<Hall> halls=new ArrayList<>();
            for(var hall:manager.state.halls)if(PublicOperationsSnapshot.includes(root,hall)){
                var programme=PublicOperationsSnapshot.programme(manager,hall);
                halls.add(new Hall(hall.id,PublicOperationsSnapshot.label(hall.name,"Hall"),PublicOperationsSnapshot.status(manager,hall,programme),programme,local.cinemamanager.library.HallAuthorization.programmeStatus(manager,hall.id)));
            }
            List<Plan> plans=PublicOperationsSnapshot.plans(root,manager,now).stream().map(s->new Plan(s.id,s.hallId,PublicOperationsSnapshot.label(s.title,"Programme"),s.start,s.end,s.status.name(),s.status==ManagerState.Status.PENDING)).toList();
            boolean control=OperationsAccess.controlReach(actor,manager);
            return new Data(manager.identity.toString(),root.panelRevision,PanelGroup.of(tile).signature(),manager.state.revision,true,control,
                    PublicOperationsSnapshot.label(manager.state.name,"MyCinema"),PublicOperationsSnapshot.zone(manager.state.timezone),now,halls,plans,
                    notice.isBlank()?halls.isEmpty()?"mycinema.operations.empty":control?"mycinema.operations.owner_controls":"mycinema.operations.manager_range":notice);
        }catch(RuntimeException failure){return denied(reason(failure));}
    }
    public static void open(ServerPlayer actor,InfoTile tile){
        if(!PanelNetwork.guestAllowed(actor,tile))return;
        PacketDistributor.sendToPlayer(actor,new View(tile.getBlockPos(),tile.identity.toString(),"",true,encode(data(actor,tile,""))));
    }
    public static void apply(ServerPlayer actor,Request packet){
        if(!PanelNetwork.acceptAt(packet.action().equals("view")?VIEWS:COMMANDS,actor.getUUID(),actor.getServer().getTickCount(),2))return;
        View reply=execute(actor,packet);if(reply!=null)PacketDistributor.sendToPlayer(actor,reply);
    }
    /** Same server adapter used by packet handling and loaded-world tests; returns an exact request ACK. */
    public static View execute(ServerPlayer actor,Request packet){
        if(actor==null||packet==null||packet.pos()==null||packet.action()==null||packet.json()==null||packet.json().length()>8192)return null;
        try{UUID.fromString(packet.request());UUID.fromString(packet.identity());}catch(RuntimeException invalid){return null;}
        if(!actor.level().hasChunkAt(packet.pos())||!(actor.level().getBlockEntity(packet.pos()) instanceof InfoTile tile)
                ||!tile.identity.toString().equals(packet.identity())||!PanelNetwork.guestAllowed(actor,tile))
            return new View(packet.pos(),packet.identity(),packet.request(),false,encode(denied("mycinema.operations.unavailable")));
        String notice="";
        try{
            if(!Set.of("view","control","load_show").contains(packet.action()))throw OperationsAccess.denied("invalid");
            OperationsAccess.requireView(actor,tile);
            if(!packet.action().equals("view")){
                Arguments args=JSON.fromJson(packet.json(),Arguments.class);
                ManagerTile manager=OperationsAccess.requireControl(actor,tile,args);
                String key=actor.getUUID()+":"+packet.request();long now=actor.getServer().getTickCount();
                HANDLED.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>1200);
                if(HANDLED.containsKey(key))throw OperationsAccess.denied("duplicate");
                if(HANDLED.size()>=1024)throw OperationsAccess.denied("busy");
                HANDLED.put(key,now);
                if(packet.action().equals("control")){
                    if(args.programme==null)throw OperationsAccess.denied("invalid");
                    // This is the existing manager service, with its original actor, exact run/revision/index and hall gates.
                    manager.playback.control(actor,args.hall,args.programme);manager.notice("");notice="mycinema.operations.accepted";
                }else{
                    if(!args.confirmed)throw OperationsAccess.denied("confirm_required");
                    manager.state.requireRevision(args.managerRevision);
                    var show=manager.state.show(args.show);
                    if(!Objects.equals(show.hallId,args.hall)||show.status!=ManagerState.Status.PENDING
                            ||!PublicOperationsSnapshot.today(show,manager.state.timezone,System.currentTimeMillis()))throw OperationsAccess.denied("stale_show");
                    manager.loadShow(actor,show.id,args.managerRevision);notice="mycinema.operations.show_loaded";
                }
                tile.refreshNow();
            }
        }catch(RuntimeException failure){notice=reason(failure);}
        return new View(packet.pos(),packet.identity(),packet.request(),false,encode(data(actor,tile,notice)));
    }
    private OperationsNetwork(){}
}
