package local.cinemamanager.library;
import com.google.gson.Gson;
import local.cinemamanager.*;
import local.cinemamanager.catalog.*;
import local.cinemamanager.model.ManagerState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.*;
/** Owner-only paged library API. Client arguments never contain metadata or publication grants. */
public final class FilmNetwork {
 public static final Gson JSON=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
 public static final int PAGE_SIZE=6;
 private static final Map<UUID,Long> LAST=new HashMap<>();
 public static final class Arguments {public long catalogRevision,filmRevision,managerRevision;public String filmId="",hallId="",showId="",time="",claimFingerprint="";public FilmCatalog.Draft draft;public int segment,page;public boolean confirmed;}
 public record Summary(String id,long revision,String title,int segments,int prepared,long durationMs){}
 public record Hall(String id,String name,String terminalIdentity){}
 public record Plan(String id,String title,String hallId,String time,long start,long end,String status,String filmSnapshot){}
 public record Data(long catalogRevision,long managerRevision,String timezone,int page,int pages,List<Summary> films,FilmCatalog.Film selected,List<Hall> halls,String selectedHall,String selectedShow,List<Plan> plans,String notice,boolean editable,boolean orderedPlayback,String programmeAuthority,String localSourceConsent,HallAuthorization.ClaimReview claimReview,long imageBudgetBytes,long worldBudgetBytes){}
 public record Request(BlockPos source,String identity,String action,String request,String json) implements CustomPacketPayload {
  public static final Type<Request> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"film_request"));
  public static final StreamCodec<ByteBuf,Request> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Request::source,ByteBufCodecs.stringUtf8(36),Request::identity,ByteBufCodecs.stringUtf8(32),Request::action,ByteBufCodecs.stringUtf8(36),Request::request,ByteBufCodecs.stringUtf8(40000),Request::json,Request::new);
  public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record View(BlockPos source,String identity,String request,boolean open,String json) implements CustomPacketPayload {
  public static final Type<View> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID,"film_view"));
  public static final StreamCodec<ByteBuf,View> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,View::source,ByteBufCodecs.stringUtf8(36),View::identity,ByteBufCodecs.stringUtf8(36),View::request,ByteBufCodecs.BOOL,View::open,ByteBufCodecs.stringUtf8(196608),View::json,View::new);
  public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public static void register(PayloadRegistrar registrar){CatalogExchangeNetwork.register(registrar);registrar.playToServer(Request.TYPE,Request.CODEC,(packet,context)->context.enqueueWork(()->{if(context.player() instanceof ServerPlayer actor)apply(actor,packet);}));registrar.playToClient(View.TYPE,View.CODEC,(packet,context)->context.enqueueWork(()->local.cinemamanager.client.FilmLibraryScreen.open(packet)));}
 public static void open(ServerPlayer actor,ManagerTile manager,String hallId,String showId){if(!manager.controlAllowed(actor))return;Arguments args=new Arguments();args.hallId=hallId;args.showId=showId;send(actor,manager,args,"",true,"");}
 private static boolean accept(ServerPlayer actor){long now=actor.getServer().getTickCount(),last=LAST.getOrDefault(actor.getUUID(),Long.MIN_VALUE);if(last!=Long.MIN_VALUE&&now>=last&&now-last<2)return false;LAST.put(actor.getUUID(),now);if(LAST.size()>128)LAST.entrySet().removeIf(e->now-e.getValue()>400||now<e.getValue());return true;}
 private static String text(String value){return value==null?"":value;}
 public static void apply(ServerPlayer actor,Request packet){
  try{UUID.fromString(packet.request());}catch(RuntimeException ignored){return;}
  if(!actor.level().hasChunkAt(packet.source())||!(actor.level().getBlockEntity(packet.source()) instanceof ManagerTile manager)||!manager.identity.toString().equals(packet.identity())||!manager.controlAllowed(actor)||!accept(actor))return;
  Arguments args;try{args=JSON.fromJson(packet.json(),Arguments.class);if(args==null)throw new IllegalArgumentException();}catch(RuntimeException invalid){return;}args.filmId=text(args.filmId);args.hallId=text(args.hallId);args.showId=text(args.showId);args.time=text(args.time);String notice="";
  try{switch(packet.action()){
   case "view"->{}
   case "create"->{var film=manager.library.create(actor,args.catalogRevision,args.draft);args.filmId=film.id();notice="mycinema.catalog.created";}
   case "update"->{manager.library.update(actor,args.filmId,args.filmRevision,args.draft);notice="mycinema.catalog.updated";}
   case "delete"->{manager.library.delete(actor,args.filmId,args.filmRevision,args.confirmed);args.filmId="";notice="mycinema.catalog.deleted";}
   case "prepare_all"->{manager.library.prepareAll(actor,args.hallId,args.filmId,args.filmRevision,args.confirmed);notice="mycinema.catalog.preparing_all";}
   case "prepare"->{manager.library.prepare(actor,args.hallId,args.filmId,args.filmRevision,args.segment,args.confirmed);notice="mycinema.catalog.preparing";}
   case "read"->{manager.library.readCurrent(actor,args.hallId,args.filmId,args.filmRevision,args.segment);notice=manager.library.probeNotice(args.hallId,args.filmId);if(notice.isEmpty())notice="mycinema.catalog.metadata_ready";}
   case "auth_programme"->{manager.authorizeProgramme(actor,args.hallId,args.confirmed);notice="mycinema.catalog.programme_authorized";}
   case "claim_unowned_hall"->{manager.claimUnownedHall(actor,args.hallId,args.managerRevision,args.claimFingerprint,args.confirmed);notice="mycinema.catalog.hall_claimed";}
   case "auth_local_programme"->{consentRevision(manager,args);manager.authorizeLocalProgramme(actor,args.hallId,args.filmId,args.filmRevision,args.confirmed);notice="mycinema.catalog.local_programme_authorized";}
   case "revoke_local_programme"->{consentRevision(manager,args);manager.revokeLocalProgramme(actor,args.hallId,args.confirmed);notice="mycinema.catalog.local_programme_revoked";}
   case "load_programme"->{manager.playback.load(actor,args.hallId,args.filmId,args.filmRevision,args.confirmed);notice="mycinema.catalog.programme_loaded";}
   case "schedule"->{manager.library.schedule(actor,args.hallId,args.filmId,args.filmRevision,args.managerRevision,args.time,"",args.confirmed);notice="mycinema.catalog.scheduled";}
   case "replace"->{if(args.showId.isBlank())throw new CatalogException("snapshot_missing");manager.library.schedule(actor,args.hallId,args.filmId,args.filmRevision,args.managerRevision,args.time,args.showId,args.confirmed);notice="mycinema.catalog.replaced";}
   case "reschedule"->{if(!args.confirmed)throw new CatalogException("confirm_required");manager.library.reschedule(actor,args.showId,args.managerRevision,args.time);notice="mycinema.schedule.updated";}
   default->throw new CatalogException("invalid");
  }}catch(RuntimeException failure){String message=failure.getMessage();notice=message!=null&&message.startsWith("mycinema.")?message:"mycinema.catalog.invalid";}send(actor,manager,args,packet.request(),false,notice);
 }
 private static void consentRevision(ManagerTile manager,Arguments args){manager.library.catalog().requireRevision(args.catalogRevision);if(manager.state.revision!=args.managerRevision)throw new CatalogException("stale_manager");}
 private static Data data(ServerPlayer actor,ManagerTile manager,Arguments args,String notice){
  var catalog=manager.library.catalog();List<FilmCatalog.Film> films=catalog.films();int pages=Math.max(1,(films.size()+PAGE_SIZE-1)/PAGE_SIZE),page=Math.clamp(args.page,0,pages-1);FilmCatalog.Film selected=films.stream().filter(f->f.id().equals(args.filmId)).findFirst().orElse(null);
  List<Summary> rows=films.stream().skip((long)page*PAGE_SIZE).limit(PAGE_SIZE).map(f->new Summary(f.id(),f.revision(),f.title(),f.segments().size(),(int)f.segments().stream().filter(s->s.durationMs().isPresent()).count(),f.totalDurationMs().isPresent()?Math.addExact(f.requireDurationMs(),Math.multiplyExact((long)f.segments().size()-1,FilmCatalog.DEFAULT_TRANSITION_GAP_MS)):-1)).toList();
  List<Hall> halls=manager.state.halls.stream().map(h->new Hall(h.id,h.name,h.terminalIdentity)).toList();String hall=halls.stream().anyMatch(h->h.id().equals(args.hallId))?args.hallId:halls.isEmpty()?"":halls.getFirst().id();
  List<Plan> plans=manager.state.shows.stream().filter(s->s.status==ManagerState.Status.PENDING&&s.start>System.currentTimeMillis()).map(s->new Plan(s.id,s.title,s.hallId,manager.state.inputTime(s.start),s.start,s.end,s.status.name(),s.filmSnapshot)).toList();String selectedShow=plans.stream().anyMatch(s->s.id().equals(args.showId))?args.showId:"";
  if(notice.isEmpty()&&selected!=null)notice=manager.library.probeNotice(hall,selected.id());
  return new Data(catalog.revision(),manager.state.revision,manager.state.timezone,page,pages,rows,selected,halls,hall,selectedShow,plans,notice,true,manager.library.supportsOrderedPlayback(),HallAuthorization.programmeStatus(manager,hall),manager.playback.localConsentStatus(hall,selected),manager.claimReview(actor,hall),local.cinemamanager.panels.assets.AssetLimits.imageBytes(),local.cinemamanager.panels.assets.AssetLimits.worldBytes());
 }
 private static void send(ServerPlayer actor,ManagerTile manager,Arguments args,String request,boolean open,String notice){if(manager.controlAllowed(actor))PacketDistributor.sendToPlayer(actor,new View(manager.getBlockPos(),manager.identity.toString(),request,open,JSON.toJson(data(actor,manager,args,notice))));}
 private FilmNetwork(){}
}
