package local.cinemamanager.library;

import io.netty.buffer.ByteBuf;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.catalog.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Stop-and-wait bounded exchange. Every packet is bound to the actor, current level, tile and live permission. */
public final class CatalogExchangeNetwork {
    public static final int CHUNK_CHARS = 6000;
    private static final int MAX_SESSIONS = 8;
    private static final long LIFETIME = TimeUnit.MINUTES.toNanos(3);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    // One full 1 MiB upload may need 175 chunks plus review handshakes within a second on localhost.
    private static final ExchangeIngress INGRESS = new ExchangeIngress(2, 16, 256, TimeUnit.SECONDS.toNanos(1));
    private static final StreamCodec<ByteBuf,String> ID = ByteBufCodecs.stringUtf8(36), TEXT = ByteBufCodecs.stringUtf8(CHUNK_CHARS);
    public record Request(BlockPos source, String identity, String request, String action, int sequence, long revision, String body) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(CinemaManager.id("catalog_exchange_request"));
        public static final StreamCodec<ByteBuf,Request> CODEC = new StreamCodec<>() {
            public Request decode(ByteBuf in) { return new Request(BlockPos.STREAM_CODEC.decode(in), ID.decode(in), ID.decode(in), ByteBufCodecs.stringUtf8(16).decode(in), in.readInt(), in.readLong(), TEXT.decode(in)); }
            public void encode(ByteBuf out, Request p) { BlockPos.STREAM_CODEC.encode(out,p.source()); ID.encode(out,p.identity()); ID.encode(out,p.request()); ByteBufCodecs.stringUtf8(16).encode(out,p.action()); out.writeInt(p.sequence()); out.writeLong(p.revision()); TEXT.encode(out,p.body()); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Reply(String request, String state, int sequence, String body) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(CinemaManager.id("catalog_exchange_reply"));
        public static final StreamCodec<ByteBuf,Reply> CODEC = StreamCodec.composite(ID,Reply::request,ByteBufCodecs.stringUtf8(20),Reply::state,ByteBufCodecs.INT,Reply::sequence,TEXT,Reply::body,Reply::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static final class Session {
        final ManagerTile manager; final UUID owner; final Object connection; final String request; final long revision, deadline;
        final CatalogExchange.Conflict conflict; final StringBuilder input = new StringBuilder();
        int sequence, bytes, outputSequence, outputOffset; String output = "", outputKind = "", confirmation = "";
        CatalogExchange.Document document;
        Session(ServerPlayer actor, ManagerTile manager, Request p, CatalogExchange.Conflict conflict) {
            this.manager = manager; this.owner = manager.owner; this.connection = actor.connection; this.request = p.request(); this.revision = p.revision(); this.conflict = conflict; deadline = System.nanoTime() + LIFETIME;
        }
    }
    public static void register(PayloadRegistrar registrar) {
        registrar.executesOn(HandlerThread.NETWORK).playToServer(Request.TYPE,Request.CODEC,(p,c)->{
            ExchangeIngress.Permit permit = INGRESS.acquire(c.connection(),System.nanoTime());
            if (permit == null) { c.disconnect(Component.translatable("disconnect.exceeded_packet_rate")); return; }
            try { c.enqueueWork(()->{ if(c.player() instanceof ServerPlayer actor && actor.connection.getConnection() == c.connection()) apply(actor,p); }).whenComplete((v,e)->permit.close()); }
            catch(RuntimeException e) { permit.close(); throw e; }
        });
        registrar.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.CatalogExchangeScreen.receive(p)));
    }
    private static void apply(ServerPlayer actor, Request p) {
        long now = System.nanoTime(); SESSIONS.entrySet().removeIf(e->now-e.getValue().deadline>=0);
        if (!uuid(p.request()) || !uuid(p.identity())) return;
        try {
            if (!actor.level().hasChunkAt(p.source()) || !(actor.level().getBlockEntity(p.source()) instanceof ManagerTile manager)
                    || !manager.identity.toString().equals(p.identity()) || !manager.controlAllowed(actor) || manager.owner == null) throw error("permission");
            if (p.action().equals("cancel")) { Session s = SESSIONS.get(actor.getUUID()); if(s!=null&&s.request.equals(p.request()))SESSIONS.remove(actor.getUUID()); return; }
            if (p.action().equals("export") || p.action().equals("begin")) {
                if (!SESSIONS.containsKey(actor.getUUID()) && SESSIONS.size() >= MAX_SESSIONS) throw error("exchange_busy");
                manager.library.catalog().requireRevision(p.revision());
                Session s = new Session(actor,manager,p,p.action().equals("begin") ? CatalogExchange.Conflict.valueOf(p.body()) : CatalogExchange.Conflict.SKIP);
                SESSIONS.put(actor.getUUID(),s);
                if(p.action().equals("export")) { s.output=manager.library.exportCatalog(actor);s.outputKind="export";sendOutput(actor,s,0); }
                else reply(actor,p,"upload",0,"");
                return;
            }
            Session s = SESSIONS.get(actor.getUUID());
            if(s==null || s.connection!=actor.connection || s.manager!=manager || !s.owner.equals(manager.owner) || !s.request.equals(p.request()) || s.revision!=p.revision()) throw error("exchange_expired");
            manager.library.catalog().requireRevision(s.revision);
            switch(p.action()) {
                case "chunk" -> {
                    if(s.document!=null || !s.output.isEmpty() || p.sequence()!=s.sequence || p.body().isEmpty()) throw error("exchange_sequence");
                    if(s.sequence>=CatalogExchange.MAX_BYTES/CHUNK_CHARS+2)throw error("byte_limit");
                    s.bytes+=p.body().getBytes(StandardCharsets.UTF_8).length;
                    if(s.bytes>CatalogExchange.MAX_BYTES || s.input.length()+p.body().length()>CatalogExchange.MAX_BYTES)throw error("byte_limit");
                    s.input.append(p.body());s.sequence++;reply(actor,p,"upload",s.sequence,"");
                }
                case "preview" -> {
                    if(s.document!=null || p.sequence()!=s.sequence || !s.output.isEmpty())throw error("exchange_sequence");
                    s.document=CatalogExchange.decode(s.input.toString());s.input.setLength(0);
                    var preview=manager.library.previewCatalog(actor,s.revision,s.document,s.conflict);
                    s.confirmation=preview.applicable()?UUID.randomUUID().toString():"";
                    s.output=FilmNetwork.JSON.toJson(new Review(preview,s.confirmation));s.outputKind="preview";sendOutput(actor,s,0);
                }
                case "next" -> sendOutput(actor,s,p.sequence());
                case "commit" -> {
                    if(s.document==null || s.confirmation.isEmpty() || !s.confirmation.equals(p.body()) || s.outputOffset!=s.output.length())throw error("confirm_required");
                    manager.library.importCatalog(actor,s.revision,s.document,s.conflict,true);
                    SESSIONS.remove(actor.getUUID());reply(actor,p,"committed",0,"");
                }
                default -> throw error("exchange_invalid");
            }
        } catch(RuntimeException failure) {
            Session session = SESSIONS.get(actor.getUUID()); if(session!=null&&session.request.equals(p.request()))SESSIONS.remove(actor.getUUID());
            String key=failure.getMessage();reply(actor,p,"error",0,key!=null&&key.matches("mycinema\\.catalog\\.[a-z_]{1,64}")?key:"mycinema.catalog.exchange_invalid");
        }
    }
    public record Review(CatalogExchange.Preview preview, String confirmation) {}
    private static void sendOutput(ServerPlayer actor,Session s,int sequence) {
        if(s.output.isEmpty() || sequence!=s.outputSequence || s.outputOffset>=s.output.length())throw error("exchange_sequence");
        int start=s.outputOffset,end=Math.min(s.output.length(),start+CHUNK_CHARS);
        if(end<s.output.length()&&Character.isHighSurrogate(s.output.charAt(end-1)))end--;
        String state=s.outputKind+(end==s.output.length()?"_done":"_part");s.outputSequence++;s.outputOffset=end;
        PacketDistributor.sendToPlayer(actor,new Reply(s.request,state,sequence,s.output.substring(start,end)));
        if(state.equals("export_done"))SESSIONS.remove(actor.getUUID());
    }
    private static void reply(ServerPlayer actor,Request p,String state,int sequence,String body){PacketDistributor.sendToPlayer(actor,new Reply(p.request(),state,sequence,body));}
    private static boolean uuid(String s){try{return UUID.fromString(s).toString().equals(s);}catch(RuntimeException e){return false;}}
    private static CatalogException error(String key){return new CatalogException(key);}
    private CatalogExchangeNetwork() {}
}
