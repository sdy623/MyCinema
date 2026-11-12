package local.cinemamanager.library;
import com.mojang.authlib.GameProfile;
import local.cinemamanager.*;
import local.cinemamanager.catalog.*;
import local.cinemamanager.catalog.FilmCatalog.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.PosterPolicy;
import local.cinemamanager.panels.assets.*;
import local.cinemamanager.policy.CinemaPermissions;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.media.MediaSource;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.media.CinemaSources;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import java.util.*;
/** Film authoring and source preparation. All world access is serialized on the server thread. */
public final class FilmLibrary {
 private final ManagerTile manager;
 private FilmCatalog catalog=new FilmCatalog();
 private Tag invalidBackup;
 private final Map<String,Probe> probes=new HashMap<>();
 private final Map<String,ProbeOutcome> probeOutcomes=new LinkedHashMap<>();
 private record ProbeOutcome(String filmId,long revision,String key){}
 private record Probe(String filmId,long revision,int index,String source,UUID actor,UUID owner,UUID controller,
  UUID terminal,UUID epoch,long deadline,List<Integer> remaining,boolean all){
  private Probe{remaining=List.copyOf(remaining);if(remaining.size()>=FilmCatalog.MAX_SEGMENTS)throw error("segment_limit");}
 }
 public FilmLibrary(ManagerTile manager){this.manager=manager;}
 public FilmCatalog catalog(){return catalog;}
 public void reset(){catalog=new FilmCatalog();probes.clear();probeOutcomes.clear();invalidBackup=null;}
 private ServerLevel level(){
  if(!(manager.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()||manager.isRemoved()
   ||!level.hasChunkAt(manager.getBlockPos())||level.getBlockEntity(manager.getBlockPos())!=manager)throw error("permission");return level;
 }
 private void allowed(ServerPlayer actor){level();if(!manager.controlAllowed(actor)||manager.owner==null)throw error("permission");}
 private CinemaSources.ControllerRef controller(){return new CinemaSources.ControllerRef(manager.getBlockPos(),manager.identity);}
 private static CatalogException error(String suffix){return new CatalogException(suffix);}
 private static void result(CinemaSources.Result result){if(result.error()!=null)throw error("source_"+(result.error().matches("[a-z_]{1,40}")?result.error():"unavailable"));}
 private Draft checkedDraft(ServerPlayer actor,Draft input){
  if(input==null)throw error("invalid");List<String> sources=new ArrayList<>();
  for(String raw:input.normalizedSources()){String source=MediaSource.normalize(raw);if(source==null||!CinemaSources.validSource(new CinemaSources.SourceRef(source,0))||!CinemaPermissions.canSave(actor,source))throw error("source_permission");sources.add(source);}
  String poster=PosterPolicy.normalize(input.poster());if(poster==null||!(AssetReference.isAsset(poster)?PanelAssets.authorizesAsset(level(),actor.getUUID(),poster):PosterPolicy.maySave(actor,poster)))throw error("poster_permission");return new Draft(input.title(),poster,sources);
 }
 private Publication publication(ServerPlayer actor,String poster){return new Publication(actor.getUUID().toString(),actor.getGameProfile().getName(),manager.identity.toString(),manager.owner.toString(),poster);}
 public Film create(ServerPlayer actor,long expected,Draft draft){allowed(actor);Draft normalized=checkedDraft(actor,draft);Film film=catalog.create(expected,normalized,publication(actor,normalized.poster()));manager.notice("mycinema.catalog.created");return film;}
 public Film update(ServerPlayer actor,String id,long revision,Draft draft){allowed(actor);Draft normalized=checkedDraft(actor,draft);Film film=catalog.update(id,revision,normalized,publication(actor,normalized.poster()));cancelFilmProbes(id);manager.notice("mycinema.catalog.updated");return film;}
 public void delete(ServerPlayer actor,String id,long revision,boolean confirmed){allowed(actor);if(!confirmed)throw error("confirm_required");catalog.delete(id,revision);cancelFilmProbes(id);manager.notice("mycinema.catalog.deleted");}
 /** Metadata exchange reuses normal save policy and never probes a file or opens a media source. */
 public String exportCatalog(ServerPlayer actor){allowed(actor);return CatalogExchange.encode(CatalogExchange.fromCatalog(catalog));}
 private CatalogExchange.Approved exchangeApproved(ServerPlayer actor,CatalogExchange.Entry entry){
  Draft normalized=checkedDraft(actor,entry.draft());Set<String> warnings=new LinkedHashSet<>();
  for(String source:normalized.normalizedSources()){
   if(MediaSource.relative(source)||source.startsWith("file:"))warnings.add("mycinema.catalog.exchange_local_reference");
   else warnings.add("mycinema.catalog.exchange_remote_unverified");
  }
  String poster=normalized.poster();
  if(AssetReference.isAsset(poster))warnings.add("mycinema.catalog.exchange_server_asset");
  else if(PosterPolicy.resource(poster))warnings.add("mycinema.catalog.exchange_resource_pack");
  else if(!poster.isEmpty()&&(MediaSource.relative(poster)||poster.startsWith("file:")))warnings.add("mycinema.catalog.exchange_local_reference");
  else if(!poster.isEmpty())warnings.add("mycinema.catalog.exchange_remote_unverified");
  return new CatalogExchange.Approved(normalized,publication(actor,normalized.poster()),List.copyOf(warnings));
 }
 public CatalogExchange.Preview previewCatalog(ServerPlayer actor,long revision,CatalogExchange.Document document,CatalogExchange.Conflict conflict){
  allowed(actor);return CatalogExchange.prepare(catalog,revision,document,conflict,entry->exchangeApproved(actor,entry)).preview();
 }
 public CatalogExchange.Preview importCatalog(ServerPlayer actor,long revision,CatalogExchange.Document document,CatalogExchange.Conflict conflict,boolean confirmed){
  allowed(actor);if(!confirmed)throw error("confirm_required");
  // Recheck all current permissions and quotas immediately before the atomic catalog reference swap.
  CatalogExchange.Prepared prepared=CatalogExchange.prepare(catalog,revision,document,conflict,entry->exchangeApproved(actor,entry));
  if(!prepared.preview().applicable())throw error("exchange_review_failed");
  catalog=prepared.candidate();
  for(CatalogExchange.Row row:prepared.preview().rows())if(row.action().equals("UPDATE"))cancelFilmProbes(row.id());
  manager.notice("mycinema.catalog.exchange_imported");return prepared.preview();
 }
 private Film checkedFilm(String id,long revision){Film film=catalog.film(id);if(film.revision()!=revision)throw error("stale_film");return film;}
 private void managerRevision(long expected){if(manager.state.revision!=expected)throw error("stale_manager");if(expected==Long.MAX_VALUE)throw error("revision_exhausted");}
 private FilmCatalog copyCatalog(){return FilmCatalogCodec.decode(FilmCatalogCodec.encode(catalog));}
 private CinemaTerminalTile scheduleTerminal(ServerPlayer actor,String hallId){
  var terminal=manager.catalogTerminal(actor,hallId);
  HallAuthorization.requireTmsOwner(manager,actor,terminal);
  if(CinemaSources.snapshot(terminal)==null)throw error("source_permission");return terminal;
 }
 /** Ordered source operations are implemented by the manager-owned FilmPlayback adapter. */
 public boolean supportsOrderedPlayback(){return true;}
 /**
  * Checks a retained immutable program against current server-owned source evidence. A null actor
  * is for delegated runtime use only: WaterFrames must still recheck its grant and each exact URL
  * at the target terminal. No actor, metadata, permission or evidence is synthesized here.
  */
 public void validateSnapshot(Snapshot snapshot,ServerPlayer actor){
  level();if(snapshot==null||!catalog.snapshot(snapshot.id()).equals(snapshot))throw error("snapshot_invalid");
  validateProgram(snapshot,actor);
 }
 private void validateProgram(Snapshot snapshot,ServerPlayer actor){
  if(actor!=null)allowed(actor);else level();
  Film film=snapshot.film();requirePublication(film);film.requireDurationMs();
  for(Segment segment:film.segments()){
   String source=segment.sourceKey();
   if(!CinemaSources.validSource(new CinemaSources.SourceRef(source,0)))throw error("source_invalid");
   if(actor!=null&&!CinemaPermissions.canSave(actor,source))throw error("source_permission");
   Observation observation=segment.observation();
   if(observation==null||observation.provenance()==null)throw error("duration_unknown");
   if(observation.live())throw error("live_unschedulable");
   Provenance origin=observation.provenance();
   if(!origin.controllerId().equals(manager.identity.toString())||manager.owner==null
    ||!origin.authorId().equals(manager.owner.toString())||!origin.sourceKey().equals(source))throw error("provenance_invalid");
   if(origin.evidenceId()==null||observation.durationMs()<=0)throw error("owner_evidence_required");
   var evidence=CinemaSourceEvidence.get(level()).find(UUID.fromString(origin.evidenceId()));
   if(evidence==null||!evidence.id().toString().equals(origin.evidenceId())||!evidence.matches(manager.owner,source,0)
    ||!evidence.terminal().toString().equals(origin.terminalIdentity())||evidence.durationMs()!=observation.durationMs())throw error("evidence_unavailable");
  }
 }
 private void publicationForSave(Snapshot snapshot){if(!snapshot.film().poster().isBlank()&&!posterAuthorized(snapshot,null))throw error("poster_permission");}
 private Publication requirePublication(Film film){
  Publication author=film.publication();
  if(author==null||manager.owner==null||!author.controllerId().equals(manager.identity.toString())
   ||!author.controllerOwner().equals(manager.owner.toString())||!author.posterSource().equals(film.poster()))throw error("publication_invalid");return author;
 }
 /** Read-only validation of the reviewed film revision before granting local programme consent. */
 public Film consentFilm(ServerPlayer actor,String filmId,long expectedRevision){
  allowed(actor);Film film=checkedFilm(filmId,expectedRevision);FilmCatalog review=copyCatalog();
  validateProgram(review.snapshot(filmId,expectedRevision),actor);return film;
 }
 /** Captures the current revision for an explicitly authorized manual action; no transport changes. */
 public Snapshot captureSnapshot(ServerPlayer actor,String filmId,long expectedFilmRevision){
  allowed(actor);checkedFilm(filmId,expectedFilmRevision);FilmCatalog next=copyCatalog();
  Snapshot snapshot=next.snapshot(filmId,expectedFilmRevision);validateProgram(snapshot,actor);publicationForSave(snapshot);
  catalog=next;manager.dirty();return snapshot;
 }
 /** Saves only the reviewed plan, atomically with its immutable program. Never enables or arms it. */
 public ManagerState.Show schedule(ServerPlayer actor,String hallId,String filmId,long filmRevision,long expectedManagerRevision,String localTime,String replacementShowId,boolean confirmed){
  allowed(actor);managerRevision(expectedManagerRevision);if(!confirmed)throw error("confirm_required");checkedFilm(filmId,filmRevision);
  if(replacementShowId==null)throw error("invalid");
  boolean replacing=!replacementShowId.isBlank();
  if(replacing&&!manager.state.show(replacementShowId).hallId.equals(hallId))throw error("snapshot_invalid");
  scheduleTerminal(actor,hallId);
  FilmCatalog nextCatalog=copyCatalog();ManagerState nextState=StateCodec.decode(StateCodec.encode(manager.state));
  Snapshot snapshot=nextCatalog.snapshot(filmId,filmRevision);validateProgram(snapshot,actor);publicationForSave(snapshot);
  if(snapshot.film().segments().size()>1&&!supportsOrderedPlayback())throw error("ordered_unavailable");
  long now=System.currentTimeMillis();
  ManagerState.Show show=replacing?nextState.replaceFilmShow(replacementShowId,expectedManagerRevision,snapshot,localTime,now)
   :nextState.addFilmShow(hallId,snapshot,localTime,now);
  nextState.validate();FilmCatalogCodec.encode(nextCatalog);
  nextState.notice=replacing?"mycinema.catalog.replaced":"mycinema.catalog.scheduled";
  // All checks and bounded serialization precede these server-thread assignments. Historical,
  // deleted-film and runtime references are not fully enumerated here, so snapshots are retained.
  catalog=nextCatalog;manager.state=nextState;manager.dirty();return show;
 }
 /** Changes the time of an existing future plan while retaining its saved film revision. */
 public ManagerState.Show reschedule(ServerPlayer actor,String showId,long expectedManagerRevision,String localTime){
  allowed(actor);managerRevision(expectedManagerRevision);var existing=manager.state.show(showId);
  scheduleTerminal(actor,existing.hallId);Snapshot snapshot=snapshot(existing);validateSnapshot(snapshot,actor);
  ManagerState nextState=StateCodec.decode(StateCodec.encode(manager.state));
  var show=nextState.rescheduleFilmShow(showId,expectedManagerRevision,localTime,System.currentTimeMillis());
  nextState.validate();nextState.notice="mycinema.schedule.updated";manager.state=nextState;manager.dirty();return show;
 }
 /** Only a bounded translation key for this exact selected film revision; never source or actor data. */
 public String probeNotice(String hallId,String filmId){
  level();trimProbeState();ProbeOutcome outcome=probeOutcomes.get(hallId);
  if(outcome==null||!outcome.filmId().equals(filmId))return "";
  return catalog.films().stream().anyMatch(f->f.id().equals(filmId)&&f.revision()==outcome.revision())?outcome.key():"";
 }
 private void trimProbeState(){
  Set<String> halls=new HashSet<>();for(var hall:manager.state.halls)halls.add(hall.id);
  probes.keySet().retainAll(halls);
  probeOutcomes.entrySet().removeIf(entry->!halls.contains(entry.getKey())
   ||catalog.films().stream().noneMatch(f->f.id().equals(entry.getValue().filmId())&&f.revision()==entry.getValue().revision()));
 }
 private void probeOutcome(String hallId,String filmId,long revision,String key){
  trimProbeState();probeOutcomes.remove(hallId);
  while(probeOutcomes.size()>=ManagerState.MAX_HALLS)probeOutcomes.remove(probeOutcomes.keySet().iterator().next());
  String safe=key!=null&&key.matches("mycinema\\.catalog\\.[a-z_]{1,80}")?key:"mycinema.catalog.metadata_unavailable";
  probeOutcomes.put(hallId,new ProbeOutcome(filmId,revision,safe));
 }
 private static String source(Film film,int index){if(index<0||index>=film.segments().size())throw error("segment_invalid");return film.segments().get(index).sourceKey();}
 private void cancelFilmProbes(String filmId){
  probes.values().removeIf(probe->probe.filmId().equals(filmId));
  probeOutcomes.values().removeIf(outcome->outcome.filmId().equals(filmId));
 }
 /** First occurrence of each exact source, preserving programme order and duplicate observations. */
 private static List<Integer> uniqueSegments(Film film){
  Set<String> seen=new HashSet<>();List<Integer> indices=new ArrayList<>();
  for(int i=0;i<film.segments().size();i++)if(seen.add(source(film,i)))indices.add(i);
  return List.copyOf(indices);
 }
 private Film clearObservations(FilmCatalog next,Film film,List<Integer> indices,ServerPlayer actor,CinemaTerminalTile terminal){
  Film cleared=film;
  for(int index:indices){String source=source(film,index);cleared=next.observeTrusted(film.id(),cleared.revision(),index,source,false,-1,
   new Provenance(actor.getUUID().toString(),manager.identity.toString(),terminal.identity().toString(),source));}
  return cleared;
 }
 /** Explicit inspection replaces this hall's source, keeps it paused, and selects manual takeover. */
 public void prepare(ServerPlayer actor,String hallId,String filmId,long revision,int index,boolean confirmed){
  beginInspection(actor,hallId,filmId,revision,List.of(index),false,confirmed);
 }
 /** Explicitly revalidates every unique source, serially, through the existing paused WF session. */
 public void prepareAll(ServerPlayer actor,String hallId,String filmId,long revision,boolean confirmed){
  allowed(actor);if(!confirmed)throw error("confirm_required");Film film=checkedFilm(filmId,revision);
  beginInspection(actor,hallId,filmId,revision,uniqueSegments(film),true,true);
 }
 private void beginInspection(ServerPlayer actor,String hallId,String filmId,long revision,List<Integer> indices,boolean all,boolean confirmed){
  allowed(actor);if(!confirmed)throw error("confirm_required");Film film=checkedFilm(filmId,revision);
  if(all&&level().getServer().getPlayerList().getPlayer(actor.getUUID())!=actor)throw error("permission");
  var terminal=scheduleTerminal(actor,hallId);var before=CinemaSources.snapshot(terminal);if(before==null)throw error("source_unavailable");
  for(int index:indices)if(!CinemaPermissions.canSave(actor,source(film,index)))throw error("source_permission");
  FilmCatalog next=copyCatalog();Film cleared=clearObservations(next,film,indices,actor,terminal);
  // A new explicit inspection supersedes any queued continuation, even if the device then rejects it.
  cancelFilmProbes(filmId);probes.remove(hallId);probeOutcomes.remove(hallId);
  if(!terminal.playbackAvailable())throw error("source_unavailable");
  int index=indices.getFirst();String source=source(film,index);
  var prepared=CinemaSources.prepare(terminal,actor,controller(),new CinemaSources.SourceRef(source,0),before.epoch(),null);result(prepared);
  catalog=next;var hall=manager.state.hall(hallId);manager.state.takeOver(hall);hall.transport=ManagerState.Transport.PAUSED;hall.current=film.title();hall.issue="";
  trimProbeState();Probe probe=new Probe(film.id(),cleared.revision(),index,source,actor.getUUID(),manager.owner,manager.identity,
   terminal.identity(),prepared.session().epoch(),level().getGameTime()+600,indices.subList(1,indices.size()),all);
  probes.put(hallId,probe);String key=all?"mycinema.catalog.preparing_all":"mycinema.catalog.preparing";
  probeOutcome(hallId,film.id(),cleared.revision(),key);manager.notice(key);
 }
 private void checkProbe(Probe probe,ServerPlayer actor,Film film,CinemaTerminalTile terminal){
  allowed(actor);if(!actor.getUUID().equals(probe.actor()))throw error("permission");
  if(!Objects.equals(manager.owner,probe.owner())||!terminal.ownedBy(probe.owner()))throw error("source_permission");
  if(!manager.identity.equals(probe.controller())||!terminal.identity().equals(probe.terminal())
   ||!terminal.data.paused||!film.id().equals(probe.filmId())||film.revision()!=probe.revision()||!source(film,probe.index()).equals(probe.source()))throw error("source_stale_session");
  if(!CinemaPermissions.canSave(actor,probe.source()))throw error("source_permission");
 }
 private void failedProbe(String hallId,Probe probe,long revision,RuntimeException failure){
  probes.remove(hallId);probeOutcome(hallId,probe.filmId(),revision,failure.getMessage());manager.notice(probeOutcomes.get(hallId).key());
 }
 private Film completeProbe(String hallId,Probe probe,ServerPlayer actor,Film film,CinemaTerminalTile terminal){
  checkProbe(probe,actor,film,terminal);var view=CinemaSources.snapshot(terminal);
  if(view==null||!probe.epoch().equals(view.epoch())||!probe.source().equals(view.url())||view.source()!=0)throw error("source_stale_session");
  if(!view.failure().isEmpty())throw error("source_"+view.failure());
  if(level().getGameTime()>probe.deadline())throw error("metadata_timeout");
  if(view.metadataReady()&&view.live())throw error("live_unschedulable");
  if(!view.metadataReady()||view.evidenceId()==null||!view.ready())throw error("metadata_pending");
  FilmCatalog next=copyCatalog();Film updated=capture(next,film,probe.index(),terminal,actor);catalog=next;probes.remove(hallId);
  if(probe.all()&&!probe.remaining().isEmpty()){
   try{
    // Only this successful trusted capture can advance the queue; no client duration is consulted.
    allowed(actor);if(level().getServer().getPlayerList().getPlayer(probe.actor())!=actor)throw error("permission");if(!Objects.equals(manager.owner,probe.owner())||!manager.identity.equals(probe.controller()))throw error("source_permission");
    int index=probe.remaining().getFirst();String source=source(updated,index);
    if(!CinemaPermissions.canSave(actor,source))throw error("source_permission");
    var prepared=CinemaSources.prepare(terminal,actor,controller(),new CinemaSources.SourceRef(source,0),probe.epoch(),null);result(prepared);
    Probe following=new Probe(updated.id(),updated.revision(),index,source,probe.actor(),probe.owner(),probe.controller(),probe.terminal(),
     prepared.session().epoch(),level().getGameTime()+600,probe.remaining().subList(1,probe.remaining().size()),true);
    probes.put(hallId,following);probeOutcome(hallId,updated.id(),updated.revision(),"mycinema.catalog.preparing_all");manager.notice("mycinema.catalog.preparing_all");
   }catch(RuntimeException failure){failedProbe(hallId,probe,updated.revision(),failure);}
  }else{probeOutcome(hallId,updated.id(),updated.revision(),"mycinema.catalog.metadata_ready");manager.notice("mycinema.catalog.metadata_ready");}
  return updated;
 }
 public Film readCurrent(ServerPlayer actor,String hallId,String filmId,long revision,int index){
  allowed(actor);Film film=checkedFilm(filmId,revision);var terminal=manager.catalogTerminal(actor,hallId);Probe pending=probes.get(hallId);
  if(pending!=null&&pending.filmId().equals(filmId)&&pending.revision()==revision&&pending.index()==index){
   try{return completeProbe(hallId,pending,actor,film,terminal);}
   catch(RuntimeException failure){if(!"mycinema.catalog.metadata_pending".equals(failure.getMessage()))failedProbe(hallId,pending,pending.revision(),failure);throw failure;}
  }
  FilmCatalog next=copyCatalog();Film updated=capture(next,film,index,terminal,actor);catalog=next;
  cancelFilmProbes(filmId);probeOutcome(hallId,updated.id(),updated.revision(),"mycinema.catalog.metadata_ready");manager.notice("mycinema.catalog.metadata_ready");return updated;
 }
 private Film capture(FilmCatalog target,Film film,int index,CinemaTerminalTile terminal,ServerPlayer actor){
  String source=source(film,index);if(!CinemaPermissions.canSave(actor,source)||!terminal.ownedBy(manager.owner))throw error("source_permission");var view=CinemaSources.snapshot(terminal);if(view==null||!view.url().equals(source)||view.source()!=0||!view.metadataReady())throw error("metadata_pending");if(view.live())throw error("live_unschedulable");if(view.evidenceId()==null||view.durationMs()<=0)throw error("owner_evidence_required");var evidence=CinemaSourceEvidence.get(level()).find(view.evidenceId());
  if(evidence==null||!evidence.owner().equals(manager.owner)||!evidence.url().equals(source)||evidence.source()!=0||evidence.durationMs()!=view.durationMs())throw error("evidence_unavailable");
  Provenance origin=new Provenance(evidence.owner().toString(),manager.identity.toString(),evidence.terminal().toString(),source,evidence.id().toString());Film current=film;
  for(int i=0;i<film.segments().size();i++)if(film.segments().get(i).sourceKey().equals(source))current=target.observeTrusted(film.id(),current.revision(),i,source,false,evidence.durationMs(),origin);return current;
 }
 public void tick(){
  if(manager.getLevel()==null||manager.getLevel().isClientSide||manager.getLevel().getGameTime()%5!=0)return;
  for(var entry:List.copyOf(probes.entrySet())){String hallId=entry.getKey();Probe probe=entry.getValue();
   if(probes.get(hallId)!=probe)continue;
   try{
    ServerPlayer actor=level().getServer().getPlayerList().getPlayer(probe.actor());allowed(actor);
    Film film=checkedFilm(probe.filmId(),probe.revision());var terminal=manager.catalogTerminal(actor,hallId);checkProbe(probe,actor,film,terminal);
    var view=CinemaSources.snapshot(terminal);
    if(view==null||!probe.epoch().equals(view.epoch())||!probe.source().equals(view.url())||view.source()!=0)throw error("source_stale_session");
    if(!view.failure().isEmpty())throw error("source_"+view.failure());if(level().getGameTime()>probe.deadline())throw error("metadata_timeout");
    if(view.metadataReady()&&view.live())throw error("live_unschedulable");
    if(!view.metadataReady()||view.evidenceId()==null||!view.ready())continue;
    completeProbe(hallId,probe,actor,film,terminal);
   }catch(RuntimeException failure){failedProbe(hallId,probe,probe.revision(),failure);}
  }
 }
 private record PosterSelection(Film film,String problem){
  String source(){return film==null?"":film.poster();}
  String identity(){return film==null?"":film.id()+"|"+film.revision()+"|"+film.publication();}
 }
 /** All public paths use this display-only decision; immutable programme validation stays separate. */
 private PosterSelection selectPoster(Snapshot snapshot){
  try{
   level();if(snapshot==null||!catalog.snapshot(snapshot.id()).equals(snapshot))return new PosterSelection(null,"mycinema.panel.poster_snapshot_invalid");
   Film saved=snapshot.film(),current=null;
   try{current=catalog.film(saved.id());}catch(CatalogException removed){/* Retained snapshots survive deletion. */}
   Film candidate=FilmPosterSelection.candidate(saved,current,FilmLibrary::publicPosterSource);
   if(candidate!=null)return posterAuthorized(candidate,null)?new PosterSelection(candidate,"")
    :new PosterSelection(null,"mycinema.panel.poster_publication_denied");
   if(saved.poster().isBlank())return new PosterSelection(null,current!=null&&!current.poster().isBlank()
    ?"mycinema.panel.poster_saved_empty":"mycinema.panel.poster_film_empty");
   return new PosterSelection(null,"mycinema.panel.poster_local_unpublished");
  }catch(RuntimeException invalid){return new PosterSelection(null,"mycinema.panel.poster_snapshot_invalid");}
 }
 public String approvedPoster(ManagerState.Show show){try{return approvedPosterSnapshot(snapshot(show));}catch(RuntimeException ignored){return "";}}
 public String approvedPosterSnapshot(Snapshot snapshot){return selectPoster(snapshot).source();}
 /** Server-private selected publication identity, used to retire same-URI transfer tokens. */
 public String posterSnapshotIdentity(Snapshot snapshot){return selectPoster(snapshot).identity();}
 /** Bounded owner-facing reason; never includes the private path, URL or authorship. */
 public String posterProblem(Snapshot snapshot){return selectPoster(snapshot).problem();}
 public boolean posterSnapshotTarget(Snapshot snapshot,String source,String target){
  try{Film selected=selectPoster(snapshot).film();return selected!=null&&selected.poster().equals(source)
   &&(target==null||publicPosterSource(target))&&posterAuthorized(selected,target);}
  catch(RuntimeException invalid){return false;}
 }
 public boolean posterTarget(ManagerState.Show show,String source,String target){try{return posterSnapshotTarget(snapshot(show),source,target);}catch(RuntimeException ignored){return false;}}
 private static boolean publicPosterSource(String source){
  return source!=null&&source.equals(PosterPolicy.normalize(source))&&(AssetReference.isAsset(source)
   ||PosterPolicy.resource(source)||source.startsWith("https://")||source.startsWith("http://"));
 }
 private boolean posterAuthorized(Snapshot snapshot,String target){return posterAuthorized(snapshot.film(),target);}
 private boolean posterAuthorized(Film film,String target){
  level();Publication author=film.publication();String source=film.poster();if(author==null||source.isBlank()||!author.controllerId().equals(manager.identity.toString())||manager.owner==null||!author.controllerOwner().equals(manager.owner.toString())||!author.posterSource().equals(source))return false;UUID identity=UUID.fromString(author.authorId());
  if(AssetReference.isAsset(source))return target==null&&PanelAssets.authorizesAsset(level(),identity,source);if(PosterPolicy.resource(source))return target==null||target.equals(source);if(!CinemaPermissions.canSavePosterOffline(level().getServer(),new GameProfile(identity,author.authorName()),source))return false;return target==null||CinemaPermissions.canSavePosterOffline(level().getServer(),new GameProfile(identity,author.authorName()),target);
 }
 public Snapshot snapshot(ManagerState.Show show){
  if(show==null||show.filmSnapshot==null||show.filmSnapshot.isBlank())throw error("snapshot_missing");Snapshot snapshot=catalog.snapshot(show.filmSnapshot);Film film=snapshot.film();if(!film.id().equals(show.filmId)||film.revision()!=show.filmRevision||!film.title().equals(show.title)||!film.segments().getFirst().sourceKey().equals(show.url)||snapshot.totalDurationMs()!=show.filmDurationMs||show.end<=show.start||show.end-show.start!=show.filmDurationMs)throw error("snapshot_invalid");return snapshot;
 }
 public long duration(ManagerState.Show show){try{return snapshot(show).film().requireDurationMs();}catch(RuntimeException ignored){return 0;}}
 public void save(CompoundTag tag){tag.put("film_catalog",CatalogStorage.write(FilmCatalogCodec.encode(catalog)));if(invalidBackup!=null)tag.put("invalid_film_catalog",invalidBackup.copy());}
 public void load(CompoundTag tag){probes.clear();probeOutcomes.clear();invalidBackup=tag.contains("invalid_film_catalog")?tag.get("invalid_film_catalog").copy():null;try{catalog=FilmCatalogCodec.decode(CatalogStorage.read(tag,"film_catalog"));}catch(RuntimeException failure){invalidBackup=tag.get("film_catalog")==null?null:tag.get("film_catalog").copy();catalog=new FilmCatalog();manager.state.notice="mycinema.catalog.saved_catalog_invalid";}}
}
