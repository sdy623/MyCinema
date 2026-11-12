package local.cinemamanager;

import local.cinemamanager.model.ManagerState;
import local.cinemamanager.model.ManagerState.*;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.automation.CinemaAutomation;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Authorized orchestration only. WaterFrames retains the sole decoder/audio/clock ownership. */
public final class ManagerTile extends OwnedTile {
    public static final int MANAGEMENT_RANGE = 128;
    public ManagerState state = new ManagerState();
    public final local.cinemamanager.library.FilmLibrary library=new local.cinemamanager.library.FilmLibrary(this);
    public final local.cinemamanager.library.FilmPlayback playback=new local.cinemamanager.library.FilmPlayback(this);
    private long snapshotSequence;
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private final local.cinemamanager.control.SeekTickets seekTickets = new local.cinemamanager.control.SeekTickets();
    private final Map<org.watermedia.api.player.ServerMediaPlayer,String> seekSessions = new IdentityHashMap<>();
    private String invalidStateBackup = "";
    public ManagerTile(BlockPos p, BlockState s) { super(CinemaManager.MANAGER_TILE.get(), p, s); }
    @Override public boolean onlyOpCanSetNbt(){return true;}
    public void initializePlacement(UUID owner){this.owner=owner;identity=UUID.randomUUID();library.reset();playback.clearConsents();}
    public CinemaTerminalTile programmeTerminal(String hallId){return enrolledTerminal(state.hall(hallId));}
    public CinemaTerminalTile catalogTerminal(ServerPlayer actor,String hallId){if(!controlAllowed(actor))throw new IllegalArgumentException("mycinema.catalog.permission");return terminal(state.hall(hallId),actor);}
    public boolean theaterSourceControlAllowed(ServerPlayer actor,UUID terminalId){if(!controlAllowed(actor))return false;try{var hall=state.halls.stream().filter(h->h.terminalIdentity.equals(terminalId.toString())).findFirst().orElseThrow();return terminal(hall,actor).identity().equals(terminalId);}catch(RuntimeException denied){return false;}}
    public boolean theaterProgrammePresentationEnabled(CinemaTerminalTile terminal){return playback.presentationEnabled(terminal);}
    public local.cinemamanager.theater.CinemaProgramme.View theaterProgrammeView(CinemaTerminalTile terminal){return playback.terminalView(terminal);}
    public String theaterProgrammeControl(ServerPlayer actor,CinemaTerminalTile terminal,local.cinemamanager.theater.CinemaProgramme.Request request){return playback.terminalControl(actor,terminal,request);}
    public boolean acceptRequest(ServerPlayer player) {
        long now = level.getGameTime(), last = lastRequest.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now - last < 2) return false;
        lastRequest.put(player.getUUID(), now);
        if (lastRequest.size() > 64) lastRequest.entrySet().removeIf(e -> now - e.getValue() > 200);
        return true;
    }
    public Hall enroll(ServerPlayer actor, CinemaTerminalTile terminal, boolean localDevice) {
        if (!(localDevice?permits(actor,Double.POSITIVE_INFINITY):(permits(actor,8)||TabletItem.boundTo(actor,this)&&permits(actor,Double.POSITIVE_INFINITY))) || !terminal.allows(actor) || (localDevice && !terminal.canControl(actor)) || !CinemaPermissions.canBindRemote(actor))
            throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m000"));
        if (terminal.getLevel() != level)
            throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m001"));
        for (Hall h : state.halls) if (h.terminalPos == terminal.getBlockPos().asLong()) {
            if (!h.terminalIdentity.equals(terminal.identity().toString())) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m002"));
            return h;
        }
        if (state.halls.size() >= ManagerState.MAX_HALLS) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m003"));
        if (!EnrollmentRegistry.get((ServerLevel)level).claim(terminal.identity(), identity)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m004"));
        Hall hall = new Hall(); hall.name = local.cinemamanager.MessageText.key("message.m104")+" "+(state.halls.size() + 1);
        hall.terminalPos = terminal.getBlockPos().asLong(); hall.terminalIdentity = terminal.identity().toString();
        state.halls.add(hall); notice(local.cinemamanager.MessageText.key("message.m006") + hall.name + local.cinemamanager.MessageText.key("message.m007"));
        return hall;
    }
    public void nearest(ServerPlayer actor) {
        List<CinemaTerminalTile> candidates = new ArrayList<>();
        int r = MANAGEMENT_RANGE;
        for (int x = (worldPosition.getX()-r)>>4; x <= (worldPosition.getX()+r)>>4; x++)
            for (int z = (worldPosition.getZ()-r)>>4; z <= (worldPosition.getZ()+r)>>4; z++) {
                if (!level.getChunkSource().hasChunk(x,z)) continue;
                for (var tile : level.getChunk(x,z).getBlockEntities().values()) if (tile instanceof CinemaTerminalTile t && t.allows(actor)
                        && worldPosition.distSqr(t.getBlockPos()) <= r*r && state.halls.stream().noneMatch(h -> h.terminalPos == t.getBlockPos().asLong())) candidates.add(t);
            }
        candidates.sort(Comparator.comparingDouble(t -> t.getBlockPos().distSqr(worldPosition)));
        if (candidates.isEmpty()) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m008"));
        enroll(actor, candidates.getFirst(), false);
    }
    public void bindOutput(ServerPlayer actor, EndpointTile output, String hallId) {
        Hall h = state.hall(hallId);
        if (!permits(actor, Double.POSITIVE_INFINITY) || !output.permits(actor,8) || output.getLevel() != level)
            throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m009"));
        if (output.manager != null && (!output.manager.equals(worldPosition) || !identity.equals(output.managerIdentity)))
            throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m010"));
        h.outputs.removeIf(raw -> { BlockPos p=BlockPos.of(raw); if(!level.hasChunkAt(p))return false; return !(level.getBlockEntity(p) instanceof EndpointTile e) || !worldPosition.equals(e.manager) || !identity.equals(e.managerIdentity) || !h.id.equals(e.hallId); });
        if (!h.outputs.contains(output.getBlockPos().asLong()) && h.outputs.size() >= ManagerState.MAX_OUTPUTS) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m011"));
        for (Hall other : state.halls) other.outputs.remove(output.getBlockPos().asLong());
        h.outputs.add(output.getBlockPos().asLong()); output.manager = worldPosition; output.managerIdentity = identity; output.hallId = h.id; output.dirty();
        notice(local.cinemamanager.MessageText.key("message.m006") + h.name + (((EndpointBlock)output.getBlockState().getBlock()).light ? local.cinemamanager.MessageText.key("message.m012") : local.cinemamanager.MessageText.key("message.m013")));
    }
    private CinemaTerminalTile enrolledTerminal(Hall h) {
        BlockPos p = BlockPos.of(h.terminalPos);
        if (!level.hasChunkAt(p)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m014"));
        if (!(level.getBlockEntity(p) instanceof CinemaTerminalTile t) || !t.identity().toString().equals(h.terminalIdentity)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m015"));
        if (!EnrollmentRegistry.get((ServerLevel)level).owns(t.identity(), identity)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m016"));
        return t;
    }
    private CinemaTerminalTile terminal(Hall h, ServerPlayer actor) {
        CinemaTerminalTile t = enrolledTerminal(h);
        if (actor == null || actor.level() != level || !t.allows(actor) || !CinemaPermissions.canBindRemote(actor) || !CinemaPermissions.canInteractRemote(actor)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m017"));
        return t;
    }
    /** Reads the existing trusted source-zero session only; never opens media or creates a cache. */
    public long publicDuration(Show show) {
        if (level == null || level.isClientSide || show == null) return 0;
        if(show.filmSnapshot!=null&&!show.filmSnapshot.isBlank())return library.duration(show);
        try {
            CinemaTerminalTile t = enrolledTerminal(state.hall(show.hallId));
            String source = local.cinemamanager.media.MediaSource.normalize(show.url);
            var clock = t.clock();
            if (source == null || !source.equals(local.cinemamanager.media.MediaSource.normalize(t.data.getUrl()))
                    || t.data.getSource() != 0 || clock == null || !t.theaterClockCurrent()
                    || !t.playbackAvailable() || !t.data.active || (!clock.playing() && !clock.paused())
                    || clock.snapshot().live() || clock.duration() <= 0) return 0;
            return clock.duration();
        } catch (RuntimeException ignored) { return 0; }
    }
    public UUID theaterAutomationIdentity() { return identity; }
    public UUID theaterAutomationOwner() { return owner; }
    public boolean theaterAutomationEnabled(UUID terminalId) { return (state.schedulerEnabled || playback.enabled(terminalId)) && state.halls.stream().anyMatch(h -> h.terminalIdentity.equals(terminalId.toString())) && level instanceof ServerLevel server && EnrollmentRegistry.get(server).owns(terminalId, identity); }
    public boolean theaterSourcePlaybackEnabled(UUID terminalId){
        return playback.enabled(terminalId)&&state.halls.stream().anyMatch(h->h.terminalIdentity.equals(terminalId.toString()))
                &&level instanceof ServerLevel server&&EnrollmentRegistry.get(server).owns(terminalId,identity);
    }
    public boolean theaterLocalSourcePlaybackAllowed(UUID terminalId,String normalizedSource){
        return theaterSourcePlaybackEnabled(terminalId)&&playback.localSourceAllowed(terminalId,normalizedSource);
    }
    public void authorizeLocalProgramme(ServerPlayer actor,String hallId,String filmId,long filmRevision,boolean confirmed){playback.authorizeLocal(actor,hallId,filmId,filmRevision,confirmed);}
    public void revokeLocalProgramme(ServerPlayer actor,String hallId,boolean confirmed){playback.revokeLocal(actor,hallId,confirmed);}
    public local.cinemamanager.library.HallAuthorization.ClaimReview claimReview(ServerPlayer actor,String hallId){return local.cinemamanager.library.HallAuthorization.review(this,actor,hallId);}
    public void claimUnownedHall(ServerPlayer actor,String hallId,long expected,String fingerprint,boolean confirmed){local.cinemamanager.library.HallAuthorization.claim(this,actor,hallId,expected,fingerprint,confirmed);}
    public void authorizeProgramme(ServerPlayer actor,String hallId,boolean confirmed){
        if(!confirmed)throw new IllegalArgumentException("mycinema.catalog.confirm_required");
        local.cinemamanager.library.HallAuthorization.requireManagerOwner(this,actor,null);
        CinemaTerminalTile terminal=local.cinemamanager.library.HallAuthorization.target(this,actor,hallId);
        local.cinemamanager.library.HallAuthorization.grant(this,actor,terminal);
        notice("mycinema.catalog.programme_authorized");
    }
    public void enableScheduler(ServerPlayer actor, boolean enabled) {
        if (!enabled) { suspend(local.cinemamanager.MessageText.key("message.m018")); state.enable(false); notice(local.cinemamanager.MessageText.key("message.m018")); return; }
        if (owner == null || !owner.equals(actor.getUUID())) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m019"));
        state.enable(true);
        try { for (Hall h : state.halls) { CinemaTerminalTile t = terminal(h,actor); for(Show filmShow:state.shows)if(filmShow.hallId.equals(h.id)&&filmShow.status==Status.PENDING)validateFilmAutomation(actor,t,filmShow); if(!CinemaPermissions.canAutomateTheater(owner,actor.gameMode.getGameModeForPlayer(),t.getDisplayBlock(),"",false)) throw new IllegalArgumentException(h.name+local.cinemamanager.MessageText.key("message.m020")); for(Show show:state.shows) if(show.hallId.equals(h.id)&&show.status==Status.PENDING&&(show.filmSnapshot==null||show.filmSnapshot.isBlank())&&!CinemaPermissions.canAutomateTheater(owner,actor.gameMode.getGameModeForPlayer(),t.getDisplayBlock(),show.url,true)) throw new IllegalArgumentException(h.name+local.cinemamanager.MessageText.key("message.m021")+show.title); var grant=t.automationGrant(); if (grant!=null&&grant.matches(t.identity(),identity,owner,worldPosition)) continue; local.cinemamanager.library.HallAuthorization.grant(this,actor,t); } }
        catch (RuntimeException e) { state.enable(false); throw e; }
        notice(local.cinemamanager.MessageText.key("message.m023"));
    }
    private void validateFilmAutomation(ServerPlayer actor,CinemaTerminalTile terminal,Show show){
        if(show.filmSnapshot==null||show.filmSnapshot.isBlank())return;
        var snapshot=library.snapshot(show);library.validateSnapshot(snapshot,actor);
        playback.requireSourcePolicies(snapshot.film(),show.hallId,terminal,actor.gameMode.getGameModeForPlayer());
    }
    private String automatic(Hall hall, CinemaAutomation.Action action, String url) {
        try {
            CinemaTerminalTile terminal=enrolledTerminal(hall);
            if(action==CinemaAutomation.Action.PLAY_NEW_MEDIA){String power=local.cinemamanager.library.ProjectionPower.check(this,terminal,null,null);if(power!=null)return "mycinema.catalog."+power;}
            String error=CinemaAutomation.execute(terminal,worldPosition,identity,owner,action,url);
            if(error!=null)return error;
            String power=local.cinemamanager.library.ProjectionPower.apply(this,terminal,null,null,action==CinemaAutomation.Action.PLAY_NEW_MEDIA);
            return power==null?null:"mycinema.catalog."+power;
        }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }
    private String transport(Hall h, Scene scene, ServerPlayer actor) {
        try {
            CinemaTerminalTile t = terminal(h, actor);
            if(playback.present(h.id)) {
                if(scene==Scene.PLAY||scene==Scene.PAUSE||scene==Scene.STOP)return "mycinema.catalog.source_stale_session";
                // Closing an auditorium remains an explicit hall-wide emergency action.
                if(scene==Scene.CLOSE)return playback.stop(h.id,actor);
            }
            switch (scene) {
                case PLAY -> {
                    if (!t.data.hasUrl() || !t.playbackAvailable()) return local.cinemamanager.MessageText.key("message.m024");
                    if (CinemaPermissions.useMasterModeRedstone() && t.isPowered()) return local.cinemamanager.MessageText.key("message.m025");
                    String power=local.cinemamanager.library.ProjectionPower.apply(this,t,actor,null,true);
                    if(power!=null)return "mycinema.catalog."+power;
                    t.data.active = true; t.data.paused = false;
                    if (t.clock() != null && (t.clock().stopped() || t.clock().ended())) t.restartClock();
                }
                case PAUSE -> t.data.paused = true;
                case STOP, CLOSE -> {
                    t.data.paused = true; t.resetPosition(); if (t.clock() != null) t.clock().stop();t.setDirty();
                    String power=local.cinemamanager.library.ProjectionPower.apply(this,t,actor,null,false);
                    if(power!=null)return "mycinema.catalog."+power;
                }
                default -> { }
            }
            t.setDirty(); return null;
        } catch (IllegalArgumentException e) { return e.getMessage(); }
    }
    public void media(ServerPlayer actor, Hall h, String url, String title) {
        if (url.isBlank() || url.length() > 2048 || title.length() > 80 || !CinemaPermissions.canSave(actor, url)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m026"));
        CinemaTerminalTile t = terminal(h, actor);
        t.data.playlist.clear(); t.data.entryIndex = 0; t.data.url = url.trim(); t.data.uuid = actor.getUUID();
        t.data.active = true; t.data.paused = true; t.resetPosition(); if (t.clock() != null) t.clock().stop();
        t.cleanClock(); t.sourceReplaced(); t.setDirty();
        playback.forget(h.id); state.takeOver(h); h.transport=Transport.STOPPED; h.current = title.isBlank() ? local.cinemamanager.MessageText.key("message.m027") : title; h.issue = "";
        refreshMediaStatus(h);
        notice(local.cinemamanager.MessageText.key("message.m028"));
    }
    public void scene(ServerPlayer actor, String hallId, Scene scene) {
        List<Hall> selected = hallId.equals("*") ? List.copyOf(state.halls) : List.of(state.hall(hallId));
        int ok = 0; List<String> failures = new ArrayList<>();
        for (Hall h : selected) {
            String error = scene == Scene.AUTO ? null : transport(h, scene, actor);
            if (error == null) { state.manual(h, scene, playback.currentDisplayPending(h.id)); h.issue = ""; ok++; }
            else { h.issue = error; failures.add(h.name + ": " + error); }
        }
        notice(local.cinemamanager.MessageText.key("message.m029") + ok + "/" + selected.size() + (failures.isEmpty() ? local.cinemamanager.MessageText.key("message.m030") : "; " + String.join(" / ", failures)));
    }
    public void removeOutput(BlockPos pos) { boolean changed=false;for(Hall h:state.halls)changed|=h.outputs.remove(pos.asLong());if(changed){state.revision++;dirty();} }
    public void lighting(ServerPlayer actor,String hallId,LightPreset preset) {
        List<Hall> selected=hallId.equals("*")?List.copyOf(state.halls):List.of(state.hall(hallId));int ok=0;
        for(Hall h:selected){String error=transport(h,Scene.LIGHTS_ON,actor);if(error==null){state.manual(h,Scene.LIGHTS_ON,playback.currentDisplayPending(h.id));ManagerState.applyStage(h,preset);h.issue="";ok++;}else h.issue=error;}
        notice(local.cinemamanager.MessageText.key("message.m031")+ManagerState.presetName(preset)+local.cinemamanager.MessageText.key("message.m032")+ok+"/"+selected.size()+local.cinemamanager.MessageText.key("message.m033"));
    }
    public void saveLighting(String hallId,LightPreset preset,CompoundTag args) {
        Hall h=state.hall(hallId);ManagerState.savePreset(h,preset,new int[]{args.getInt("main"),args.getInt("aisle"),args.getInt("exit"),args.getInt("accent")});
        notice(h.name+" “"+ManagerState.presetName(preset)+local.cinemamanager.MessageText.key("message.m034")+(args.getInt("aisle")==0||args.getInt("exit")==0?local.cinemamanager.MessageText.key("message.m035"):""));
    }
    public void schedule(ServerPlayer actor, Hall hall, CompoundTag args) {
        String url = args.getString("url").trim();
        terminal(hall, actor);
        if (!CinemaPermissions.canSave(actor, url)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m036"));
        state.addShow(hall.id, args.getString("title"), url, args.getString("time"), args.getInt("minutes"), System.currentTimeMillis());
        notice(local.cinemamanager.MessageText.key("message.m037"));
    }
    private void schedulePermission(ServerPlayer actor) {
        if (!controlAllowed(actor)) throw new IllegalArgumentException("mycinema.schedule.permission");
    }
    private Show checkedShow(ServerPlayer actor,String showId,long expected) {
        schedulePermission(actor);state.requireRevision(expected);
        return state.show(showId);
    }
    /** The request names a server-owned plan, never supplies an alternative media source or hall. */
    public void loadShow(ServerPlayer actor,String showId,long expected) {
        Show show=checkedShow(actor,showId,expected);
        if(show.filmSnapshot!=null&&!show.filmSnapshot.isBlank()){playback.loadShow(actor,show);return;}
        String source=local.cinemamanager.media.MediaSource.normalize(show.url);
        if(source==null || !CinemaPermissions.canSave(actor,source)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m036"));
        media(actor,state.hall(show.hallId),source,show.title);
        notice("mycinema.schedule.loaded");
    }
    /** Explicit scheduled arming never preloads or starts media, and only releases this hall's override. */
    public void armShow(ServerPlayer actor,String showId,long expected,boolean confirmed) {
        Show show=checkedShow(actor,showId,expected);
        if(show.filmSnapshot!=null&&!show.filmSnapshot.isBlank())library.validateSnapshot(library.snapshot(show),actor);
        if(!confirmed)throw new IllegalArgumentException("mycinema.schedule.confirm_required");
        long now=System.currentTimeMillis();
        if(show.status!=Status.PENDING||show.start<=now)throw new IllegalArgumentException("mycinema.schedule.arm_past");
        if(owner==null||!owner.equals(actor.getUUID()))throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m019"));
        if(state.timezone.isBlank())throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m117"));
        java.time.ZoneId.of(state.timezone);
        Hall selected=state.hall(show.hallId);
        CinemaTerminalTile t=terminal(selected,actor);
        validateFilmAutomation(actor,t,show);
        if((show.filmSnapshot==null||show.filmSnapshot.isBlank())&&!CinemaPermissions.canAutomateTheater(owner,actor.gameMode.getGameModeForPlayer(),t.getDisplayBlock(),show.url,true))
            throw new IllegalArgumentException(selected.name+local.cinemamanager.MessageText.key("message.m021")+show.title);
        var grant=t.automationGrant();
        if(grant==null||!grant.matches(t.identity(),identity,owner,worldPosition)){
            local.cinemamanager.library.HallAuthorization.grant(this,actor,t);
        }
        // All checks precede any control-mode change. Other halls retain their mode and grants.
        state.enable(true);
        state.manual(selected,Scene.AUTO);
        notice("{tr:mycinema.schedule.armed} "+state.format(show.start)+"–"+state.format(show.end));
        org.slf4j.LoggerFactory.getLogger("MyCinema/Schedule").info("arm show={} hall={} scheduledStart={} globalEnabled={} manualOverride={}",show.id,show.hallId,show.start,state.schedulerEnabled,selected.override);
    }
    public void editShow(ServerPlayer actor,String showId,long expected,CompoundTag args) {
        Show show=checkedShow(actor,showId,expected);
        terminal(state.hall(show.hallId),actor);
        String url=args.getString("url").trim();
        if(!CinemaPermissions.canSave(actor,url)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m036"));
        state.editShow(show.id,expected,args.getString("title"),url,args.getString("time"),args.getInt("minutes"),System.currentTimeMillis());
        notice("mycinema.schedule.updated");
    }
    public void deleteShow(ServerPlayer actor,String showId,long expected,boolean confirmed) {
        checkedShow(actor,showId,expected);
        if(!confirmed) throw new IllegalArgumentException("mycinema.schedule.confirm_required");
        state.deleteShow(showId,expected);notice("mycinema.schedule.deleted");
    }
    public void cancelShow(ServerPlayer actor,String showId,long expected,boolean confirmed) {
        checkedShow(actor,showId,expected);
        if(!confirmed) throw new IllegalArgumentException("mycinema.schedule.confirm_required");
        cancelShow(actor,showId);
    }
    public void suspend(String reason) {
        if (level == null || level.isClientSide) return;
        for (Show s : state.shows) if (s.status == Status.PREPARING || s.status == Status.RUNNING || s.status == Status.STOP_PENDING) {
            Hall h = state.hall(s.hallId); String error = s.filmSnapshot!=null&&!s.filmSnapshot.isBlank()?playback.stopScheduled(h.id,s.id):automatic(h, CinemaAutomation.Action.STOP, "");
            s.status = error == null ? Status.CANCELLED : Status.STOP_PENDING; if (error != null&&!ManagerState.filmProgramme(s)) s.end = Math.min(s.end, System.currentTimeMillis()); s.outcome = reason + (error == null ? "" : "; " + error);
            if (!ManagerState.filmProgramme(s)) { if (error == null) ManagerState.apply(h, Scene.STOP); else h.issue = error; }
        }
        notice(reason);
    }
    public boolean pendingAutomaticStop() { return state.shows.stream().anyMatch(s -> s.status==Status.PREPARING || s.status==Status.RUNNING || s.status==Status.STOP_PENDING); }
    public void releaseEnrollments() { if (level instanceof ServerLevel server) EnrollmentRegistry.get(server).releaseAll(identity); }
    public void cancelShow(ServerPlayer actor, String id) {
        Show show=state.shows.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow();
        if (show.status == Status.PREPARING || show.status == Status.RUNNING || show.status == Status.STOP_PENDING) {
            Hall h=state.hall(show.hallId);
            if(ManagerState.filmProgramme(show)){
                String error=playback.stopScheduled(h.id,show.id);if(error!=null)throw new IllegalArgumentException(error);
            }else{String error=transport(h,Scene.STOP,actor);if(error!=null)throw new IllegalArgumentException(error);state.manual(h,Scene.STOP);}
        }
        show.status=Status.CANCELLED; show.outcome=local.cinemamanager.MessageText.key("message.m038"); notice(local.cinemamanager.MessageText.key("message.m039"));
    }
    public void removeHall(String id) { playback.forget(id); Hall h=state.hall(id); EnrollmentRegistry.get((ServerLevel)level).release(UUID.fromString(h.terminalIdentity),identity); state.shows.removeIf(s -> s.hallId.equals(id)); state.halls.remove(h); notice(local.cinemamanager.MessageText.key("message.m040")); }
    public void tick() {
        library.tick(); playback.tick();
        if (level == null || level.isClientSide || level.getGameTime()%20 != 0) return;
        // Revalidates the persisted real-owner grant, current offline permissions and loaded targets; no fake player.
        Map<String,Status> previousStatuses=new HashMap<>();
        for(Show show:state.shows)previousStatuses.put(show.id,show.status);
        long schedulerNow=System.currentTimeMillis();
        boolean changed = state.tick(schedulerNow, new ManagerState.Executor() {
            public String start(Hall h, Show s) { return s.filmSnapshot!=null&&!s.filmSnapshot.isBlank()?playback.startScheduled(h,s):automatic(h, CinemaAutomation.Action.PLAY_NEW_MEDIA, s.url); }
            public boolean preparing(Hall h,Show s){return s.filmSnapshot!=null&&!s.filmSnapshot.isBlank();}
            public String stop(Hall h) { return automatic(h, CinemaAutomation.Action.STOP, ""); }
            public String stop(Hall h,Show s){return s.filmSnapshot!=null&&!s.filmSnapshot.isBlank()?playback.stopScheduled(h.id,s.id):stop(h);}
        });
        for(Show show:state.shows)if(previousStatuses.get(show.id)!=show.status){
            Hall hall=state.hall(show.hallId);
            org.slf4j.LoggerFactory.getLogger("MyCinema/Schedule").info("transition show={} hall={} from={} to={} reason={} now={} start={} end={} enabled={} override={}",show.id,show.hallId,previousStatuses.get(show.id),show.status,reasonCode(show),schedulerNow,show.start,show.end,state.schedulerEnabled,hall.override);
        }
        for(Hall h:state.halls) {
            changed|=refreshMediaStatus(h);
            int admissionCount=0,minSignal=15,maxSignal=0,offline=0;
            for(long raw:h.outputs){BlockPos p=BlockPos.of(raw);if(!level.hasChunkAt(p)||!(level.getBlockEntity(p) instanceof EndpointTile output)||!worldPosition.equals(output.manager)||!identity.equals(output.managerIdentity)||!h.id.equals(output.hallId)){offline++;continue;}
                if(!((EndpointBlock)output.getBlockState().getBlock()).light){int signal=output.getBlockState().getValue(EndpointBlock.POWER);admissionCount++;minSignal=Math.min(minSignal,signal);maxSignal=Math.max(maxSignal,signal);}}
            String admission=admissionCount==0?local.cinemamanager.MessageText.key("message.m049"):admissionCount+local.cinemamanager.MessageText.key("message.m050")+(minSignal==maxSignal?Integer.toString(minSignal):minSignal+"–"+maxSignal);
            if(offline>0)admission+=local.cinemamanager.MessageText.key("message.m051")+offline+local.cinemamanager.MessageText.key("message.m052");
            if(!admission.equals(h.admissionOutputStatus)){h.admissionOutputStatus=admission;changed=true;}
        }
        if (changed) dirty();
    }
    /** Reuse current device facts for both periodic polls and an immediate successful media load response. */
    private boolean refreshMediaStatus(Hall h) {
        String status;long duration=0,time=0;
        try {CinemaTerminalTile t=enrolledTerminal(h);if(!t.playbackAvailable())status=local.cinemamanager.MessageText.key("message.m041");
            else if(!t.data.hasUrl())status=local.cinemamanager.MessageText.key("message.m042");
            else {duration=t.clock()==null?0:t.clock().duration();time=t.clock()==null?0:t.clock().time();
                if(t.clock()!=null&&(t.clock().stopped()||t.clock().ended()))status=local.cinemamanager.MessageText.key("message.m043");else if(t.data.paused)status=local.cinemamanager.MessageText.key("message.m044");else status=duration>0?local.cinemamanager.MessageText.key("message.m045"):local.cinemamanager.MessageText.key("message.m046");
                if(state.shows.stream().anyMatch(s->s.hallId.equals(h.id)&&s.status==Status.RUNNING&&s.startedAt-s.start>5000))status=local.cinemamanager.MessageText.key("message.m047")+status;}
        }catch(IllegalArgumentException e){status=local.cinemamanager.MessageText.key("message.m048");}
        if(playback.present(h.id)){status=h.deviceState;duration=h.durationMs;time=h.playheadMs;}
        if(status.equals(h.deviceState)&&duration==h.durationMs&&time/1000==h.playheadMs/1000)return false;
        h.deviceState=status;h.durationMs=Math.max(0,duration);h.playheadMs=Math.max(0,time);return true;
    }
    /** Logs only bounded reason codes; never URL, owner, user-entered title, path, or remote exception text. */
    private static String reasonCode(Show s){
        if(s.status!=Status.MISSED)return s.status.name();
        if(s.outcome.equals("mycinema.schedule.programme_conflict"))return "HALL_PROGRAMME_ACTIVE";
        for(String[] item:List.of(new String[]{"message.m127","SCHEDULER_DISABLED"},new String[]{"message.m128","MANUAL_OVERRIDE"},new String[]{"message.m129","STOP_PENDING"},new String[]{"message.m130","START_WINDOW_EXPIRED"}))
            if(s.outcome.equals(MessageText.key(item[0])))return item[1];
        return "MISSED_UNKNOWN";
    }
    public boolean controlAllowed(ServerPlayer actor) {
        return actor != null && actor.level() == level && (permits(actor,8)
                || TabletItem.boundTo(actor,this) && permits(actor,Double.POSITIVE_INFINITY));
    }
    private local.cinemamanager.control.SeekTickets.Session seekSession(ServerPlayer actor, Hall hall, CinemaTerminalTile terminal) {
        var clock=terminal.clock();
        if(clock==null || !terminal.theaterClockCurrent() || !terminal.playbackAvailable()
                || !terminal.data.active || (!clock.playing() && !clock.paused())) return null;
        return new local.cinemamanager.control.SeekTickets.Session(actor.getUUID(),hall.id,terminal.identity(),clock,
                terminal.data.getUrl()+"#"+terminal.data.getSource(),clock.revision(),clock.duration(),clock.snapshot().live(),terminal.data.paused);
    }
    /** Adds fresh, actor-specific seek leases to a response copy, never to persistent manager data. */
    public void describeSeek(ServerPlayer actor, ManagerState copy, boolean readOnly) {
        copy.snapshotSequence=++snapshotSequence;
        seekSessions.keySet().removeIf(clock -> state.halls.stream().noneMatch(h -> {
            BlockPos p=BlockPos.of(h.terminalPos);return level.hasChunkAt(p) && level.getBlockEntity(p) instanceof CinemaTerminalTile t && t.clock()==clock;
        }));
        for(Hall h:copy.halls) {
            h.seekToken="";h.seekSession="";h.seekReason="unavailable";h.seekIssuedTick=level.getGameTime();
            h.seekLive=false;h.seekPaused=true;
            if(readOnly || !controlAllowed(actor)){h.seekReason="permission";continue;}
            try {
                CinemaTerminalTile t=terminal(state.hall(h.id),actor);
                if(playback.present(h.id)){playback.describeSeek(actor,h);continue;}
                h.programme=null;
                var session=seekSession(actor,h,t);
                if(session==null)continue;
                h.durationMs=Math.max(0,session.duration());h.playheadMs=Math.max(0,t.clock().time());
                h.seekLive=session.live();h.seekPaused=session.paused();
                h.seekSession=seekSessions.computeIfAbsent(t.clock(),k->UUID.randomUUID().toString());
                h.seekReason=session.duration()<=0?"unknown_duration":session.live()?"live":"ready";
                h.seekToken=seekTickets.issue(session,level.getGameTime());
            } catch(IllegalArgumentException ignored){h.seekReason="permission";}
        }
    }
    /** Only the enrolled TMS's existing authoritative clock is changed. */
    public void seek(ServerPlayer actor,String hallId,String ticket,long target) {
        if(!controlAllowed(actor))throw new IllegalArgumentException("mycinema.seek.permission");
        Hall h=state.hall(hallId);if(playback.present(hallId)){playback.seek(actor,hallId,ticket,target);return;}CinemaTerminalTile terminal=terminal(h,actor);
        var session=seekSession(actor,h,terminal);
        if(!seekTickets.consume(ticket,session,level.getGameTime(),target))
            throw new IllegalArgumentException("mycinema.seek.stale");
        if(!terminal.clock().seek(target))throw new IllegalArgumentException("mycinema.seek.unavailable");
        // Do not pause/restart/recreate: preserve both transport state and the single shared clock.
        terminal.setDirty();state.takeOver(h);h.issue="";
        h.playheadMs=terminal.clock().time();h.durationMs=terminal.clock().duration();
        h.transport=terminal.data.paused?Transport.PAUSED:Transport.PLAYING;
        notice("mycinema.seek.complete");
    }
    public void emergencyAccess(ServerPlayer actor,boolean active,long expected,boolean confirmed) {
        local.cinemamanager.access.EmergencyAccess.change(actor,this,active,expected,confirmed);
    }
    public void describeEmergency(ServerPlayer actor,ManagerState copy,boolean readOnly) {
        local.cinemamanager.access.EmergencyAccess.describe(this,copy,actor,readOnly);
    }
    public void notice(String text) { state.notice = text.length() > 500 ? text.substring(0,500) : text; state.revision++; dirty(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider regs) { super.saveAdditional(tag,regs); library.save(tag); playback.save(tag); tag.put("manager_state_chunks",local.cinemamanager.library.CatalogStorage.write(StateCodec.encode(state))); if (!invalidStateBackup.isBlank()) tag.put("invalid_state_backup_chunks",local.cinemamanager.library.CatalogStorage.write(invalidStateBackup)); }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider regs) {
        super.loadAdditional(tag,regs);
        try{invalidStateBackup = tag.contains("invalid_state_backup_chunks")?local.cinemamanager.library.CatalogStorage.read(tag,"invalid_state_backup_chunks"):tag.getString("invalid_state_backup");}catch(RuntimeException invalidBackup){this.invalidStateBackup="Invalid prior recovery backup";}
        String saved="";
        if (tag.contains("manager_state_chunks")||tag.contains("manager_state")) try { saved=tag.contains("manager_state_chunks")?local.cinemamanager.library.CatalogStorage.read(tag,"manager_state_chunks"):tag.getString("manager_state");state = StateCodec.decode(saved); }
        catch (RuntimeException e) { invalidStateBackup = saved; state = new ManagerState(); state.notice = local.cinemamanager.MessageText.key("message.m053"); }
        library.load(tag);playback.load(tag);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider regs) {
        CompoundTag tag = new CompoundTag(); super.saveAdditional(tag,regs); tag.put("manager_state_chunks",local.cinemamanager.library.CatalogStorage.write(StateCodec.publicView(state))); return tag;
    }
}
