package local.cinemamanager.panels;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import local.cinemamanager.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

public final class PanelNetwork {
    public static final Gson JSON=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    public static final int MAX_GUEST_JSON=262144;
    private static final Map<UUID,Long> REQUESTS=new HashMap<>(),GUEST_OPENS=new HashMap<>(),GUEST_REFRESHES=new HashMap<>();
    public record Edit(BlockPos pos,String identity,String signature,String action,String json,String request) implements CustomPacketPayload {
        public static final Type<Edit> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"panel_edit"));
        public static final StreamCodec<ByteBuf,Edit> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Edit::pos,ByteBufCodecs.stringUtf8(36),Edit::identity,ByteBufCodecs.stringUtf8(36),Edit::signature,ByteBufCodecs.stringUtf8(8),Edit::action,ByteBufCodecs.stringUtf8(32768),Edit::json,ByteBufCodecs.stringUtf8(36),Edit::request,Edit::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record View(BlockPos pos,String identity,String signature,String json,String request,boolean open) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"panel_view"));
        public static final StreamCodec<ByteBuf,View> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,View::pos,ByteBufCodecs.stringUtf8(36),View::identity,ByteBufCodecs.stringUtf8(36),View::signature,ByteBufCodecs.stringUtf8(65536),View::json,ByteBufCodecs.stringUtf8(36),View::request,ByteBufCodecs.BOOL,View::open,View::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record GuestRefresh(BlockPos pos,String identity) implements CustomPacketPayload {
        public static final Type<GuestRefresh> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"panel_guest_refresh"));
        public static final StreamCodec<ByteBuf,GuestRefresh> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,GuestRefresh::pos,ByteBufCodecs.stringUtf8(36),GuestRefresh::identity,GuestRefresh::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record GuestView(BlockPos pos,String identity,String json,boolean open) implements CustomPacketPayload {
        public static final Type<GuestView> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"panel_guest_view"));
        public static final StreamCodec<ByteBuf,GuestView> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,GuestView::pos,ByteBufCodecs.stringUtf8(36),GuestView::identity,ByteBufCodecs.stringUtf8(MAX_GUEST_JSON),GuestView::json,ByteBufCodecs.BOOL,GuestView::open,GuestView::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Configuration(PanelSettings settings,PanelScope scope,local.cinemamanager.operations.TerminalRoleSettings terminalRole){public Configuration(PanelSettings settings,PanelScope scope){this(settings,scope,null);}public Configuration{if(settings==null||scope==null)throw new IllegalArgumentException("mycinema.panel.invalid");}}
    public enum BindingState { UNKNOWN, MANUAL, UNBOUND, CONTROLLER_UNAVAILABLE, HALL_MISSING, HALL_BOUND, LOBBY_BOUND }
    public enum ScheduleState { UNKNOWN, MANUAL, UNAVAILABLE, INVALID_TIMEZONE, EMPTY, AVAILABLE }
    /** Owner-only status. Never includes a manager address, private source, or film identifier. */
    public record BindingData(BindingState state,String hallName,ScheduleState schedule){
        public static final BindingData UNKNOWN=new BindingData(BindingState.UNKNOWN,"",ScheduleState.UNKNOWN);
        public BindingData{state=state==null?BindingState.UNKNOWN:state;hallName=hallName==null?"":hallName;schedule=schedule==null?ScheduleState.UNKNOWN:schedule;}
    }
    public record Data(PanelSettings settings,List<String> fields,int height,boolean editable,String notice,PanelScope scope,int width,local.cinemamanager.advertising.AdvertisingGeometry advertising,String surface,long imageBudgetBytes,long worldBudgetBytes,local.cinemamanager.advertising.ScreenMode screenMode,boolean imageShape,String assemblyToken,int assemblyUnits,BindingData binding,local.cinemamanager.operations.TerminalRoleSettings terminalRole,boolean invalidRole){
        public Data{binding=binding==null?BindingData.UNKNOWN:binding;terminalRole=terminalRole==null?local.cinemamanager.operations.TerminalRoleSettings.LEGACY:terminalRole;}
        public Data(PanelSettings settings,List<String> fields,int height,boolean editable,String notice,PanelScope scope,int width,local.cinemamanager.advertising.AdvertisingGeometry advertising,String surface,long imageBudgetBytes,long worldBudgetBytes,local.cinemamanager.advertising.ScreenMode screenMode,boolean imageShape,String assemblyToken,int assemblyUnits,BindingData binding){this(settings,fields,height,editable,notice,scope,width,advertising,surface,imageBudgetBytes,worldBudgetBytes,screenMode,imageShape,assemblyToken,assemblyUnits,binding,local.cinemamanager.operations.TerminalRoleSettings.LEGACY,false);}
        /** Retain both historical constructors and absent-field Gson snapshots. */
        public Data(PanelSettings settings,List<String> fields,int height,boolean editable,String notice,PanelScope scope,int width,local.cinemamanager.advertising.AdvertisingGeometry advertising,String surface,long imageBudgetBytes,long worldBudgetBytes,local.cinemamanager.advertising.ScreenMode screenMode,boolean imageShape,String assemblyToken,int assemblyUnits){this(settings,fields,height,editable,notice,scope,width,advertising,surface,imageBudgetBytes,worldBudgetBytes,screenMode,imageShape,assemblyToken,assemblyUnits,BindingData.UNKNOWN);}
        public Data(PanelSettings settings,List<String> fields,int height,boolean editable,String notice,PanelScope scope){this(settings,fields,height,editable,notice,scope,1,null,"",64L*1024*1024,512L*1024*1024,local.cinemamanager.advertising.ScreenMode.PICTURE,false,"",0);}
    }
    public record GuestData(PanelSettings settings,PanelScope scope,PublicPanelSnapshot snapshot,int height,int width){
        public GuestData(PanelSettings settings,PanelScope scope,PublicPanelSnapshot snapshot,int height){this(settings,scope,snapshot,height,1);}
        public GuestData{if(settings==null||scope==null||snapshot==null||height<1||height>PanelGroup.MAX_HEIGHT||width<1||width>PanelGroup.MAX_WIDTH)throw new IllegalArgumentException("mycinema.panel.invalid");}
    }
    public static void register(PayloadRegistrar r){
        local.cinemamanager.operations.OperationsNetwork.register(r);
        r.playToServer(Edit.TYPE,Edit.CODEC,PanelNetwork::handle);
        r.playToClient(View.TYPE,View.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.PanelEditor.open(p)));
        r.playToServer(GuestRefresh.TYPE,GuestRefresh.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer actor)refreshGuest(actor,p);}));
        r.playToClient(GuestView.TYPE,GuestView.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.GuestPanelScreen.open(p)));
    }
    public static boolean acceptAt(Map<UUID,Long> requests,UUID actor,long now,int interval){
        if(interval<1)throw new IllegalArgumentException();long last=requests.getOrDefault(actor,Long.MIN_VALUE);
        if(last!=Long.MIN_VALUE&&now>=last&&now-last<interval)return false;
        requests.put(actor,now);if(requests.size()>128)requests.entrySet().removeIf(e->now-e.getValue()>400||now<e.getValue());return true;
    }
    private static boolean accept(ServerPlayer actor,Map<UUID,Long> requests,int interval){
        return acceptAt(requests,actor.getUUID(),actor.getServer().getTickCount(),interval);
    }
    public static boolean guestAllowed(ServerPlayer actor,InfoTile tile){
        return actor!=null&&tile.getLevel()==actor.level()&&!tile.isRemoved()&&actor.level().hasChunkAt(tile.getBlockPos())
                &&actor.level().getBlockEntity(tile.getBlockPos())==tile&&tile.panelDistanceSquared(actor)<=64
                &&actor.level().mayInteract(actor,tile.getBlockPos());
    }
    public static GuestData guestData(InfoTile tile){
        return tile.publicGuestData();
    }
    public static void openGuest(ServerPlayer actor,InfoTile tile,boolean open){
        if(!guestAllowed(actor,tile)||!accept(actor,open?GUEST_OPENS:GUEST_REFRESHES,open?2:20))return;
        if(tile.isOperations()||tile.invalidTerminalRole()){
            if(open)local.cinemamanager.operations.OperationsNetwork.open(actor,tile);
            else PacketDistributor.sendToPlayer(actor,new GuestView(tile.getBlockPos(),tile.identity.toString(),JSON.toJson(guestData(tile)),false));
            return;
        }
        PacketDistributor.sendToPlayer(actor,new GuestView(tile.getBlockPos(),tile.identity.toString(),JSON.toJson(guestData(tile)),open));
    }
    public static void refreshGuest(ServerPlayer actor,GuestRefresh packet){
        if(!actor.level().hasChunkAt(packet.pos())||!(actor.level().getBlockEntity(packet.pos()) instanceof InfoTile tile)||!tile.identity.toString().equals(packet.identity()))return;
        openGuest(actor,tile,false);
    }
    public static void open(ServerPlayer actor,InfoTile tile,boolean edit){
        if(!edit||!tile.permits(actor,8)){openGuest(actor,tile,true);return;}
        send(actor,tile,true,"","",true);
    }
    /** Read the actual saved binding without rebinding, changing a programme, or refreshing its snapshot. */
    public static BindingData bindingData(InfoTile root){
        if(root.scope==PanelScope.MANUAL)return new BindingData(BindingState.MANUAL,"",ScheduleState.MANUAL);
        if(root.manager==null)return new BindingData(BindingState.UNBOUND,"",ScheduleState.UNAVAILABLE);
        ManagerTile controller=root.controller();
        if(controller==null)return new BindingData(BindingState.CONTROLLER_UNAVAILABLE,"",ScheduleState.UNAVAILABLE);
        BindingState binding=BindingState.LOBBY_BOUND;String hallName="";
        if(root.scope==PanelScope.HALL){
            if(root.hallId==null||root.hallId.isBlank())return new BindingData(BindingState.UNBOUND,"",ScheduleState.UNAVAILABLE);
            var hall=controller.state.halls.stream().filter(h->h!=null&&root.hallId.equals(h.id)).findFirst();
            if(hall.isEmpty())return new BindingData(BindingState.HALL_MISSING,"",ScheduleState.UNAVAILABLE);
            binding=BindingState.HALL_BOUND;hallName=hall.get().name;
        }
        ScheduleState schedule;
        try{if(root.scope==PanelScope.HALL&&!root.projectNow().rows().isEmpty())schedule=ScheduleState.AVAILABLE;
            else{java.time.ZoneId.of(controller.state.timezone);schedule=PublicSchedule.project(controller.state,root.scope,root.hallId,System.currentTimeMillis(),show->0).rows().isEmpty()?ScheduleState.EMPTY:ScheduleState.AVAILABLE;}}
        catch(RuntimeException invalid){schedule=ScheduleState.INVALID_TIMEZONE;}
        return new BindingData(binding,hallName,schedule);
    }
    private static void send(ServerPlayer actor,InfoTile tile,boolean edit,String notice,String request,boolean open){
        PanelGroup group=PanelGroup.of(tile);InfoTile root=group.root();
        // Denied edits get a harmless response so the editor can end its pending state.
        var assembly=edit&&root.isAdvertising()?local.cinemamanager.advertising.AdvertisingAssembly.inspect(root):null;
        Data data=new Data(edit?root.panel:root.publicSettings(),edit?root.diagnostics():List.of(),group.height(),edit,notice,root.scope,group.width(),root.isAdvertising()?root.advertising:null,root.getBlockState().getBlock() instanceof local.cinemamanager.advertising.AdvertisingBlock ad?(ad.emissive()?"led":"printed"):"",local.cinemamanager.panels.assets.AssetLimits.imageBytes(),local.cinemamanager.panels.assets.AssetLimits.worldBytes(),root.screenMode,!root.advertisingShape.rectangular(),assembly==null?"":assembly.signature(),assembly==null?0:assembly.units().size(),edit?bindingData(root):BindingData.UNKNOWN,root.terminalRole,root.invalidTerminalRole());
        PacketDistributor.sendToPlayer(actor,new View(tile.getBlockPos(),tile.identity.toString(),group.signature(),JSON.toJson(data),request,open));
    }
    public static void handle(Edit packet,IPayloadContext context){context.enqueueWork(()->{if(context.player() instanceof ServerPlayer actor)apply(actor,packet);});}
    /** Production path, also exercised by dedicated tests with an explicit test actor. */
    public static void apply(ServerPlayer actor,Edit packet){
        try{UUID.fromString(packet.request());}catch(RuntimeException e){return;}
        if(!accept(actor,REQUESTS,2))return;
        if(!actor.level().hasChunkAt(packet.pos())||!(actor.level().getBlockEntity(packet.pos()) instanceof InfoTile tile)||!tile.identity.toString().equals(packet.identity()))return;
        if(!tile.permits(actor,8)){send(actor,tile,false,"mycinema.panel.permission",packet.request(),false);return;}
        String notice="";
        try{switch(packet.action()){
            case "save"->{Configuration configuration=JSON.fromJson(packet.json(),Configuration.class);if(configuration==null)throw new IllegalArgumentException("mycinema.panel.invalid");tile.configure(actor,packet.signature(),configuration.settings(),configuration.scope(),configuration.terminalRole()==null?tile.terminalRole:configuration.terminalRole());notice="mycinema.panel.saved";}
            case "ad_size"->{var g=JSON.fromJson(packet.json(),local.cinemamanager.advertising.AdvertisingGeometry.class);local.cinemamanager.advertising.AdvertisingLifecycle.resize(tile,actor,packet.signature(),g);notice="mycinema.panel.saved";}
            case "ad_merge"->{local.cinemamanager.advertising.AdvertisingAssembly.assemble(tile,actor,packet.json());notice="mycinema.panel.saved";}
            case "ad_shape"->{if(!packet.json().equals("true")&&!packet.json().equals("false"))throw new IllegalArgumentException("mycinema.panel.invalid");if(tile.screenMode!=local.cinemamanager.advertising.ScreenMode.PICTURE)throw new IllegalArgumentException("mycinema.ad.picture_first");local.cinemamanager.advertising.AdvertisingLifecycle.applyImageShape(tile,actor,packet.signature(),Boolean.parseBoolean(packet.json()));notice="mycinema.panel.saved";}
            case "ad_mode"->{tile.screenMode(actor,packet.signature(),local.cinemamanager.advertising.ScreenMode.valueOf(packet.json()));notice="mycinema.panel.saved";}
            case "reload"->{}
            default->throw new IllegalArgumentException("mycinema.panel.invalid");
        }}catch(RuntimeException e){notice=e.getMessage()!=null&&(e.getMessage().startsWith("mycinema.panel.")||e.getMessage().startsWith("mycinema.ad."))?e.getMessage():"mycinema.panel.invalid";}
        send(actor,tile,true,notice,packet.request(),false);
    }
}
