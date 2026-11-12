package local.cinemamanager.library;

import com.google.gson.Gson;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.catalog.CatalogException;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.control.SeekTickets;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.automation.CinemaAutomation;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.theater.CinemaProgramme;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** One ordered programme per enrolled hall; WF owns every source clock and decoder follower. */
public final class FilmPlayback {
    private static final Gson JSON = new Gson();
    private static final long PREPARE_TIMEOUT_TICKS = 600;
    private final ManagerTile manager;
    private final Map<String,Run> runs = new LinkedHashMap<>();
    private final Map<String,Stored> restored = new LinkedHashMap<>();
    private final SeekTickets tickets = new SeekTickets();
    private Tag invalidBackup, invalidConsentBackup;
    private LocalProgrammeConsents localConsents=new LocalProgrammeConsents();
    private record Stored(String hall,UUID terminal,UUID identity,FilmProgramState.Saved state,boolean stopRequested,boolean displayRetained) {}
    private static final class Run {
        final UUID terminal, identity;
        // Public display identity is deliberately unrelated to any control/seek token.
        final String publicId="programme:"+UUID.randomUUID();
        FilmProgramState programme;
        // A changed epoch is not reload proof. Retain the exact previously bound block entity.
        CinemaTerminalTile boundTerminal;
        UUID boundEvidence;
        long unavailableSince=-1, unavailableTicks, seekGeneration;
        long observedNanos, observedTick = -1, gapRemainderNanos;
        boolean historyRestorePending;
        boolean stopRequested;
        boolean displayRetained; // Explicit current screening display; absent from old saves means false.
        UUID observedHistoryEpoch;
        Run(UUID terminal,UUID identity,FilmProgramState programme){this.terminal=terminal;this.identity=identity;this.programme=programme;}
    }
    public FilmPlayback(ManagerTile manager){this.manager=manager;}
    private ServerLevel level(){if(!(manager.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread())throw error("permission");return level;}
    private long gameTick(){return level().getGameTime();}
    private long programmeTick(Run run){long now=gameTick();return now-run.unavailableTicks-(run.unavailableSince<0?0:now-run.unavailableSince);}
    private boolean terminalChunkLoaded(String hall){return level().hasChunkAt(net.minecraft.core.BlockPos.of(manager.state.hall(hall).terminalPos));}
    private void suspend(Run run){
        if(run.unavailableSince<0){run.unavailableSince=gameTick();run.seekGeneration++;}
        resetObservation(run);
    }
    private void continueLoaded(Run run){
        if(run.unavailableSince>=0){run.unavailableTicks+=Math.max(0,gameTick()-run.unavailableSince);run.unavailableSince=-1;resetObservation(run);}
    }
    private static void resetObservation(Run run){run.observedTick=-1;run.observedNanos=0;run.gapRemainderNanos=0;}
    private CinemaSources.ControllerRef controller(){return new CinemaSources.ControllerRef(manager.getBlockPos(),manager.identity);}
    private static CatalogException error(String reason){return new CatalogException(reason);}
    public boolean active(String hall){Run run=runs.get(hall);return run!=null&&!run.programme.finished();}
    public boolean currentProgrammePending(String hall){
        if(active(hall))return true;
        Stored saved=restored.get(hall);
        if(saved==null)return false;
        // Saved data is not validated until restoreRuns; malformed history must withhold publication too.
        if(saved.state()==null||saved.state().status()==null)return true;
        return switch(saved.state().status()){
            case PREPARING,READY,PLAYING,PAUSED,GAP->true;
            default->false;
        };
    }
    /** Withhold unrelated next-show fallback until the exact retained screening is verified or dismissed. */
    public boolean currentDisplayPending(String hall){
        if(currentProgrammePending(hall))return true;
        Run run=runs.get(hall);
        if(run!=null)return retainedCompletion(run);
        Stored saved=restored.get(hall);
        return saved!=null&&saved.displayRetained()&&!saved.stopRequested()&&saved.state()!=null
                &&saved.state().status()==FilmProgramState.Status.COMPLETED;
    }
    private static boolean retainedCompletion(Run run){
        return run.displayRetained&&!run.stopRequested&&run.programme.completedDisplayCandidate();
    }
    /** Actual playback stays separate from a naturally completed, explicitly retained screening display. */
    public record PublicProgramme(String publicId,FilmCatalog.Snapshot snapshot,ManagerState.Show scheduled) {}
    public PublicProgramme publicProgramme(String hall){return selectPublicProgramme(hall,false);}
    public PublicProgramme publicDisplayProgramme(String hall){return selectPublicProgramme(hall,true);}
    private PublicProgramme selectPublicProgramme(String hall,boolean includeCompleted){
        level();Run run=runs.get(hall);
        boolean completed=run!=null&&includeCompleted&&retainedCompletion(run);
        if(run==null||run.programme.finished()&&!completed||run.programme.restoring()||!run.programme.ownsSession())return null;
        try{
            var terminal=terminal(hall,run);
            if(run.boundTerminal!=terminal)return null;
            UUID expectedEpoch=run.programme.epoch();
            if(completed){
                // Cold history is display-only after the existing exact source/evidence revalidation.
                // It never acquires a playback command or promotes arbitrary legacy completed history.
                if(run.historyRestorePending||!matchesHistoryRestore(run,terminal))return null;
                if(run.observedHistoryEpoch!=null)expectedEpoch=run.observedHistoryEpoch;
                var grant=terminal.automationGrant();var online=level().getServer().getPlayerList().getPlayer(manager.owner);
                requireSourcePolicies(run.programme.snapshot().film(),hall,terminal,online==null?grant.mode():online.gameMode.getGameModeForPlayer());
            }else permission(run,terminal);
            var saved=run.programme.save();
            if(!CinemaSources.controlledBy(terminal,controller())||!Objects.equals(expectedEpoch,terminal.mediaEpoch())
                    ||saved.boundSourceType()!=terminal.data.getSource()||!Objects.equals(saved.boundSource(),terminal.data.getUrl()))return null;
            ManagerState.Show show=null;String showId=run.programme.scheduledShowId();
            if(showId!=null){
                show=manager.state.show(showId);
                boolean eligible=completed?show.status==ManagerState.Status.COMPLETED:
                        show.status==ManagerState.Status.PENDING||show.status==ManagerState.Status.PREPARING||show.status==ManagerState.Status.RUNNING;
                if(!show.hallId.equals(hall)||!eligible||!manager.library.snapshot(show).equals(run.programme.snapshot()))return null;
            }
            return new PublicProgramme(show==null?run.publicId:show.id,run.programme.snapshot(),show);
        }catch(RuntimeException unavailable){return null;}
    }
    public boolean present(String hall){Run run=runs.get(hall);return run!=null&&run.programme.status()!=FilmProgramState.Status.OVERRIDDEN||restored.containsKey(hall);}
    /** Explicitly owner-confirmed programme grants may continue with the global scheduler off. */
    public boolean enabled(UUID terminal){
        return runs.values().stream().anyMatch(r->r.terminal.equals(terminal)&&Objects.equals(manager.owner,r.programme.owner())
                &&!r.programme.finished())
                || restored.values().stream().anyMatch(r->r.terminal().equals(terminal)&&Objects.equals(manager.owner,r.state().owner())
                && r.state().status()!=FilmProgramState.Status.FAILED&&r.state().status()!=FilmProgramState.Status.COMPLETED
                &&r.state().status()!=FilmProgramState.Status.STOPPED&&r.state().status()!=FilmProgramState.Status.OVERRIDDEN);
    }
    /** Called only after the outer WF path verifies the exact persisted controller grant. */
    public boolean localSourceAllowed(UUID terminalId,String source){
        if(!local.cinemamanager.media.MediaSource.isNormalizedLocal(source))return false;
        for(var entry:runs.entrySet()){
            Run run=entry.getValue();
            if(run.terminal.equals(terminalId)&&!run.programme.finished()&&Objects.equals(manager.owner,run.programme.owner())
                    &&localConsents.permits(manager.owner,manager.identity,terminalId,entry.getKey(),run.programme.snapshot().film(),source))return true;
        }
        return false;
    }
    public void authorizeLocal(ServerPlayer actor,String hall,String filmId,long revision,boolean confirmed){
        level();if(!confirmed)throw error("confirm_required");
        if(manager.owner==null||!manager.owner.equals(actor.getUUID())||!manager.permits(actor,8))throw error("programme_owner_required");
        var terminal=manualTerminal(actor,hall);ensureGrant(actor,terminal);
        var film=manager.library.consentFilm(actor,filmId,revision);boolean any=false;
        for(var segment:film.segments())if(local.cinemamanager.media.MediaSource.isNormalizedLocal(segment.sourceKey())){
            any=true;
            if(!CinemaPermissions.canSave(actor,segment.sourceKey())||!CinemaPermissions.canAutomateLocalProgramme(level().getServer(),manager.owner,
                    actor.gameMode.getGameModeForPlayer(),terminal.getDisplayBlock(),segment.sourceKey()))throw error("local_source_qualification");
        }
        if(!any)throw error("local_sources_missing");
        localConsents.authorize(manager.owner,manager.identity,terminal.identity(),hall,film);manager.notice("mycinema.catalog.local_programme_authorized");
    }
    public void revokeLocal(ServerPlayer actor,String hall,boolean confirmed){
        level();if(!confirmed)throw error("confirm_required");
        if(manager.owner==null||!manager.owner.equals(actor.getUUID())||!manager.controlAllowed(actor))throw error("programme_owner_required");
        manager.state.hall(hall);localConsents.revokeHall(hall);manager.notice("mycinema.catalog.local_programme_revoked");
    }
    /** Preflight for an inactive saved plan, without inventing an active programme or touching media. */
    public boolean sourcePolicyAllows(FilmCatalog.Snapshot snapshot,String hall,CinemaTerminalTile terminal,String source,net.minecraft.world.level.GameType mode){
        return sourcePolicyError(snapshot.film(),hall,terminal,source,mode)==null;
    }
    private String sourcePolicyError(FilmCatalog.Film film,String hall,CinemaTerminalTile terminal,String source,net.minecraft.world.level.GameType mode){
        if(CinemaPermissions.canAutomateTheater(manager.owner,mode,terminal.getDisplayBlock(),source,true))return null;
        if(!local.cinemamanager.media.MediaSource.isNormalizedLocal(source))return "source_media_permission";
        if(!localConsents.permits(manager.owner,manager.identity,terminal.identity(),hall,film,source))return "source_local_consent_required";
        return CinemaPermissions.canAutomateLocalProgramme(level().getServer(),manager.owner,mode,terminal.getDisplayBlock(),source)?null:"source_local_source_permission";
    }
    public void requireSourcePolicies(FilmCatalog.Film film,String hall,CinemaTerminalTile terminal,net.minecraft.world.level.GameType mode){
        for(var segment:film.segments()){
            String error=sourcePolicyError(film,hall,terminal,segment.sourceKey(),mode);if(error!=null)throw error(error);
        }
    }
    public void clearConsents(){localConsents.clear();invalidConsentBackup=null;}
    /** First ownership registration cannot inherit an earlier device's programme or local consent. */
    public void retireClaimedHall(String hall){localConsents.revokeHall(hall);forget(hall);}
    /** Reports saved scope only; it does not grant consent or assert current server permission. */
    public String localConsentStatus(String hall,FilmCatalog.Film film){
        if(film==null)return "mycinema.catalog.local_consent_select_film";
        var sources=film.segments().stream().map(FilmCatalog.Segment::sourceKey)
                .filter(local.cinemamanager.media.MediaSource::isNormalizedLocal).toList();
        if(sources.isEmpty())return "mycinema.catalog.local_consent_not_needed";
        if(!film.schedulable())return "mycinema.catalog.local_consent_needs_evidence";
        try{var terminal=manager.programmeTerminal(hall);
            return sources.stream().allMatch(source->localConsents.permits(manager.owner,manager.identity,terminal.identity(),hall,film,source))
                    ?"mycinema.catalog.local_consent_saved":"mycinema.catalog.local_consent_not_saved";
        }catch(RuntimeException unavailable){return "mycinema.catalog.grant_target_unavailable";}
    }
    private CinemaTerminalTile terminal(String hall,Run run){
        CinemaTerminalTile terminal=manager.programmeTerminal(hall);
        if(run!=null&&!run.terminal.equals(terminal.identity()))throw error("source_stale_session");
        return terminal;
    }
    private void permission(Run run,CinemaTerminalTile terminal){
        if(manager.owner==null||!manager.owner.equals(run.programme.owner())||!terminal.ownedBy(manager.owner))throw error("source_permission");
        var grant=terminal.automationGrant();
        if(grant==null||!grant.matches(terminal.identity(),manager.identity,manager.owner,manager.getBlockPos()))throw error("source_grant_revoked");
        var online=level().getServer().getPlayerList().getPlayer(manager.owner);
        var mode=online==null?grant.mode():online.gameMode.getGameModeForPlayer();
        String source=run.programme.pending() instanceof FilmProgramState.Prepare p?p.source():terminal.data.getUrl();
        String policy=CinemaSources.delegatedSourceError(terminal,run.programme.owner(),controller(),source);
        if(policy!=null)throw error("source_"+policy);
        manager.library.validateSnapshot(run.programme.snapshot(),null);
    }
    /** A manual load is an explicit paused whole-programme operation, never an automatic start. */
    public void load(ServerPlayer actor,String hall,String film,long revision,boolean confirmed){
        if(!confirmed)throw error("confirm_required");
        var terminal=manualTerminal(actor,hall);
        ensureGrant(actor,terminal);
        var reviewed=manager.library.consentFilm(actor,film,revision);
        requireSourcePolicies(reviewed,hall,terminal,actor.gameMode.getGameModeForPlayer());
        var snapshot=manager.library.captureSnapshot(actor,film,revision);
        begin(hall,snapshot,null,true,terminal);
        manager.state.takeOver(manager.state.hall(hall));
        manager.notice("mycinema.catalog.programme_loaded");
    }
    public void loadShow(ServerPlayer actor,ManagerState.Show show){
        var terminal=manualTerminal(actor,show.hallId);
        var snapshot=manager.library.snapshot(show);manager.library.validateSnapshot(snapshot,actor);
        ensureGrant(actor,terminal);
        begin(show.hallId,snapshot,null,true,terminal);
        manager.state.takeOver(manager.state.hall(show.hallId));manager.notice("mycinema.catalog.programme_loaded");
    }
    private CinemaTerminalTile manualTerminal(ServerPlayer actor,String hall){
        if(!manager.controlAllowed(actor)||manager.owner==null||!manager.owner.equals(actor.getUUID()))throw error("programme_owner_required");
        return manager.catalogTerminal(actor,hall);
    }
    private void ensureGrant(ServerPlayer actor,CinemaTerminalTile terminal){
        var grant=terminal.automationGrant();
        if(grant!=null&&grant.matches(terminal.identity(),manager.identity,manager.owner,manager.getBlockPos()))return;
        throw error("programme_grant_required");
    }
    public String startScheduled(ManagerState.Hall hall,ManagerState.Show show){
        try{if(active(hall.id))throw error("programme_conflict");
            var snapshot=manager.library.snapshot(show);manager.library.validateSnapshot(snapshot,null);
            begin(hall.id,snapshot,show.id,false,terminal(hall.id,null));return null;
        }catch(RuntimeException failure){return key(failure);}
    }
    private void begin(String hall,FilmCatalog.Snapshot snapshot,String show,boolean paused,CinemaTerminalTile terminal){
        manager.library.validateSnapshot(snapshot,null);
        FilmProgramState programme=FilmProgramState.start(snapshot,manager.owner,show,session(terminal),gameTick(),PREPARE_TIMEOUT_TICKS);
        if(paused&&!programme.finished())programme.pause(true,session(terminal),gameTick());
        Run next=new Run(terminal.identity(),UUID.randomUUID(),programme);
        Run previous=runs.put(hall,next);Stored previousRestore=restored.remove(hall);
        try{permission(next,terminal);}catch(RuntimeException denied){
            if(previous==null)runs.remove(hall);else runs.put(hall,previous);
            if(previousRestore!=null)restored.put(hall,previousRestore);throw denied;
        }
        execute(next,terminal); // One atomic paused preparation. Never loop several sources in this tick.
        if(programme.finished()&&!programme.ownsSession()){
            if(previous==null)runs.remove(hall);else runs.put(hall,previous);
            if(previousRestore!=null)restored.put(hall,previousRestore);
            throw error(programme.failure());
        }
        next.boundTerminal=terminal;next.boundEvidence=terminal.sourceEvidenceId();next.displayRetained=!programme.finished();
        project(hall,next);manager.dirty();
        if(programme.status()==FilmProgramState.Status.FAILED||programme.status()==FilmProgramState.Status.OVERRIDDEN)throw error(programme.failure());
    }
    /** Retired show claims never control or project another programme now occupying their hall. */
    public String stopScheduled(String hall,String showId){
        if(restored.containsKey(hall))restoreRuns();
        Run run=runs.get(hall);
        if(run==null)return "mycinema.catalog.programme_missing";
        if(!Objects.equals(showId,run.programme.scheduledShowId()))return null;
        String result=stop(hall,null);
        if("mycinema.show.stop_pending".equals(result)){
            var show=manager.state.show(showId);
            if(show.hallId.equals(hall)&&ManagerState.active(show)&&show.status!=ManagerState.Status.STOP_PENDING){
                show.status=ManagerState.Status.STOP_PENDING;show.outcome=result;manager.state.revision++;manager.dirty();
            }
        }
        return result;
    }
    /** Returns null only after an actual matching source stop, or an already ended programme. */
    public String stop(String hall,ServerPlayer actor){
        if(restored.containsKey(hall))restoreRuns();
        Run run=runs.get(hall);if(run==null)return "mycinema.catalog.programme_missing";
        try{
            if(!terminalChunkLoaded(hall)&&!run.programme.finished()){
                requestUnavailableStop(hall,run,actor);return "mycinema.show.stop_pending";
            }
            continueLoaded(run);
            if(actor!=null)manager.catalogTerminal(actor,hall);
            var terminal=terminal(hall,run);
            if(run.programme.status()==FilmProgramState.Status.COMPLETED){
                return stopFinished(hall,run,terminal,actor,false);
            }
            if(run.programme.finished()){
                if(run.programme.status()!=FilmProgramState.Status.STOPPED||!run.programme.failure().isEmpty())return "mycinema.catalog."+run.programme.failure();
                return stopFinished(hall,run,terminal,actor,false);
            }
            return stopActive(hall,run,terminal);
        }catch(RuntimeException failure){return key(failure);}
    }
    private String stopActive(String hall,Run run,CinemaTerminalTile terminal){
        restoreReentered(hall,run,terminal);
        if(run.programme.restoring())requireRestoredAuthority(hall,run,terminal);
        run.stopRequested=true;run.displayRetained=false;
        if(run.programme.restoring())run.programme.stopRestored(session(terminal),programmeTick(run));
        else run.programme.stop(session(terminal));
        execute(run,terminal);project(hall,run);manager.dirty();
        if(run.programme.restoring())return "mycinema.show.stop_pending";
        return run.programme.status()==FilmProgramState.Status.STOPPED&&run.programme.failure().isEmpty()?null:"mycinema.catalog."+run.programme.failure();
    }
    /** Save only the owner's/controller's intent while the target cannot be inspected. */
    private void requestUnavailableStop(String hall,Run run,ServerPlayer actor){
        if(manager.owner==null||!manager.owner.equals(run.programme.owner())
                ||!run.terminal.toString().equals(manager.state.hall(hall).terminalIdentity)
                ||!local.cinemamanager.EnrollmentRegistry.get(level()).owns(run.terminal,manager.identity)
                ||actor!=null&&(!manager.controlAllowed(actor)||!manager.owner.equals(actor.getUUID())
                ||!CinemaPermissions.canBindRemote(actor)||!CinemaPermissions.canInteractRemote(actor)))throw error("source_permission");
        run.stopRequested=true;run.displayRetained=false;suspend(run);projectUnavailable(hall,run);manager.dirty();
    }
    private void projectUnavailable(String hall,Run run){
        var selected=manager.state.hall(hall);String issue=selected.issue;
        project(hall,run);selected.issue=run.stopRequested?"mycinema.show.stop_pending":"mycinema.catalog.programme_waiting_terminal";
        if(!selected.issue.equals(issue))manager.dirty();
    }
    public String transport(String hall,boolean paused,ServerPlayer actor){
        Run run=runs.get(hall);if(run==null||run.programme.finished())return "mycinema.catalog.programme_finished";
        try{var terminal=manager.catalogTerminal(actor,hall);permission(run,terminal);
            continueLoaded(run);restoreReentered(hall,run,terminal);
            if(run.programme.restoring())throw error("source_stale_session");
            run.programme.pause(paused,session(terminal),programmeTick(run));run.observedNanos=System.nanoTime();run.observedTick=gameTick();run.gapRemainderNanos=0;execute(run,terminal);project(hall,run);manager.dirty();
            return run.programme.finished()?"mycinema.catalog."+run.programme.failure():null;
        }catch(RuntimeException failure){return key(failure);}
    }
    public void forget(String hall){runs.remove(hall);restored.remove(hall);try{manager.state.hall(hall).programme=null;}catch(RuntimeException ignored){}}
    public void tick(){
        if(!(manager.getLevel() instanceof ServerLevel)||gameTick()%5!=0)return;
        restoreRuns();
        for(var entry:List.copyOf(runs.entrySet())){
            String hall=entry.getKey();Run run=entry.getValue();long tickNanos=System.nanoTime();var before=run.programme.save();CinemaTerminalTile terminal=null;boolean verifiedInstance=false;
            try{
                if(!terminalChunkLoaded(hall)){suspend(run);projectUnavailable(hall,run);continue;}
                continueLoaded(run);
                terminal=terminal(hall,run);
                verifiedInstance=run.boundTerminal==terminal;
                if(run.programme.finished()&&run.programme.pending()==null){
                    if(run.historyRestorePending||run.boundTerminal!=terminal){
                        if(run.boundTerminal!=null&&!reentered(run,terminal)||!matchesHistoryRestore(run,terminal)){forget(hall);manager.dirty();continue;}
                        run.historyRestorePending=false;
                        // WF rotates the epoch on disk load. This read-only observation retains
                        // the final summary; it never rebinds the model or authorizes a command.
                        run.observedHistoryEpoch=terminal.mediaEpoch();
                        run.boundTerminal=terminal;run.boundEvidence=terminal.sourceEvidenceId();
                    }
                    UUID observed=run.observedHistoryEpoch==null?run.programme.epoch():run.observedHistoryEpoch;
                    if(!Objects.equals(observed,terminal.mediaEpoch())||run.observedHistoryEpoch!=null
                            &&(!Objects.equals(manager.owner,run.programme.owner())||!terminal.ownedBy(run.programme.owner())
                            ||!CinemaSources.controlledBy(terminal,controller()))){forget(hall);manager.dirty();continue;}
                }
                if(!run.programme.finished()){
                    restoreReentered(hall,run,terminal);verifiedInstance=true;
                    permission(run,terminal);var current=session(terminal);
                    if(scheduledShowMissing(run))run.programme.reject("scheduled_show_missing",current);
                    else if(run.stopRequested||scheduledStopRequested(run)){
                        if(run.programme.restoring()){
                            requireRestoredAuthority(hall,run,terminal);
                            run.stopRequested=true;run.programme.stopRestored(current,programmeTick(run));
                        }else run.programme.stop(current);
                    }
                    else if(run.programme.restoring()){
                        requireRestoredAuthority(hall,run,terminal);
                        run.programme.rebindRestored(current,programmeTick(run));
                    }else if(run.programme.status()==FilmProgramState.Status.GAP){
                        long now=tickNanos;long elapsedNanos=run.observedTick>=0&&gameTick()-run.observedTick<=10&&now>=run.observedNanos
                                ?Math.min(now-run.observedNanos,(gameTick()-run.observedTick)*50_000_000L):0;
                        if(run.programme.desiredPaused())run.gapRemainderNanos=0;
                        else elapsedNanos+=run.gapRemainderNanos;
                        long elapsed=run.programme.desiredPaused()?0:elapsedNanos/1_000_000L;
                        run.gapRemainderNanos=run.programme.desiredPaused()?0:elapsedNanos%1_000_000L;
                        run.programme.advanceGap(current,elapsed,programmeTick(run));
                    }else run.programme.observe(current,programmeTick(run));
                }
                execute(run,terminal);
            }catch(RuntimeException failure){
                if(run.historyRestorePending||run.observedHistoryEpoch!=null||run.programme.finished()&&run.programme.pending()==null){forget(hall);manager.dirty();continue;}
                run.programme.reject(reason(failure),verifiedInstance?session(terminal):null);if(verifiedInstance)execute(run,terminal);
            }
            if(run.observedNanos<=tickNanos)run.observedNanos=tickNanos;run.observedTick=gameTick();
            project(hall,run);if(terminal!=null)terminal.setDirty();if(!before.equals(run.programme.save()))manager.dirty();
        }
    }
    private void execute(Run run,CinemaTerminalTile terminal){
        if(run.programme.presentationBlack()&&run.programme.ownsSession()&&run.programme.status()!=FilmProgramState.Status.OVERRIDDEN
                &&(!run.programme.finished()||run.programme.pending()!=null)){
            var hold=CinemaSources.presentationHoldDelegated(terminal,run.programme.owner(),controller(),run.programme.epoch(),true);
            if(hold.error()!=null&&!hold.error().isEmpty())run.programme.reject(reason(error("source_"+hold.error())),session(terminal));
        }
        var command=run.programme.pending();if(command==null)return;
        CinemaSources.Result result;
        if(command instanceof FilmProgramState.Prepare prepare)
            result=CinemaSources.prepareDelegated(terminal,run.programme.owner(),controller(),new CinemaSources.SourceRef(prepare.source(),0),prepare.expectedEpoch(),prepare.evidenceId());
        else if(command instanceof FilmProgramState.Resume resume){
            String power=ProjectionPower.check(manager,terminal,null,resume.expectedEpoch());
            result=power==null?CinemaSources.resumeAtDelegated(terminal,run.programme.owner(),controller(),resume.expectedEpoch(),resume.expectedRevision(),resume.localMs(),resume.remainPaused())
                    :new CinemaSources.Result(power,CinemaSources.snapshot(terminal));
            if(result.error()==null){power=ProjectionPower.apply(manager,terminal,null,resume.expectedEpoch(),true);
                if(power!=null)result=new CinemaSources.Result(power,result.session());}
        }else {
            result=CinemaSources.haltDelegated(terminal,run.programme.owner(),controller(),command.expectedEpoch());
            // A clip-transition Halt retains output for its black cue. A final Halt powers off.
            if(result.error()==null&&run.programme.finished()){
                String power=ProjectionPower.apply(manager,terminal,null,command.expectedEpoch(),false);
                if(power!=null)result=new CinemaSources.Result(power,result.session());
            }
        }
        run.programme.acknowledge(command.operation(),session(result.session(),terminal.data.paused),result.error(),programmeTick(run));
        if(command instanceof FilmProgramState.Prepare && result.error()==null){
            terminal.programmeManaged(true);
            // Every new occurrence is hidden until its exact Ready+Resume ACK,
            // including the first clip.  Gap black remains the same gate.
            terminal.programmePresentationHold(true);
        }
        if(command instanceof FilmProgramState.Prepare prepare&&result.error()==null
                &&run.programme.ownsSession()&&Objects.equals(run.programme.epoch(),terminal.mediaEpoch())
                &&Objects.equals(prepare.evidenceId(),terminal.sourceEvidenceId()))run.boundEvidence=prepare.evidenceId();
        if(command instanceof FilmProgramState.Halt&&run.programme.status()==FilmProgramState.Status.GAP&&run.programme.gapHaltAcknowledged()){run.observedNanos=System.nanoTime();run.observedTick=gameTick();run.gapRemainderNanos=0;}
        if(command instanceof FilmProgramState.Resume && !run.programme.presentationBlack()
                && (run.programme.status()==FilmProgramState.Status.PLAYING||run.programme.status()==FilmProgramState.Status.PAUSED)){
            var reveal=CinemaSources.presentationHoldDelegated(terminal,run.programme.owner(),controller(),run.programme.epoch(),false);
            if(reveal.error()!=null&&!reveal.error().isEmpty())run.programme.reject(reason(error("source_"+reveal.error())),session(terminal));
        }
        terminal.setDirty();
    }
    private void restoreRuns(){
        for(var entry:List.copyOf(restored.entrySet())){
            String hall=entry.getKey();Stored stored=entry.getValue();restored.remove(hall);
            try{manager.state.hall(hall);var snapshot=manager.library.catalog().snapshot(stored.state().snapshotId());
                var programme=FilmProgramState.restore(snapshot,stored.state(),gameTick(),PREPARE_TIMEOUT_TICKS);
                Run run=new Run(stored.terminal(),stored.identity(),programme);
                run.stopRequested=stored.stopRequested();
                run.displayRetained=stored.displayRetained()||!programme.finished();
                run.historyRestorePending=programme.finished()&&programme.ownsSession()&&programme.status()!=FilmProgramState.Status.OVERRIDDEN;
                runs.put(hall,run);
            }catch(RuntimeException failure){try{var h=manager.state.hall(hall);h.issue="mycinema.catalog.saved_programme_invalid";h.transport=ManagerState.Transport.STOPPED;}catch(RuntimeException ignored){}manager.notice("mycinema.catalog.saved_programme_invalid");}
        }
    }
    private boolean reentered(Run run,CinemaTerminalTile terminal){
        return run.boundTerminal!=null&&run.boundTerminal!=terminal&&run.boundTerminal.isRemoved()
                &&run.boundTerminal.getLevel()==level()&&run.boundTerminal.getBlockPos().equals(terminal.getBlockPos())
                &&run.terminal.equals(terminal.identity())&&run.terminal.equals(run.boundTerminal.identity())
                &&Objects.equals(run.observedHistoryEpoch==null?run.programme.epoch():run.observedHistoryEpoch,run.boundTerminal.mediaEpoch())
                &&Objects.equals(run.programme.boundSource(),run.boundTerminal.data.getUrl())
                &&run.programme.boundSourceType()==run.boundTerminal.data.getSource()
                &&Objects.equals(run.boundEvidence,run.boundTerminal.sourceEvidenceId());
    }
    /** Only a cold restore or a removed, replaced block entity may acquire a disk-load epoch. */
    private void restoreReentered(String hall,Run run,CinemaTerminalTile terminal){
        if(run.boundTerminal==terminal)return;
        if(run.boundTerminal==null?!run.programme.restoring():!reentered(run,terminal))throw error("source_stale_session");
        requireRestoredAuthority(hall,run,terminal);
        if(run.boundTerminal!=null)run.programme=FilmProgramState.restore(run.programme.snapshot(),run.programme.save(),programmeTick(run),PREPARE_TIMEOUT_TICKS);
        run.boundTerminal=terminal;run.boundEvidence=terminal.sourceEvidenceId();run.seekGeneration++;resetObservation(run);
    }
    private void requireRestoredAuthority(String hall,Run run,CinemaTerminalTile terminal){
        if(!run.terminal.equals(terminal.identity())||manager.programmeTerminal(hall)!=terminal)throw error("source_stale_session");
        permission(run,terminal);
        var grant=terminal.automationGrant();var online=level().getServer().getPlayerList().getPlayer(manager.owner);
        requireSourcePolicies(run.programme.snapshot().film(),hall,terminal,online==null?grant.mode():online.gameMode.getGameModeForPlayer());
        if(!run.programme.ownsSession()||!CinemaSources.controlledBy(terminal,controller())||!matchesRestoredSource(run,terminal))throw error("source_stale_session");
    }
    /** Finished summaries are verified history; observing a new disk epoch grants no execution. */
    private boolean matchesHistoryRestore(Run run,CinemaTerminalTile terminal){
        var programme=run.programme;
        if(!programme.finished()||programme.status()==FilmProgramState.Status.OVERRIDDEN||!programme.ownsSession()
                ||terminal.mediaEpoch()==null||manager.owner==null||!manager.owner.equals(programme.owner())
                ||!terminal.ownedBy(manager.owner)||!CinemaSources.controlledBy(terminal,controller()))return false;
        var grant=terminal.automationGrant();
        if(grant==null||!grant.matches(terminal.identity(),manager.identity,manager.owner,manager.getBlockPos()))return false;
        manager.library.validateSnapshot(programme.snapshot(),null);
        return matchesRestoredSource(run,terminal);
    }
    private boolean matchesRestoredSource(Run run,CinemaTerminalTile terminal){
        var programme=run.programme;var saved=programme.save();
        if(terminal.mediaEpoch()==null||run.boundEvidence!=null&&!run.boundEvidence.equals(terminal.sourceEvidenceId()))return false;
        if(saved.boundSourceType()!=0||terminal.data.getSource()!=0||!Objects.equals(saved.boundSource(),terminal.data.getUrl()))return false;
        for(var segment:programme.snapshot().film().segments()){
            if(!segment.sourceKey().equals(saved.boundSource())||segment.observation()==null)continue;
            var origin=segment.observation().provenance();UUID evidenceId=UUID.fromString(origin.evidenceId());
            var evidence=CinemaSourceEvidence.get(level()).find(evidenceId);
            if(origin.authorId().equals(manager.owner.toString())&&origin.controllerId().equals(manager.identity.toString())
                    &&origin.sourceKey().equals(segment.sourceKey())&&evidenceId.equals(terminal.sourceEvidenceId())
                    &&evidence!=null&&evidence.matches(manager.owner,segment.sourceKey(),0)
                    &&evidence.terminal().toString().equals(origin.terminalIdentity())
                    &&evidence.durationMs()==segment.durationMs().orElseThrow())return true;
        }
        return false;
    }
    private boolean scheduledShowMissing(Run run){
        String id=run.programme.scheduledShowId();if(id==null)return false;
        try{manager.state.show(id);return false;}catch(RuntimeException missing){return true;}
    }
    private boolean scheduledStopRequested(Run run){
        String id=run.programme.scheduledShowId();
        return id!=null&&manager.state.show(id).status==ManagerState.Status.STOP_PENDING;
    }
    private void project(String id,Run run){
        ManagerState.Hall hall;try{hall=manager.state.hall(id);}catch(RuntimeException removed){return;}
        var programme=run.programme;
        if(programme.status()==FilmProgramState.Status.OVERRIDDEN){
            hall.programme=null;
            hall.current="mycinema.catalog.external_source";hall.issue="mycinema.catalog.external_takeover";
            if(programme.scheduledShowId()!=null)try{var show=manager.state.show(programme.scheduledShowId());
                if(ManagerState.active(show)){show.status=ManagerState.Status.OVERRIDDEN;show.outcome="mycinema.catalog.external_takeover";manager.state.revision++;}}
                catch(RuntimeException missing){}
            return;
        }
        String priorDevice=hall.deviceState;
        hall.current=programme.snapshot().film().title();
        hall.durationMs=programme.snapshot().totalDurationMs();hall.playheadMs=programme.globalTimeMs();
        hall.programme=view(run);
        hall.deviceState="mycinema.catalog.programme_"+programme.status().name().toLowerCase(Locale.ROOT);
        hall.issue=programme.failure().isEmpty()?"":"mycinema.catalog."+programme.failure();
        if(!hall.deviceState.equals(priorDevice)){
            if(programme.status()==FilmProgramState.Status.PLAYING){hall.open=true;ManagerState.applyStage(hall,ManagerState.LightPreset.FEATURE);}
            else if(programme.status()==FilmProgramState.Status.PAUSED)ManagerState.applyStage(hall,ManagerState.LightPreset.PAUSE);
            else if(programme.status()==FilmProgramState.Status.COMPLETED||programme.status()==FilmProgramState.Status.STOPPED)ManagerState.applyStage(hall,ManagerState.LightPreset.EXIT);
        }
        hall.transport=programme.status()==FilmProgramState.Status.PLAYING?ManagerState.Transport.PLAYING:
                programme.status()==FilmProgramState.Status.PAUSED||programme.status()==FilmProgramState.Status.PREPARING||programme.status()==FilmProgramState.Status.READY||programme.status()==FilmProgramState.Status.GAP?ManagerState.Transport.PAUSED:ManagerState.Transport.STOPPED;
        if(programme.scheduledShowId()==null)return;
        ManagerState.Show show;try{show=manager.state.show(programme.scheduledShowId());}catch(RuntimeException deleted){return;}
        if(show.status==ManagerState.Status.OVERRIDDEN||show.status==ManagerState.Status.CANCELLED)return;
        ManagerState.Status status=switch(programme.status()){
            case PREPARING,READY,GAP->ManagerState.Status.PREPARING;
            case PLAYING,PAUSED->ManagerState.Status.RUNNING;
            case COMPLETED->ManagerState.Status.COMPLETED;
            case FAILED->ManagerState.Status.FAILED;
            case STOPPED->ManagerState.Status.CANCELLED;
            case OVERRIDDEN->ManagerState.Status.OVERRIDDEN;
        };
        if(show.status==ManagerState.Status.STOP_PENDING&&!programme.finished())return;
        long now=System.currentTimeMillis();
        String outcome=hall.issue.isEmpty()?hall.deviceState:hall.issue;
        if(!programme.finished()&&now>show.end){hall.issue="mycinema.catalog.programme_delayed";outcome=hall.issue;}
        if(status==ManagerState.Status.COMPLETED){
            // Keep the completion-time fact stable: a finished programme cannot become late later.
            outcome=show.status==status?show.outcome:now>show.end?"mycinema.catalog.programme_completed_late":hall.deviceState;
        }
        boolean firstPlaying=show.startedAt<=0&&programme.status()==FilmProgramState.Status.PLAYING;
        if(show.status!=status||!Objects.equals(show.outcome,outcome)||firstPlaying){
            if(firstPlaying)show.startedAt=now;
            show.status=status;show.outcome=outcome;manager.state.revision++;manager.dirty();
        }
    }
    public void describeSeek(ServerPlayer actor,ManagerState.Hall hall){
        Run run=runs.get(hall.id);if(run==null)return;
        hall.programme=view(run);hall.durationMs=run.programme.snapshot().totalDurationMs();hall.playheadMs=run.programme.globalTimeMs();
        hall.seekSession=run.identity.toString();hall.seekPaused=run.programme.desiredPaused();hall.seekLive=false;
        var session=seekSession(actor,hall.id,run);hall.seekReason=session==null?"unavailable":"ready";
        hall.seekToken=session==null?"":tickets.issue(session,gameTick());
    }
    private SeekTickets.Session seekSession(ServerPlayer actor,String hall,Run run){
        if(!manager.controlAllowed(actor)||run.programme.finished()||run.programme.restoring()||run.programme.pending()!=null||run.programme.status()==FilmProgramState.Status.GAP)return null;
        try{var terminal=manager.catalogTerminal(actor,hall);permission(run,terminal);var current=CinemaSources.snapshot(terminal);
            if(current==null||!Objects.equals(current.epoch(),run.programme.epoch())||!current.ready()||!current.metadataReady()||!current.failure().isEmpty())return null;
            if(run.boundTerminal!=terminal||run.unavailableSince>=0)return null;
            return new SeekTickets.Session(actor.getUUID(),hall,terminal.identity(),run,run.identity+":"+run.seekGeneration+":"+run.programme.segmentIndex()+":"+run.programme.operationRevision()+":"+current.epoch(),current.revision(),run.programme.snapshot().film().segments().get(run.programme.segmentIndex()).durationMs().orElseThrow(),false,run.programme.desiredPaused());
        }catch(RuntimeException unavailable){return null;}
    }
    public void seek(ServerPlayer actor,String hall,String ticket,long globalMs){
        Run run=runs.get(hall);if(run==null||!tickets.consume(ticket,seekSession(actor,hall,run),gameTick(),globalMs))throw error("source_stale_session");
        var terminal=manager.catalogTerminal(actor,hall);run.programme.seekCurrent(globalMs,session(terminal),programmeTick(run));execute(run,terminal);
        retainProgrammeClaim(hall);project(hall,run);manager.notice("mycinema.seek.complete");
    }
    private CinemaProgramme.View view(Run run){
        var programme=run.programme;var film=programme.snapshot().film();List<CinemaProgramme.Clip> clips=new ArrayList<>();
        for(int i=0;i<film.segments().size();i++)clips.add(new CinemaProgramme.Clip(i,Integer.toString(i+1),film.segments().get(i).durationMs().orElseThrow()));
        return new CinemaProgramme.View(1,run.identity,programme.operationRevision(),film.title(),clips,
                programme.segmentIndex(),programme.transitionTargetIndex(),programme.localTimeMs(),film.segments().get(programme.segmentIndex()).durationMs().orElseThrow(),
                programme.snapshot().totalDurationMs(),programme.status().name(),programme.desiredPaused(),programme.failure(),programme.snapshot().transitionGapMs(),programme.remainingGapMs());
    }
    public boolean presentationEnabled(CinemaTerminalTile terminal){
        return runs.values().stream().anyMatch(run->run.terminal.equals(terminal.identity())&&!run.programme.finished());
    }
    public CinemaProgramme.View terminalView(CinemaTerminalTile terminal){
        for(var entry:runs.entrySet()){
            Run run=entry.getValue();if(!run.terminal.equals(terminal.identity())||run.boundTerminal!=terminal||run.historyRestorePending||run.programme.status()==FilmProgramState.Status.OVERRIDDEN)continue;
            try{if(terminal(entry.getKey(),run)==terminal&&CinemaSources.controlledBy(terminal,controller()))return view(run);}catch(RuntimeException ignored){}
        }
        return null;
    }
    /** Direct TMS operation is a separate, local, actual-owner entry point; it never relaxes manager controls. */
    public String terminalControl(ServerPlayer actor,CinemaTerminalTile terminal,CinemaProgramme.Request request){
        try{
            level();if(actor==null||actor.level()!=level()||manager.owner==null||!manager.owner.equals(actor.getUUID())
                    ||!terminal.ownedBy(actor.getUUID())||!terminal.canControl(actor)||!manager.permits(actor,Double.POSITIVE_INFINITY)
                    ||!CinemaPermissions.canBindRemote(actor)||!CinemaPermissions.canInteractRemote(actor))throw error("permission");
            var hall=manager.state.halls.stream().filter(h->h.terminalIdentity.equals(terminal.identity().toString())).findFirst().orElseThrow(()->error("hall_missing"));
            if(manager.programmeTerminal(hall.id)!=terminal)throw error("source_stale_session");
            controlRun(hall.id,runs.get(hall.id),terminal,actor,true,request);return null;
        }catch(RuntimeException failure){return reason(failure);}
    }
    public void control(ServerPlayer actor,String hall,CinemaProgramme.Request request){
        level();Run run=runs.get(hall);
        if(request!=null&&request.action()==CinemaProgramme.Action.STOP&&!terminalChunkLoaded(hall)){
            requireRequest(run,request);
            String pending=stop(hall,actor);if(pending!=null)throw new IllegalArgumentException(pending);return;
        }
        var terminal=manager.catalogTerminal(actor,hall);controlRun(hall,run,terminal,actor,false,request);
    }
    /**
     * An explicit current user request may close a verified cold historical output. Observation
     * alone never grants execution, never changes the saved programme epoch, and never enables media.
     */
    private String stopFinished(String hall,Run run,CinemaTerminalTile terminal,ServerPlayer actor,boolean terminalEntry){
        if(terminal.mediaEpoch()==null)return "mycinema.catalog.source_stale_session";
        UUID expected=run.programme.epoch();
        if(run.historyRestorePending||run.observedHistoryEpoch!=null){
            if(actor==null||actor.level()!=level()||manager.owner==null||!manager.owner.equals(actor.getUUID())
                    ||!terminal.ownedBy(actor.getUUID())||!CinemaPermissions.canBindRemote(actor)||!CinemaPermissions.canInteractRemote(actor))
                return "mycinema.catalog.source_permission";
            if(terminalEntry){
                if(!terminal.canControl(actor)||!manager.permits(actor,Double.POSITIVE_INFINITY))return "mycinema.catalog.source_permission";
            }else if(manager.catalogTerminal(actor,hall)!=terminal)return "mycinema.catalog.source_stale_session";
            if(run.historyRestorePending||run.observedHistoryEpoch==null
                    ||!run.observedHistoryEpoch.equals(terminal.mediaEpoch())||!matchesHistoryRestore(run,terminal))
                return "mycinema.catalog.source_stale_session";
            // The user's current per-block protection must pass too; the delegated helper
            // below then rechecks ownership, identities, reciprocity, grant and exact epoch.
            for(var pos:terminal.projectors().keySet()){
                if(!level().hasChunkAt(pos)||!(level().getBlockEntity(pos) instanceof local.cinemamanager.projection.CinemaProjectorTile member)
                        ||!member.allows(actor))return "mycinema.catalog.source_permission";
            }
            expected=terminal.mediaEpoch();
        }
        String power=ProjectionPower.apply(manager,terminal,null,expected,false);
        if(power==null&&run.displayRetained){run.displayRetained=false;manager.dirty();}
        return power==null?null:"mycinema.catalog."+power;
    }
    /** Transporting this same programme is not an external source takeover. */
    private void retainProgrammeClaim(String hall){manager.state.hall(hall).override=true;manager.state.revision++;}
    private static void requireRequest(Run run,CinemaProgramme.Request request){
        if(run==null||request==null||!run.identity.equals(request.run())||run.programme.operationRevision()!=request.operationRevision()
                ||run.programme.segmentIndex()!=request.currentIndex())throw error("stale_programme");
    }
    private void controlRun(String hall,Run run,CinemaTerminalTile terminal,ServerPlayer actor,boolean terminalEntry,CinemaProgramme.Request request){
        requireRequest(run,request);
        if(!run.terminal.equals(terminal.identity()))throw error("source_stale_session");
        continueLoaded(run);
        if(!run.programme.finished()&&request.action()==CinemaProgramme.Action.STOP
                &&(run.boundTerminal!=terminal||run.programme.restoring())){
            String pending=stopActive(hall,run,terminal);if(pending!=null)throw new IllegalArgumentException(pending);return;
        }
        if(!run.programme.finished()&&run.boundTerminal!=terminal){
            restoreReentered(hall,run,terminal);
        }
        if(run.programme.restoring()||run.historyRestorePending)throw error("source_stale_session");
        if(request.action()!=CinemaProgramme.Action.STOP)permission(run,terminal);
        if(request.action()==CinemaProgramme.Action.STOP&&run.programme.finished()){
            String power=stopFinished(hall,run,terminal,actor,terminalEntry);
            if(power!=null)throw new IllegalArgumentException(power);
        }
        // Finished output requests must reject a lost/changed epoch before constructing a model session.
        var current=session(terminal);
        switch(request.action()){
            case PLAY->run.programme.pause(false,current,programmeTick(run));
            case PAUSE->run.programme.pause(true,current,programmeTick(run));
            case STOP->{run.programme.stop(current);run.displayRetained=false;}
            case SEEK_CURRENT->run.programme.seekCurrent(request.value(),current,programmeTick(run));
            case PREVIOUS->run.programme.previous(current,programmeTick(run));
            case NEXT->run.programme.next(current,programmeTick(run));
            case CHOOSE->{if(request.value()<0||request.value()>=run.programme.snapshot().film().segments().size())throw error("segment_invalid");run.programme.selectSegment((int)request.value(),current,programmeTick(run));}
        }
        run.observedNanos=System.nanoTime();run.observedTick=gameTick();run.gapRemainderNanos=0;execute(run,terminal);retainProgrammeClaim(hall);project(hall,run);manager.dirty();
    }
    public void save(CompoundTag tag){
        List<Stored> saved=new ArrayList<>(restored.values());for(var entry:runs.entrySet())saved.add(new Stored(entry.getKey(),entry.getValue().terminal,entry.getValue().identity,entry.getValue().programme.save(),entry.getValue().stopRequested,entry.getValue().displayRetained));
        tag.put("film_programmes",CatalogStorage.write(JSON.toJson(saved)));
        tag.put("local_programme_consents",CatalogStorage.write(localConsents.save()));
        if(invalidConsentBackup!=null)tag.put("invalid_local_programme_consents",invalidConsentBackup.copy());
        if(invalidBackup!=null)tag.put("invalid_film_programmes",invalidBackup.copy());
    }
    public void load(CompoundTag tag){
        for(var hall:manager.state.halls)hall.programme=null;
        localConsents=new LocalProgrammeConsents();invalidConsentBackup=tag.contains("invalid_local_programme_consents")?tag.get("invalid_local_programme_consents").copy():null;
        try{localConsents=LocalProgrammeConsents.load(CatalogStorage.read(tag,"local_programme_consents"));}
        catch(RuntimeException malformed){invalidConsentBackup=tag.contains("local_programme_consents")?tag.get("local_programme_consents").copy():null;manager.state.notice="mycinema.catalog.local_consent_invalid";}
        runs.clear();restored.clear();invalidBackup=tag.contains("invalid_film_programmes")?tag.get("invalid_film_programmes").copy():null;try{
        String json=CatalogStorage.read(tag,"film_programmes");if(json.isEmpty())return;Stored[] saved=JSON.fromJson(json,Stored[].class);
        if(saved==null||saved.length>ManagerState.MAX_HALLS)throw error("saved_programme_invalid");
        for(Stored s:saved){if(s==null||s.hall()==null||s.terminal()==null||s.identity()==null||s.state()==null||restored.putIfAbsent(s.hall(),s)!=null)throw error("saved_programme_invalid");UUID.fromString(s.hall());}
    }catch(RuntimeException invalid){restored.clear();invalidBackup=tag.contains("film_programmes")?tag.get("film_programmes").copy():null;manager.state.notice="mycinema.catalog.saved_programme_invalid";}}
    private static FilmProgramState.Session session(CinemaTerminalTile terminal){return session(CinemaSources.snapshot(terminal),terminal.data.paused);}
    public static FilmProgramState.Session session(CinemaSources.SessionView view,boolean pausedIntent){
        if(view==null)return null;
        var playback=switch(view.status()){case PLAYING->FilmProgramState.Playback.PLAYING;case PAUSED->FilmProgramState.Playback.PAUSED;case STOPPED->FilmProgramState.Playback.STOPPED;case ENDED->FilmProgramState.Playback.ENDED;default->FilmProgramState.Playback.WAITING;};
        return new FilmProgramState.Session(view.epoch(),view.url()==null?"":view.url(),view.source(),view.revision(),playback,view.timeMs(),view.durationMs(),view.live(),view.metadataReady(),view.evidenceId(),view.ready(),view.failure(),pausedIntent);
    }
    private static String reason(RuntimeException failure){String key=failure.getMessage();if(key!=null&&key.startsWith("mycinema.catalog."))key=key.substring("mycinema.catalog.".length());return key!=null&&key.matches("[a-z_]{1,80}")?key:"unavailable";}
    private static String key(RuntimeException failure){return "mycinema.catalog."+reason(failure);}
}
