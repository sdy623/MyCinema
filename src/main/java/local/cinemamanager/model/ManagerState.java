package local.cinemamanager.model;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Deterministic persistent control model. No world access, media decoding or wall-clock reads. */
public final class ManagerState {
    public static final int MAX_HALLS = 16, MAX_SHOWS = 128, MAX_OUTPUTS = 64;
    public String name = "MyCinema";
    public String timezone = "";
    public boolean schedulerEnabled;
    public final List<Hall> halls = new ArrayList<>();
    public final List<Show> shows = new ArrayList<>();
    public String notice = local.cinemamanager.MessageText.key("message.m103");
    public long revision;
    public long serverTime;
    public long snapshotSequence;
    public String seekAcknowledgement="";
    /** Snapshot-only presentation; EmergencyAccess SavedData is the sole access authority. */
    public boolean emergencyActive, emergencyAllowed;
    public long emergencyRevision, emergencyChangedAt;
    public int emergencyDoors, emergencyPendingDoors;
    public enum LightGroup { MAIN, AISLE, EXIT, ACCENT }
    public enum LightPreset { ENTRY, TRAILER, FEATURE, PAUSE, EXIT, CLEANING }
    public enum Transport { STOPPED, PLAYING, PAUSED }
    public enum Status { PENDING, PREPARING, RUNNING, STOP_PENDING, COMPLETED, MISSED, OVERRIDDEN, FAILED, CANCELLED }
    public enum Scene { OPEN, CLOSE, PLAY, PAUSE, STOP, LIGHTS_OFF, LIGHTS_HALF, LIGHTS_ON, ADMISSION_OPEN, ADMISSION_CLOSE, AUTO }
    public static final class Hall {
        public String id = UUID.randomUUID().toString();
        public String name = local.cinemamanager.MessageText.key("message.m104");
        public long terminalPos;
        public String terminalIdentity = "";
        public boolean open, admissionHeld, override;
        public String admissionOutputStatus=local.cinemamanager.MessageText.key("message.m105");
        public Transport transport = Transport.STOPPED;
        public int lights = 15;
        public LightPreset preset = LightPreset.CLEANING;
        public Map<String,int[]> presets = defaultPresets();
        public int[] activeLights = {15,15,15,15};
        public long durationMs, playheadMs;
        /** Source-free programme presentation; never used as control authority. */
        public local.cinemamanager.theater.CinemaProgramme.View programme;
        // Populated only on an authorized per-player response copy; never issued from saved state.
        public String seekToken = "", seekSession = "", seekReason = "unavailable";
        public long seekIssuedTick;
        public boolean seekLive, seekPaused;
        public String deviceState = local.cinemamanager.MessageText.key("message.m092");
        public String current = local.cinemamanager.MessageText.key("message.m106");
        public String issue = "";
        public final List<Long> outputs = new ArrayList<>();
    }
    public static final class Show {
        public String id = UUID.randomUUID().toString();
        public String hallId, title, url;
        public long start, end, startedAt;
        public String filmId="", filmSnapshot="";
        public long filmRevision, filmDurationMs;
        public Status status = Status.PENDING;
        public String outcome = "";
    }
    public Hall hall(String id) { return halls.stream().filter(h -> h.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m107"))); }
    public static boolean identityAllowed(UUID owner, UUID actor, boolean admin, boolean spectator, boolean mayInteract, double distanceSquared) {
        return !spectator && mayInteract && actor != null && (Objects.equals(owner, actor) || admin) && distanceSquared <= 64.0;
    }
    public static int outputPower(boolean light, Hall hall, boolean controllerAvailable) {
        if (!controllerAvailable || hall == null) return light ? 15 : 0;
        return light ? Math.clamp(hall.lights, 0, 15) : hall.admissionHeld ? 15 : 0;
    }
    public static Map<String,int[]> defaultPresets() {
        Map<String,int[]> m = new LinkedHashMap<>();
        m.put("ENTRY",new int[]{15,12,15,8}); m.put("TRAILER",new int[]{4,7,15,4});
        m.put("FEATURE",new int[]{0,5,15,0}); m.put("PAUSE",new int[]{7,10,15,5});
        m.put("EXIT",new int[]{12,15,15,8}); m.put("CLEANING",new int[]{15,15,15,15}); return m;
    }
    public static void savePreset(Hall h, LightPreset preset, int[] values) {
        if (values == null || values.length != 4) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m108"));
        int[] copy=values.clone(); for(int i=0;i<copy.length;i++) if(copy[i]<0 || copy[i]>15) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m109"));
        h.presets.put(preset.name(),copy);
    }
    public static void applyPreset(Hall h, LightPreset preset) {
        h.preset=preset; h.activeLights=h.presets.getOrDefault(preset.name(),defaultPresets().get(preset.name())).clone(); h.lights=h.activeLights[0];
    }
    public static void applyStage(Hall hall, LightPreset preset) {
        applyPreset(hall,preset);
        hall.admissionHeld = preset==LightPreset.ENTRY || preset==LightPreset.EXIT;
    }
    public static int outputPower(LightGroup group, Hall hall, boolean available) {
        if(!available || hall==null) return 15;
        return group==LightGroup.MAIN ? Math.clamp(hall.lights,0,15) : Math.clamp(hall.activeLights[group.ordinal()],0,15);
    }
    public static String presetName(LightPreset p) { return switch(p){case ENTRY->local.cinemamanager.MessageText.key("message.m110");case TRAILER->local.cinemamanager.MessageText.key("message.m111");case FEATURE->local.cinemamanager.MessageText.key("message.m112");case PAUSE->local.cinemamanager.MessageText.key("message.m113");case EXIT->local.cinemamanager.MessageText.key("message.m114");case CLEANING->local.cinemamanager.MessageText.key("message.m115");}; }
    public void setTimezone(String value) {
        ZoneId zone = ZoneId.of(value.trim());
        if (schedulerEnabled) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m116"));
        timezone = zone.getId();
        revision++;
    }
    public void enable(boolean enabled) {
        if (enabled && (timezone.isBlank() || halls.isEmpty())) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m117"));
        if (!timezone.isBlank()) ZoneId.of(timezone);
        schedulerEnabled = enabled;
        revision++;
    }
    public Show show(String id) {
        return shows.stream().filter(s -> Objects.equals(s.id,id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("mycinema.schedule.missing"));
    }
    public void requireRevision(long expected) {
        if (expected != revision) throw new IllegalArgumentException("mycinema.schedule.stale");
    }
    public static boolean active(Show show) {
        return show.status == Status.PREPARING || show.status == Status.RUNNING || show.status == Status.STOP_PENDING;
    }
    public static boolean filmProgramme(Show show) { return show.filmSnapshot != null && !show.filmSnapshot.isBlank(); }
    private long[] validateShow(String exceptId,String hallId,String title,String url,String localTime,int minutes,long now) {
        if(minutes<1||minutes>1440)throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m120"));
        return validateShowDuration(exceptId,hallId,title,url,localTime,minutes*60_000L,now);
    }
    private long[] validateShowDuration(String exceptId,String hallId,String title,String url,String localTime,long duration,long now) {
        if (timezone.isBlank()) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m118"));
        hall(hallId);
        if (title == null || url == null || title.isBlank() || title.length() > 80 || url.isBlank() || url.length() > 2048 || duration < 1 || duration > 86_400_000L)
            throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m120"));
        LocalDateTime localStart = LocalDateTime.parse(localTime.trim(), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT));
        ZoneId zone = ZoneId.of(timezone);
        var offsets = zone.getRules().getValidOffsets(localStart);
        if (offsets.size() != 1) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m121"));
        long start = localStart.toInstant(offsets.getFirst()).toEpochMilli();
        long end = Math.addExact(start, duration);
        if (start <= now) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m122"));
        if (shows.stream().anyMatch(s -> !Objects.equals(s.id,exceptId) && s.hallId.equals(hallId) && s.status != Status.CANCELLED && start < s.end && end > s.start))
            throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m123"));
        return new long[]{start,end};
    }
    public Show addShow(String hallId, String title, String url, String localTime, int minutes, long now) {
        if (shows.size() >= MAX_SHOWS) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m119"));
        long[] times=validateShow(null,hallId,title,url,localTime,minutes,now);
        Show s = new Show(); s.hallId = hallId; s.title = title.trim(); s.url = url.trim(); s.start = times[0]; s.end = times[1];
        shows.add(s); shows.sort(Comparator.comparingLong(a -> a.start)); revision++;
        return s;
    }
    /** Only a future pending plan may be edited. No transport or automatic state is changed. */
    public Show editShow(String id,long expected,String title,String url,String localTime,int minutes,long now) {
        requireRevision(expected);
        Show s=show(id);
        if(!s.filmSnapshot.isEmpty())throw new IllegalArgumentException("mycinema.catalog.use_film_editor");
        if(active(s)) throw new IllegalArgumentException("mycinema.schedule.active_edit");
        if(s.status!=Status.PENDING || s.start<=now) throw new IllegalArgumentException("mycinema.schedule.history_edit");
        long[] times=validateShow(s.id,s.hallId,title,url,localTime,minutes,now);
        s.title=title.trim();s.url=url.trim();s.start=times[0];s.end=times[1];
        shows.sort(Comparator.comparingLong(a -> a.start));revision++;
        return s;
    }
    public Show addFilmShow(String hallId,local.cinemamanager.catalog.FilmCatalog.Snapshot snapshot,String localTime,long now){
        if(shows.size()>=MAX_SHOWS)throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m119"));var film=snapshot.film();long duration=snapshot.totalDurationMs();long[] times=validateShowDuration(null,hallId,film.title(),film.segments().getFirst().sourceKey(),localTime,duration,now);Show show=new Show();show.hallId=hallId;assignFilm(show,snapshot,times);shows.add(show);shows.sort(Comparator.comparingLong(a->a.start));revision++;return show;
    }
    public Show replaceFilmShow(String showId,long expected,local.cinemamanager.catalog.FilmCatalog.Snapshot snapshot,String localTime,long now){
        requireRevision(expected);Show show=show(showId);futureEditable(show,now);var film=snapshot.film();long[] times=validateShowDuration(show.id,show.hallId,film.title(),film.segments().getFirst().sourceKey(),localTime,snapshot.totalDurationMs(),now);assignFilm(show,snapshot,times);shows.sort(Comparator.comparingLong(a->a.start));revision++;return show;
    }
    public Show rescheduleFilmShow(String showId,long expected,String localTime,long now){
        requireRevision(expected);Show show=show(showId);futureEditable(show,now);if(show.filmSnapshot.isBlank()||show.filmDurationMs<=0)throw new IllegalArgumentException("mycinema.catalog.snapshot_missing");long[] times=validateShowDuration(show.id,show.hallId,show.title,show.url,localTime,show.filmDurationMs,now);show.start=times[0];show.end=times[1];shows.sort(Comparator.comparingLong(a->a.start));revision++;return show;
    }
    private static void futureEditable(Show show,long now){if(active(show))throw new IllegalArgumentException("mycinema.schedule.active_edit");if(show.status!=Status.PENDING||show.start<=now)throw new IllegalArgumentException("mycinema.schedule.history_edit");}
    private static void assignFilm(Show show,local.cinemamanager.catalog.FilmCatalog.Snapshot snapshot,long[] times){var film=snapshot.film();show.title=film.title();show.url=film.segments().getFirst().sourceKey();show.start=times[0];show.end=times[1];show.filmId=film.id();show.filmRevision=film.revision();show.filmSnapshot=snapshot.id();show.filmDurationMs=snapshot.totalDurationMs();}
    /** A record removal never controls a media device. Active records must first be stopped. */
    public void deleteShow(String id,long expected) {
        requireRevision(expected);Show s=show(id);
        if(active(s)) throw new IllegalArgumentException("mycinema.schedule.active_delete");
        shows.remove(s);revision++;
    }
    public String inputTime(long timestamp) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(timezone.isBlank()?ZoneOffset.UTC:ZoneId.of(timezone)).format(Instant.ofEpochMilli(timestamp));
    }
    public void manual(Hall hall, Scene scene) { manual(hall,scene,false); }
    /** Operational controls do not replace an existing film programme or its scheduled identity. */
    public void manual(Hall hall, Scene scene, boolean currentProgramme) {
        if (scene == Scene.AUTO) { hall.override = false; hall.issue = ""; revision++; return; }
        boolean operational = switch (scene) {
            case OPEN, ADMISSION_OPEN, ADMISSION_CLOSE, LIGHTS_OFF, LIGHTS_HALF, LIGHTS_ON -> true;
            default -> false;
        };
        // Preserve the old manual-takeover contract when there is no current film programme.
        // Transport replacement, STOP and CLOSE are never made passive by this flag.
        if (!currentProgramme || !operational) takeOver(hall);
        apply(hall, scene);
        revision++;
    }
    public void takeOver(Hall hall) {
        hall.override = true;
        for (Show show : shows) if (show.hallId.equals(hall.id) && (show.status == Status.PREPARING || show.status == Status.RUNNING || show.status == Status.STOP_PENDING)) {
            show.status = Status.OVERRIDDEN; show.outcome = local.cinemamanager.MessageText.key("message.m124");
        }
        revision++;
    }
    public static void apply(Hall hall, Scene scene) {
        switch (scene) {
            case OPEN -> { hall.open = true; applyStage(hall,LightPreset.ENTRY); }
            case CLOSE -> { hall.open = false; hall.transport = Transport.STOPPED; applyStage(hall,LightPreset.CLEANING); }
            case PLAY -> { hall.open = true; hall.transport = Transport.PLAYING; applyStage(hall,LightPreset.FEATURE); }
            case PAUSE -> { hall.transport = Transport.PAUSED; applyStage(hall,LightPreset.PAUSE); }
            case STOP -> { hall.transport = Transport.STOPPED; applyStage(hall,LightPreset.EXIT); }
            case LIGHTS_OFF -> hall.lights = 0;
            case LIGHTS_HALF -> hall.lights = 7;
            case LIGHTS_ON -> hall.lights = 15;
            case ADMISSION_OPEN -> hall.admissionHeld = true;
            case ADMISSION_CLOSE -> hall.admissionHeld = false;
            case AUTO -> hall.override = false;
        }
    }
    public interface Executor { String start(Hall hall, Show show); String stop(Hall hall); default String stop(Hall hall,Show show){return stop(hall);} default boolean preparing(Hall hall,Show show){return false;} }
    /** A pending show can start only once in a 30-second window. Reload never replays RUNNING. */
    public boolean tick(long now, Executor executor) {
        boolean changed = false;
        for (Show show : shows) {
            Hall hall;
            try { hall = hall(show.hallId); } catch (IllegalArgumentException e) { continue; }
            // A film's end is its immutable planned end, not a command to clip decoder/Ready delays.
            // Only the programme's natural final Ended acknowledgement can complete a film.
            boolean film=filmProgramme(show);
            if ((show.status == Status.STOP_PENDING && (film || now >= show.end))
                    || (!film && (show.status == Status.PREPARING || show.status == Status.RUNNING) && now >= show.end)) {
                String error = executor.stop(hall,show);
                Status before = show.status; String previous = show.outcome;
                show.status = error == null ? film ? Status.CANCELLED : Status.COMPLETED : Status.STOP_PENDING;
                show.outcome = error == null ? film ? "mycinema.catalog.programme_cancelled" : local.cinemamanager.MessageText.key("message.m125") : local.cinemamanager.MessageText.key("message.m126") + error;
                // FilmPlayback projects only its exact run. An old claim must not relabel,
                // relight or stop a replacement programme sharing this hall.
                if (!film&&error == null) { apply(hall, Scene.STOP); hall.current = show.title; hall.issue = ""; }
                else if(!film) { hall.issue = show.outcome; hall.lights = 15; hall.activeLights = new int[]{15,15,15,15}; hall.admissionHeld = false; }
                changed |= before != show.status || !previous.equals(show.outcome);
            } else if (show.status == Status.PENDING && now >= show.start) {
                boolean stopPending=shows.stream().anyMatch(other -> other != show && other.hallId.equals(hall.id) && other.status == Status.STOP_PENDING);
                boolean occupied=shows.stream().anyMatch(other -> other != show && other.hallId.equals(hall.id) && active(other));
                if (!schedulerEnabled || hall.override || stopPending || occupied || now - show.start > 30_000 || now >= show.end) {
                    show.status = Status.MISSED;
                    show.outcome = !schedulerEnabled ? local.cinemamanager.MessageText.key("message.m127") : hall.override ? local.cinemamanager.MessageText.key("message.m128") : stopPending ? local.cinemamanager.MessageText.key("message.m129") : occupied ? "mycinema.schedule.programme_conflict" : local.cinemamanager.MessageText.key("message.m130");
                } else {
                    // Claim before applying: repeated ticks and restored RUNNING entries never start twice.
                    show.status = Status.RUNNING;
                    String error = executor.start(hall, show);
                    if (error != null) { show.status = Status.FAILED; show.outcome = error; hall.issue = error; }
                    else { boolean preparing=executor.preparing(hall,show); show.status=preparing?Status.PREPARING:Status.RUNNING; apply(hall,preparing?Scene.PAUSE:Scene.PLAY); hall.current = show.title; hall.issue = ""; show.startedAt = film&&preparing?0:now; show.outcome = preparing?"mycinema.catalog.programme_preparing":now-show.start>5000 ? local.cinemamanager.MessageText.key("message.m131") : local.cinemamanager.MessageText.key("message.m132"); }
                }
                changed = true;
            }
        }
        if (changed) revision++;
        return changed;
    }
    public Show next(Hall hall, long now) {
        return shows.stream().filter(s -> s.hallId.equals(hall.id) && s.status == Status.PENDING && s.start > now)
                .min(Comparator.comparingLong(s -> s.start)).orElse(null);
    }
    public String format(long timestamp) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone)).format(Instant.ofEpochMilli(timestamp));
    }
    public void validate() {
        if (halls.size() > MAX_HALLS || shows.size() > MAX_SHOWS || name.length() > 80) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m133"));
        if (!timezone.isBlank()) ZoneId.of(timezone);
        if (schedulerEnabled && timezone.isBlank()) schedulerEnabled = false;
        Set<String> ids = new HashSet<>();
        for (Hall h : halls) {
            if (h.id == null || !ids.add(h.id) || h.name == null || h.name.length() > 60 || h.outputs.size() > MAX_OUTPUTS || h.transport == null)
                throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m134"));
            UUID.fromString(h.terminalIdentity); h.lights = Math.clamp(h.lights, 0, 15);
            if(h.presets==null) h.presets=defaultPresets();
            for(LightPreset p:LightPreset.values()) {int[] v=h.presets.get(p.name());if(v==null)h.presets.put(p.name(),defaultPresets().get(p.name()));else savePreset(h,p,v);}
            if(h.preset==null)h.preset=LightPreset.CLEANING;
            if(h.activeLights==null||h.activeLights.length!=4)h.activeLights=h.presets.get(h.preset.name()).clone();
            for(int i=0;i<4;i++)h.activeLights[i]=Math.clamp(h.activeLights[i],0,15);
            h.durationMs=Math.max(0,h.durationMs);h.playheadMs=Math.max(0,h.playheadMs);
        }
        Set<String> showIds = new HashSet<>();
        for (Show s : shows) {
            if (s.id == null || !showIds.add(s.id) || !ids.contains(s.hallId) || s.status == null || s.title == null || s.title.length() > 80 || s.url == null || s.url.length() > 2048 || s.start >= s.end)throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m135"));
            if(s.filmId==null)s.filmId="";if(s.filmSnapshot==null)s.filmSnapshot="";
            if(!s.filmSnapshot.isEmpty()){UUID.fromString(s.filmSnapshot);UUID.fromString(s.filmId);if(s.filmRevision<1||s.filmDurationMs<1||s.filmDurationMs>86_400_000L||s.end-s.start!=s.filmDurationMs)throw new IllegalArgumentException("mycinema.catalog.snapshot_invalid");}
            else if(!s.filmId.isEmpty()||s.filmRevision!=0||s.filmDurationMs!=0)throw new IllegalArgumentException("mycinema.catalog.snapshot_invalid");
        }
    }
}
