package local.cinemamanager;
import io.netty.buffer.ByteBuf;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.theater.CinemaProgrammeNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ManagerNetwork {
    private static final java.util.Map<java.util.UUID,Long> LAST_REQUEST=new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID,Long> LAST_REFRESH=new java.util.HashMap<>();
    private static boolean accept(ServerPlayer player) { return accept(player,false); }
    private static boolean accept(ServerPlayer player,boolean refresh) {
        var requests=refresh?LAST_REFRESH:LAST_REQUEST;
        long now=player.getServer().getTickCount(),last=requests.getOrDefault(player.getUUID(),Long.MIN_VALUE);
        if(last!=Long.MIN_VALUE&&now>=last&&now-last<2)return false;
        requests.put(player.getUUID(),now);
        if(requests.size()>128)requests.entrySet().removeIf(e->now-e.getValue()>400);
        return true;
    }
    public record Request(BlockPos source,String identity,String action,CompoundTag args) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"request"));
        public static final StreamCodec<ByteBuf,Request> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Request::source,ByteBufCodecs.stringUtf8(36),Request::identity,ByteBufCodecs.stringUtf8(24),Request::action,ByteBufCodecs.COMPOUND_TAG,Request::args,Request::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(BlockPos source,String identity,boolean readOnly,boolean remote,boolean open,String json) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"snapshot"));
        public static final StreamCodec<ByteBuf,Snapshot> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Snapshot::source,ByteBufCodecs.stringUtf8(36),Snapshot::identity,ByteBufCodecs.BOOL,Snapshot::readOnly,ByteBufCodecs.BOOL,Snapshot::remote,ByteBufCodecs.BOOL,Snapshot::open,ByteBufCodecs.stringUtf8(512000),Snapshot::json,Snapshot::new);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static String publicJson(ManagerTile tile,ServerPlayer actor,boolean readOnly){return publicJson(tile,actor,readOnly,"");}
    private static String publicJson(ManagerTile tile,ServerPlayer actor,boolean readOnly,String seekAck){tile.state.serverTime=System.currentTimeMillis();var copy=StateCodec.decode(!readOnly && tile.controlAllowed(actor) ? StateCodec.encode(tile.state) : StateCodec.publicView(tile.state));tile.describeSeek(actor,copy,readOnly);tile.describeEmergency(actor,copy,readOnly);copy.seekAcknowledgement=seekAck;return StateCodec.encode(copy);}
    private static long revision(CompoundTag args){if(!args.contains("revision",net.minecraft.nbt.Tag.TAG_LONG))throw new IllegalArgumentException("mycinema.schedule.stale");return args.getLong("revision");}
    public static void register(RegisterPayloadHandlersEvent e){var r=e.registrar("1");local.cinemamanager.access.AccessNetwork.register(r);local.cinemamanager.advertising.MirrorNetwork.register(r);local.cinemamanager.library.FilmNetwork.register(r);local.cinemamanager.panels.PanelNetwork.register(r);local.cinemamanager.panels.PosterRedirectNetwork.register(r);local.cinemamanager.panels.assets.AssetNetwork.register(r);local.cinemamanager.network.TheaterPacket.register(r);local.cinemamanager.theater.TerminalNetwork.register(r);r.playToServer(Request.TYPE,Request.CODEC,ManagerNetwork::handle);r.playToClient(Snapshot.TYPE,Snapshot.CODEC,(p,c)->local.cinemamanager.client.ManagerClient.snapshot(p));}
    public static void open(ServerPlayer p,ManagerTile t,boolean readOnly){if(!accept(p))return;PacketDistributor.sendToPlayer(p,new Snapshot(t.getBlockPos(),t.identity.toString(),readOnly,false,true,publicJson(t,p,readOnly)));}
    public static void openRemote(ServerPlayer p,ManagerTile t){if(!accept(p))return;PacketDistributor.sendToPlayer(p,new Snapshot(t.getBlockPos(),t.identity.toString(),false,true,true,publicJson(t,p,false)));}
    public static void handle(Request p,IPayloadContext c){
        if(!(c.player() instanceof ServerPlayer actor)||!actor.level().hasChunkAt(p.source)||!accept(actor,p.action.equals("refresh")))return;
        var target=actor.level().getBlockEntity(p.source);
        if(target instanceof InfoTile)return; // Guest panels have a separate, allowlisted protocol.
        if(!(target instanceof ManagerTile t)||!t.identity.toString().equals(p.identity)||!(t.permits(actor,8)||(t.permits(actor,Double.POSITIVE_INFINITY)&&TabletItem.boundTo(actor,t)))||(!p.action.equals("refresh")&&!t.acceptRequest(actor)))return;
        try {
            String hall=p.args.getString("hall");
            switch(p.action){
                case "refresh" -> { }
                case "emergency_unlock", "emergency_restore" -> {
                    if(!p.args.contains("emergency_revision",net.minecraft.nbt.Tag.TAG_LONG))throw new IllegalArgumentException("mycinema.emergency.stale");
                    t.emergencyAccess(actor,p.action.equals("emergency_unlock"),p.args.getLong("emergency_revision"),p.args.getBoolean("confirmed"));
                }
                case "films" -> {local.cinemamanager.library.FilmNetwork.open(actor,t,hall,p.args.getString("show"));return;}
                case "seek" -> t.seek(actor,hall,p.args.getString("ticket"),p.args.getLong("target"));
                case "programme_control" -> {
                    t.playback.control(actor,hall,CinemaProgrammeNbt.readRequest(p.args.getCompound("programme_request")));
                    t.notice(""); // A successful explicit request retires an earlier failure notice.
                }
                case "nearest" -> t.nearest(actor);
                case "scene" -> t.scene(actor,hall,ManagerState.Scene.valueOf(p.args.getString("scene")));
                case "name" -> {String v=p.args.getString("value").trim();if(v.isEmpty()||v.length()>60)throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m054"));if(hall.equals("*"))t.state.name=v;else t.state.hall(hall).name=v;t.notice(local.cinemamanager.MessageText.key("message.m055"));}
                case "preset" -> t.lighting(actor,hall,ManagerState.LightPreset.valueOf(p.args.getString("preset")));
                case "save_preset" -> t.saveLighting(hall,ManagerState.LightPreset.valueOf(p.args.getString("preset")),p.args);
                case "media" -> t.media(actor,t.state.hall(hall),p.args.getString("url"),p.args.getString("title"));
                case "timezone" -> {t.state.setTimezone(p.args.getString("value"));t.notice(local.cinemamanager.MessageText.key("message.m056")+t.state.timezone);}
                case "scheduler" -> t.enableScheduler(actor,p.args.getBoolean("enabled"));
                case "schedule" -> { t.state.requireRevision(revision(p.args)); t.schedule(actor,t.state.hall(hall),p.args); }
                case "load_show" -> t.loadShow(actor,p.args.getString("show"),revision(p.args));
                case "arm_show" -> t.armShow(actor,p.args.getString("show"),revision(p.args),p.args.getBoolean("confirmed"));
                case "edit_show" -> t.editShow(actor,p.args.getString("show"),revision(p.args),p.args);
                case "delete_show" -> t.deleteShow(actor,p.args.getString("show"),revision(p.args),p.args.getBoolean("confirmed"));
                case "cancel" -> t.cancelShow(actor,p.args.getString("show"),revision(p.args),p.args.getBoolean("confirmed"));
                case "prune" -> {t.state.requireRevision(revision(p.args));if(!p.args.getBoolean("confirmed"))throw new IllegalArgumentException("mycinema.schedule.confirm_required");t.state.shows.removeIf(s->s.status!=ManagerState.Status.PENDING&&s.status!=ManagerState.Status.PREPARING&&s.status!=ManagerState.Status.RUNNING&&s.status!=ManagerState.Status.STOP_PENDING);t.notice(local.cinemamanager.MessageText.key("message.m057"));}
                case "remove" -> {var h=t.state.hall(hall);if(t.state.shows.stream().anyMatch(s->s.hallId.equals(hall)&&(s.status==ManagerState.Status.PREPARING||s.status==ManagerState.Status.RUNNING||s.status==ManagerState.Status.STOP_PENDING)))throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m058"));t.removeHall(hall);}
                default -> throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m059"));
            }
        }catch(RuntimeException e){t.notice(local.cinemamanager.MessageText.key("message.m060")+(e.getMessage()==null?local.cinemamanager.MessageText.key("message.m061"):(e.getMessage().startsWith("mycinema.")?"{tr:"+e.getMessage()+"}":e.getMessage())));}
        PacketDistributor.sendToPlayer(actor,new Snapshot(p.source,t.identity.toString(),false,TabletItem.boundTo(actor,t),false,publicJson(t,actor,false,(p.action.equals("seek")||p.action.equals("programme_control")||p.action.equals("emergency_unlock")||p.action.equals("emergency_restore"))&&p.args.getString("ticket").length()==36?p.args.getString("ticket"):"")));
    }
}
