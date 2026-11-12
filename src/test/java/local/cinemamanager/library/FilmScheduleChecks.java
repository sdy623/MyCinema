package local.cinemamanager.library;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.cinemamanager.MessageText;
import local.cinemamanager.StateCodec;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.catalog.FilmCatalogCodec;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.model.ManagerState.Hall;
import local.cinemamanager.model.ManagerState.LightPreset;
import local.cinemamanager.model.ManagerState.Scene;
import local.cinemamanager.model.ManagerState.Show;
import local.cinemamanager.model.ManagerState.Status;
import local.cinemamanager.model.ManagerState.Transport;

/** Pure scheduler/catalog contracts. No world, decoder, network, file or native player is opened. */
public final class FilmScheduleChecks {
    private static final long NOW = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli();
    private static final String A = "water://feature", B = "water://bonus";
    private static int checks;

    public static void main(String[] args) {
        exactImmutablePlans();
        gapAllowanceAndMigration();
        futureCrud();
        preparingClaimsAndReload();
        exactDeadlineAndAdjacentShow();
        failedStopRemainsRetryable();
        manualTakeover();
        durationAndStartGates();
        legacyExecutorCompatibility();
        System.out.println("PASS: " + checks + " film schedule assertions; exact immutable programs, atomic future CRUD, preparation claims, reload, nominal film ends, explicit successor conflicts, exact stop retries and manual takeover.");
    }

    private static void exactImmutablePlans() {
        FilmCatalog catalog = new FilmCatalog();
        FilmCatalog.Film film = catalog.create(0, new FilmCatalog.Draft("Ordered repeats", "", List.of(A, B, A)));
        film = observe(catalog, film, 0, false, 1001);
        film = observe(catalog, film, 1, false, 60_002);
        FilmCatalog.Snapshot snapshot = catalog.snapshot(film.id(), film.revision());
        check(snapshot.mediaDurationMs() == 62_004 && snapshot.totalDurationMs() == 68_004, "every repeated source contributes its exact millisecond duration plus two transitions");
        check(snapshot.film().segments().stream().map(FilmCatalog.Segment::sourceKey).toList().equals(List.of(A, B, A)), "snapshot retains program order and repeats");
        check(snapshot.seek(4001).equals(new FilmCatalog.SeekTarget(1, 0, false)) && snapshot.seek(67_003).equals(new FilmCatalog.SeekTarget(2, 0, false)), "repeat timeline boundaries retain segment identities after their gaps");

        ManagerState state = state(false);
        Hall hall = state.halls.getFirst();
        Show show = state.addFilmShow(hall.id, snapshot, "2030-01-01 01:00", NOW);
        check(show.end - show.start == 68_004 && show.filmDurationMs == 68_004, "schedule uses exact trusted milliseconds and transition allowance without minute rounding");
        check(show.filmId.equals(film.id()) && show.filmRevision == film.revision() && show.filmSnapshot.equals(snapshot.id()), "show pins exact film revision and snapshot identity");
        check(show.title.equals("Ordered repeats") && show.url.equals(A) && show.status == Status.PENDING, "scheduled first source is the exact first ordered segment");
        check(!state.schedulerEnabled && hall.transport == Transport.STOPPED && !hall.override, "creating a film schedule does not enable automation or start media");

        FilmCatalog.Film changed = catalog.update(film.id(), film.revision(), new FilmCatalog.Draft("New edition", "", List.of(B, A)));
        changed = observe(catalog, changed, 0, false, 99_999);
        check(snapshot.totalDurationMs() == 68_004 && snapshot.film().title().equals("Ordered repeats"), "catalog reordering and duration refresh cannot rewrite a saved plan");
        catalog.delete(changed.id(), changed.revision());
        FilmCatalog restoredCatalog = FilmCatalogCodec.decode(FilmCatalogCodec.encode(catalog));
        check(restoredCatalog.films().isEmpty() && restoredCatalog.snapshot(show.filmSnapshot).equals(snapshot), "deleted film leaves its ordered scheduled snapshot intact across reload");
        ManagerState restored = StateCodec.decode(StateCodec.encode(state));
        Show restoredShow = restored.show(show.id);
        check(restoredShow.filmSnapshot.equals(snapshot.id()) && restoredShow.end - restoredShow.start == snapshot.totalDurationMs(), "scheduler reload retains exact snapshot reference and duration");
        RecordingExecutor executor = new RecordingExecutor();
        restored.enable(true);
        restored.tick(restoredShow.start, executor);
        check(executor.starts.equals(List.of(show.id)) && executor.startedSnapshots.equals(List.of(snapshot.id())), "scheduler passes the pinned plan to the executor even after catalog deletion");
    }

    private static void gapAllowanceAndMigration() {
        FilmCatalog catalog = new FilmCatalog();
        FilmCatalog.Film film = catalog.create(0, new FilmCatalog.Draft("Transition occupancy", "", List.of(A, B)));
        film = observe(catalog, film, 0, false, 29_000);
        film = observe(catalog, film, 1, false, 31_000);
        FilmCatalog.Snapshot snapshot = catalog.snapshot(film.id(), film.revision());
        ManagerState state = state(false);
        Hall hall = state.halls.getFirst();
        Show show = state.addFilmShow(hall.id, snapshot, "2030-01-01 01:00", NOW);
        check(show.filmDurationMs == 63_000 && show.end - show.start == 63_000, "new plan occupies its media minute and three-second transition");
        rejectsUnchanged(state, () -> state.addFilmShow(hall.id, snapshot("Next", 60_000), "2030-01-01 01:01", NOW), MessageText.key("message.m123"));
        Show adjacent = state.addFilmShow(hall.id, snapshot("Next minute", 60_000), "2030-01-01 01:02", NOW);
        check(adjacent.start > show.end, "later slot after the full programme remains available");
        state.rescheduleFilmShow(show.id, state.revision, "2030-01-01 03:00", NOW);
        check(show.filmSnapshot.equals(snapshot.id()) && show.end - show.start == 63_000 && show.filmDurationMs == 63_000, "retiming new plan preserves its saved gap allowance");

        String legacyJson = FilmCatalogCodec.encode(catalog)
                .replace("\"version\":" + FilmCatalogCodec.VERSION, "\"version\":1")
                .replace(",\"transitionGapMs\":3000", "");
        check(legacyJson.startsWith("{\"version\":1,") && !legacyJson.contains("\"transitionGapMs\""),
                "migration fixture is an actual version-one gapless save, independent of the current writer version");
        FilmCatalog restoredCatalog = FilmCatalogCodec.decode(legacyJson);
        FilmCatalog.Snapshot legacy = restoredCatalog.snapshot(snapshot.id());
        ManagerState legacyState = state(false);
        Hall legacyHall = legacyState.halls.getFirst();
        Show legacyShow = legacyState.addFilmShow(legacyHall.id, legacy, "2030-01-01 01:00", NOW);
        Show legacyNext = legacyState.addFilmShow(legacyHall.id, snapshot("Legacy next", 60_000), "2030-01-01 01:01", NOW);
        check(legacyShow.end == legacyNext.start && legacyShow.filmDurationMs == 60_000, "migrated gapless plan retains exact adjacency and historical duration");
        ManagerState reloaded = StateCodec.decode(StateCodec.encode(legacyState));
        Show retained = reloaded.show(legacyShow.id);
        reloaded.rescheduleFilmShow(retained.id, reloaded.revision, "2030-01-01 03:00", NOW);
        check(retained.filmSnapshot.equals(legacy.id()) && retained.filmDurationMs == 60_000 && retained.end - retained.start == 60_000, "reloaded legacy retime uses saved timing instead of new catalog policy");
        FilmCatalog.Snapshot recaptured = restoredCatalog.snapshot(film.id(), film.revision());
        check(!recaptured.id().equals(legacy.id()) && restoredCatalog.snapshot(legacy.id()).totalDurationMs() == 60_000, "new snapshot capture leaves migrated snapshot unchanged");
        reloaded.replaceFilmShow(retained.id, reloaded.revision, recaptured, "2030-01-01 03:00", NOW);
        check(retained.filmSnapshot.equals(recaptured.id()) && retained.filmDurationMs == 63_000 && retained.end - retained.start == 63_000, "explicit plan replacement adopts new snapshot transition timing");

        FilmCatalog longCatalog = new FilmCatalog();
        FilmCatalog.Film longFilm = longCatalog.create(0, new FilmCatalog.Draft("Maximum slot", "", List.of(A, B)));
        longFilm = observe(longCatalog, longFilm, 0, false, 43_198_500);
        longFilm = observe(longCatalog, longFilm, 1, false, 43_198_500);
        FilmCatalog.Snapshot max = longCatalog.snapshot(longFilm.id(), longFilm.revision());
        ManagerState maxState = state(false);
        Show maxShow = maxState.addFilmShow(maxState.halls.getFirst().id, max, "2030-01-01 01:00", NOW);
        check(max.mediaDurationMs() == 86_397_000 && maxShow.filmDurationMs == 86_400_000, "twenty-four-hour limit includes transition and permits its exact boundary");
        longFilm = observe(longCatalog, longFilm, 1, false, 43_198_501);
        FilmCatalog.Snapshot tooLong = longCatalog.snapshot(longFilm.id(), longFilm.revision());
        ManagerState overLimit = state(false);
        rejectsUnchanged(overLimit, () -> overLimit.addFilmShow(overLimit.halls.getFirst().id, tooLong, "2030-01-01 01:00", NOW), MessageText.key("message.m120"));
        check(tooLong.mediaDurationMs() < 86_400_000 && tooLong.totalDurationMs() == 86_400_001, "otherwise valid media is too long once required gap exceeds slot by one millisecond");
        rejectsUnchanged(maxState, () -> maxState.replaceFilmShow(maxShow.id, maxState.revision, tooLong, "2030-01-01 01:00", NOW), MessageText.key("message.m120"));
    }

    private static void futureCrud() {
        ManagerState state = state(false);
        Hall hall = state.halls.getFirst();
        FilmCatalog.Snapshot original = snapshot("Original", 60_001);
        FilmCatalog.Snapshot replacement = snapshot("Replacement", 120_007);
        Show first = state.addFilmShow(hall.id, original, "2030-01-01 01:00", NOW);
        Show second = state.addFilmShow(hall.id, original, "2030-01-01 02:00", NOW);
        String firstId = first.id;

        rejectsUnchanged(state, () -> state.rescheduleFilmShow(firstId, state.revision - 1, "2030-01-01 03:00", NOW), "mycinema.schedule.stale");
        rejectsUnchanged(state, () -> state.replaceFilmShow(firstId, state.revision - 1, replacement, "2030-01-01 03:00", NOW), "mycinema.schedule.stale");
        rejectsUnchanged(state, () -> state.deleteShow(firstId, state.revision - 1), "mycinema.schedule.stale");
        rejectsUnchanged(state, () -> state.rescheduleFilmShow(firstId, state.revision, "2030-01-01 02:00", NOW), MessageText.key("message.m123"));
        rejectsUnchanged(state, () -> state.replaceFilmShow(firstId, state.revision, replacement, "2030-01-01 01:59", NOW), MessageText.key("message.m123"));
        rejectsUnchanged(state, () -> state.rescheduleFilmShow(firstId, state.revision, "2030-01-01 00:00", NOW), MessageText.key("message.m122"));
        rejectsUnchanged(state, () -> state.editShow(firstId, state.revision, "URL bypass", "water://other", "2030-01-01 03:00", 5, NOW), "mycinema.catalog.use_film_editor");

        long revision = state.revision;
        Show rescheduled = state.rescheduleFilmShow(firstId, revision, "2030-01-01 03:00", NOW);
        check(rescheduled == first && first.id.equals(firstId) && first.hallId.equals(hall.id), "rescheduling keeps show identity and hall");
        check(first.filmSnapshot.equals(original.id()) && first.end - first.start == 60_001 && first.title.equals("Original"), "rescheduling preserves immutable film content and exact duration");
        check(state.revision == revision + 1 && state.shows.equals(List.of(second, first)), "rescheduling revises and sorts the plan once");
        revision = state.revision;
        Show replaced = state.replaceFilmShow(firstId, revision, replacement, "2030-01-01 00:30", NOW);
        check(replaced == first && first.status == Status.PENDING && first.hallId.equals(hall.id), "replacement preserves pending show identity and hall");
        check(first.filmSnapshot.equals(replacement.id()) && first.filmId.equals(replacement.film().id()) && first.filmRevision == replacement.film().revision(), "replacement atomically switches all film identifiers");
        check(first.title.equals("Replacement") && first.filmDurationMs == 120_007 && first.end - first.start == 120_007, "replacement uses the replacement title and exact trusted duration");
        check(state.revision == revision + 1 && state.shows.equals(List.of(first, second)), "replacement revises and sorts the plan once");
        check(!state.schedulerEnabled && !hall.override && hall.transport == Transport.STOPPED, "future film edits do not affect transport or automation");

        for (Status status : List.of(Status.PREPARING, Status.RUNNING, Status.STOP_PENDING)) {
            first.status = status;
            check(ManagerState.active(first), "preparation and pending stop are active claims: " + status);
            rejectsUnchanged(state, () -> state.rescheduleFilmShow(firstId, state.revision, "2030-01-01 03:00", NOW), "mycinema.schedule.active_edit");
            rejectsUnchanged(state, () -> state.replaceFilmShow(firstId, state.revision, original, "2030-01-01 03:00", NOW), "mycinema.schedule.active_edit");
            rejectsUnchanged(state, () -> state.deleteShow(firstId, state.revision), "mycinema.schedule.active_delete");
        }
        for (Status status : List.of(Status.COMPLETED, Status.MISSED, Status.OVERRIDDEN, Status.FAILED, Status.CANCELLED)) {
            first.status = status;
            rejectsUnchanged(state, () -> state.rescheduleFilmShow(firstId, state.revision, "2030-01-01 03:00", NOW), "mycinema.schedule.history_edit");
            rejectsUnchanged(state, () -> state.replaceFilmShow(firstId, state.revision, original, "2030-01-01 03:00", NOW), "mycinema.schedule.history_edit");
        }
        first.status = Status.PENDING;
        rejectsUnchanged(state, () -> state.rescheduleFilmShow(firstId, state.revision, "2030-01-01 03:00", first.start), "mycinema.schedule.history_edit");
        rejectsUnchanged(state, () -> state.replaceFilmShow(firstId, state.revision, original, "2030-01-01 03:00", first.start), "mycinema.schedule.history_edit");
        state.deleteShow(firstId, state.revision);
        check(state.shows.equals(List.of(second)) && hall.transport == Transport.STOPPED && !hall.override, "deleting a future film plan removes only that record without controlling playback");
        rejectsUnchanged(state, () -> state.deleteShow(firstId, state.revision), "mycinema.schedule.missing");
    }

    private static void preparingClaimsAndReload() {
        ManagerState state = state(true);
        Hall hall = state.halls.getFirst();
        Show show = state.addFilmShow(hall.id, snapshot("Preparing", 60_001), "2030-01-01 01:00", NOW);
        RecordingExecutor executor = new RecordingExecutor();
        long revision = state.revision;
        check(!state.tick(show.start - 1, executor) && state.revision == revision && executor.starts.isEmpty(), "film program never starts before its time");
        check(state.tick(show.start, executor), "due film preparation changes state");
        check(executor.starts.equals(List.of(show.id)) && executor.claimedBeforeStart, "scheduler claims the specific show before calling start");
        check(show.status == Status.PREPARING && hall.transport == Transport.PAUSED && hall.preset == LightPreset.PAUSE, "accepted asynchronous preparation cannot claim RUNNING or PLAYING");
        check(show.outcome.equals("mycinema.catalog.programme_preparing") && show.startedAt == 0, "preparation reports its claim without inventing an actual playing time");
        executor.preparing = false;
        check(!state.tick(show.start + 1, executor) && show.status == Status.PREPARING && executor.starts.size() == 1, "repeated tick cannot restart or infer ready without playback acknowledgement");

        for (Status status : List.of(Status.PREPARING, Status.RUNNING)) {
            show.status = status;
            show.outcome = "saved-" + status;
            ManagerState restored = StateCodec.decode(StateCodec.encode(state));
            Show saved = restored.show(show.id);
            RecordingExecutor resumed = new RecordingExecutor();
            check(saved.status == status && saved.outcome.equals(show.outcome) && saved.startedAt == show.startedAt, "reload preserves active claim and outcome: " + status);
            check(saved.filmSnapshot.equals(show.filmSnapshot) && saved.filmRevision == show.filmRevision && saved.filmDurationMs == 60_001, "reload preserves active plan identity and milliseconds: " + status);
            restored.tick(saved.start + 10, resumed);
            check(resumed.starts.isEmpty() && resumed.stops.isEmpty() && saved.status == status, "reload cannot replay an already claimed film: " + status);
            restored.tick(saved.end, resumed);
            check(resumed.starts.isEmpty() && resumed.stops.isEmpty() && saved.status == status, "restored film keeps its active claim past nominal end: " + status);
            restored.tick(saved.end + 1, resumed);
            check(resumed.stops.isEmpty() && resumed.legacyStops == 0 && saved.end == show.end, "reloaded programme neither replays nor rewrites nominal end: " + status);
        }
    }

    private static void exactDeadlineAndAdjacentShow() {
        ManagerState state = state(true);
        Hall hall = state.halls.getFirst();
        Hall otherHall = hall(state, 20);
        otherHall.current = "Independent manual source";
        otherHall.transport = Transport.PLAYING;
        Show first = state.addFilmShow(hall.id, snapshot("First", 60_000), "2030-01-01 01:00", NOW);
        Show next = state.addFilmShow(hall.id, snapshot("Next", 60_001), "2030-01-01 01:01", NOW);
        long plannedStart=first.start,plannedEnd=first.end;
        RecordingExecutor executor = new RecordingExecutor();
        state.tick(first.start, executor);
        check(!state.tick(first.end-1,executor)&&executor.stops.isEmpty(), "preparing film has no clock-only stop before nominal end");
        state.tick(first.end, executor);
        check(first.status==Status.PREPARING&&next.status==Status.MISSED, "unfinished film is retained and adjacent successor is explicitly missed");
        check(next.outcome.equals("mycinema.schedule.programme_conflict"), "successor records an occupied-programme reason");
        check(executor.events.equals(List.of("start:"+first.id))&&executor.stops.isEmpty(), "conflict never halts or replaces the old programme");
        check(otherHall.transport==Transport.PLAYING&&otherHall.current.equals("Independent manual source"), "same-hall conflict leaves other halls untouched");
        state.enable(false);
        state.tick(next.end+1,executor);
        check(first.status==Status.PREPARING&&executor.stops.isEmpty(), "disabling new schedules does not fabricate natural film completion");
        check(first.start==plannedStart&&first.end==plannedEnd&&first.end-first.start==first.filmDurationMs, "actual delay never changes immutable planned timing");
        first.status=Status.COMPLETED; // Adapter alone publishes its natural final Ended acknowledgement.
        state.enable(true);
        state.tick(next.end+2,executor);
        check(next.status==Status.MISSED&&executor.starts.equals(List.of(first.id)), "a conflicted successor is never silently rescheduled or replayed");
    }

    private static void failedStopRemainsRetryable() {
        ManagerState state = state(true);
        Hall hall = state.halls.getFirst();
        Show first = state.addFilmShow(hall.id, snapshot("Stop retry", 60_000), "2030-01-01 01:00", NOW);
        Show next = state.addFilmShow(hall.id, snapshot("Blocked next", 60_000), "2030-01-01 01:01", NOW);
        RecordingExecutor executor = new RecordingExecutor();
        executor.preparing = false;
        state.tick(first.start, executor);
        check(first.status == Status.RUNNING && hall.transport == Transport.PLAYING, "synchronous start retains legacy running behavior");
        executor.stopError = "owned_stop_failed";
        first.status=Status.STOP_PENDING; // An explicit stop request, not the nominal film end.
        state.tick(first.end, executor);
        check(first.status == Status.STOP_PENDING && hall.transport == Transport.PLAYING, "failed stop cannot claim completion or stopped playback");
        check(first.outcome.equals(MessageText.key("message.m126") + executor.stopError) && hall.issue.isEmpty(), "failed film stop records its reason without fabricating hall state outside the exact adapter");
        check(hall.transport==Transport.PLAYING&&hall.current.equals(first.title), "scheduler alone cannot publish an unconfirmed film stop or rewrite hall transport");
        check(next.status == Status.MISSED && executor.starts.equals(List.of(first.id)), "unresolved old stop blocks a new same-hall claim");
        check(!state.tick(first.end + 1, executor) && executor.stops.equals(List.of(first.id, first.id)), "unchanged failed retry is attempted without false revision churn");
        rejectsUnchanged(state, () -> state.deleteShow(first.id, state.revision), "mycinema.schedule.active_delete");

        ManagerState restored = StateCodec.decode(StateCodec.encode(state));
        Show saved = restored.show(first.id);
        Hall savedHall = restored.hall(hall.id);
        check(saved.status == Status.STOP_PENDING && savedHall.transport == Transport.PLAYING && savedHall.issue.equals(hall.issue), "reload preserves unresolved stop and truthful hall transport");
        restored.enable(false);
        RecordingExecutor retry = new RecordingExecutor();
        restored.tick(saved.end + 2, retry);
        check(saved.status == Status.CANCELLED && retry.stops.equals(List.of(saved.id)) && retry.starts.isEmpty(), "restored explicit stop retries its exact show and never fabricates film completion");
        check(savedHall.transport == Transport.PLAYING && savedHall.issue.isEmpty(), "record-only retry delegates physical film state to its exact playback adapter");
        restored.tick(saved.end + 3, retry);
        check(retry.stops.size() == 1 && retry.legacyStops == 0, "successful retry is final and never broad-stops the hall again");
    }

    private static void manualTakeover() {
        for (Status status : List.of(Status.PREPARING, Status.RUNNING, Status.STOP_PENDING)) {
            ManagerState state = state(true);
            Hall hall = state.halls.getFirst();
            Hall other = hall(state, 30);
            Show show = state.addFilmShow(hall.id, snapshot("Taken over", 60_001), "2030-01-01 01:00", NOW);
            Show untouched = state.addFilmShow(other.id, snapshot("Other hall", 120_001), "2030-01-01 01:00", NOW);
            show.status = status;
            untouched.status = Status.PREPARING;
            state.manual(hall, Scene.PAUSE);
            check(hall.override && show.status == Status.OVERRIDDEN && hall.transport == Transport.PAUSED, "manual scene takes over an active film claim: " + status);
            check(untouched.status == Status.PREPARING && !other.override, "manual takeover remains isolated to its hall: " + status);
            RecordingExecutor executor = new RecordingExecutor();
            state.tick(show.end, executor);
            check(executor.starts.isEmpty() && executor.stops.isEmpty(), "old film deadline cannot stop a manual takeover: " + status);
            state.manual(hall, Scene.AUTO);
            state.tick(show.end + 1, executor);
            check(!hall.override && show.status == Status.OVERRIDDEN && executor.starts.isEmpty() && executor.stops.isEmpty(), "returning to auto never resurrects the overridden film: " + status);
        }
    }

    private static void durationAndStartGates() {
        for (int mode = 0; mode < 4; mode++) {
            FilmCatalog catalog = new FilmCatalog();
            FilmCatalog.Film film = catalog.create(0, new FilmCatalog.Draft("Untrusted duration", "", List.of(A)));
            if (mode == 1) film = observe(catalog, film, 0, false, -1);
            if (mode == 2) film = observe(catalog, film, 0, true, 1000);
            if (mode == 3) film = catalog.observeTrusted(film.id(), film.revision(), 0, A, false, 1000,
                    new FilmCatalog.Provenance(id(1), id(2), id(3), A));
            FilmCatalog.Film unavailable = film;
            ManagerState state = state(true);
            String before = StateCodec.encode(state);
            rejects(() -> state.addFilmShow(state.halls.getFirst().id, catalog.snapshot(unavailable.id(), unavailable.revision()), "2030-01-01 01:00", NOW), "mycinema.catalog.duration_unknown");
            check(before.equals(StateCodec.encode(state)) && state.shows.isEmpty(), "missing, unknown, live or unevidenced duration cannot create a schedule: " + mode);
        }
        FilmCatalog partial = new FilmCatalog();
        FilmCatalog.Film incomplete = partial.create(0, new FilmCatalog.Draft("Incomplete", "", List.of(A, B)));
        incomplete = observe(partial, incomplete, 0, false, 1000);
        FilmCatalog.Film onlyOneKnown = incomplete;
        rejects(() -> partial.snapshot(onlyOneKnown.id(), onlyOneKnown.revision()), "mycinema.catalog.duration_unknown");

        ManagerState disabled = state(false);
        Show disabledShow = disabled.addFilmShow(disabled.halls.getFirst().id, snapshot("Disabled", 60_001), "2030-01-01 01:00", NOW);
        RecordingExecutor executor = new RecordingExecutor();
        disabled.tick(disabledShow.start, executor);
        disabled.enable(true);
        disabled.tick(disabledShow.start + 1, executor);
        check(disabledShow.status == Status.MISSED && executor.starts.isEmpty(), "enabling scheduling cannot replay a show missed while disabled");

        for (long lateBy : new long[]{30_001, 60_001}) {
            ManagerState late = state(true);
            Show show = late.addFilmShow(late.halls.getFirst().id, snapshot("Late", 60_001), "2030-01-01 01:00", NOW);
            RecordingExecutor lateExecutor = new RecordingExecutor();
            late.tick(show.start + lateBy, lateExecutor);
            check(show.status == Status.MISSED && lateExecutor.starts.isEmpty() && lateExecutor.stops.isEmpty(), "missed start window or elapsed film cannot auto-start: " + lateBy);
        }
        ManagerState boundary = state(true);
        Show boundaryShow = boundary.addFilmShow(boundary.halls.getFirst().id, snapshot("Last allowed start", 60_001), "2030-01-01 01:00", NOW);
        RecordingExecutor boundaryExecutor = new RecordingExecutor();
        boundary.tick(boundaryShow.start + 30_000, boundaryExecutor);
        check(boundaryShow.status == Status.PREPARING && boundaryExecutor.starts.equals(List.of(boundaryShow.id)), "start window accepts exactly thirty seconds without claiming playback ready");
        boundary.tick(boundaryShow.end, boundaryExecutor);
        check(boundaryShow.status == Status.PREPARING && boundaryExecutor.stops.isEmpty() && boundaryShow.end-boundaryShow.start==60_001, "late preparation keeps the nominal end but cannot clip the saved programme");

        ManagerState failed = state(true);
        Show failedShow = failed.addFilmShow(failed.halls.getFirst().id, snapshot("Failed start", 60_001), "2030-01-01 01:00", NOW);
        RecordingExecutor failure = new RecordingExecutor();
        failure.startError = "snapshot_not_authorized";
        failed.tick(failedShow.start, failure);
        failed.tick(failedShow.start + 1, failure);
        failed.tick(failedShow.end, failure);
        check(failedShow.status == Status.FAILED && failedShow.outcome.equals(failure.startError) && failed.halls.getFirst().transport == Transport.STOPPED, "rejected preparation remains failed and never publishes playing transport");
        check(failure.starts.size() == 1 && failure.stops.isEmpty(), "failed start cannot replay or acquire a deadline stop claim");
    }

    private static void legacyExecutorCompatibility() {
        ManagerState state = state(true);
        Show show = state.addShow(state.halls.getFirst().id, "Legacy URL", A, "2030-01-01 01:00", 1, NOW);
        int[] calls = {0, 0};
        ManagerState.Executor executor = new ManagerState.Executor() {
            @Override public String start(Hall hall, Show selected) { calls[0]++; return null; }
            @Override public String stop(Hall hall) { calls[1]++; return null; }
        };
        state.tick(show.start, executor);
        check(show.status == Status.RUNNING && state.halls.getFirst().transport == Transport.PLAYING, "default preparing hook retains synchronous legacy playback");
        state.tick(show.end, executor);
        state.tick(show.end + 1, executor);
        check(show.status == Status.COMPLETED && calls[0] == 1 && calls[1] == 1, "show-specific default stop remains compatible with legacy executor exactly once");
    }

    private static final class RecordingExecutor implements ManagerState.Executor {
        final List<String> starts = new ArrayList<>(), startedSnapshots = new ArrayList<>(), stops = new ArrayList<>(), events = new ArrayList<>();
        boolean preparing = true, claimedBeforeStart = true;
        String startError, stopError;
        int legacyStops;
        @Override public String start(Hall hall, Show show) {
            check(hall.id.equals(show.hallId), "start receives the scheduled hall");
            claimedBeforeStart &= ManagerState.active(show);
            starts.add(show.id);
            startedSnapshots.add(show.filmSnapshot);
            events.add("start:" + show.id);
            return startError;
        }
        @Override public boolean preparing(Hall hall, Show show) { return preparing; }
        @Override public String stop(Hall hall) { legacyStops++; return stopError; }
        @Override public String stop(Hall hall, Show show) {
            check(hall.id.equals(show.hallId), "deadline stop receives the scheduled hall");
            stops.add(show.id);
            events.add("stop:" + show.id);
            return stopError;
        }
    }

    private static ManagerState state(boolean enabled) {
        ManagerState state = new ManagerState();
        state.setTimezone("UTC");
        hall(state, 10);
        if (enabled) state.enable(true);
        return state;
    }
    private static Hall hall(ManagerState state, long identity) {
        Hall hall = new Hall();
        hall.id = id(identity);
        hall.name = "Hall " + identity;
        hall.terminalIdentity = id(identity + 100);
        state.halls.add(hall);
        return hall;
    }
    private static FilmCatalog.Snapshot snapshot(String title, long durationMs) {
        FilmCatalog catalog = new FilmCatalog();
        FilmCatalog.Film film = catalog.create(0, new FilmCatalog.Draft(title, "", List.of(A)));
        film = observe(catalog, film, 0, false, durationMs);
        return catalog.snapshot(film.id(), film.revision());
    }
    private static FilmCatalog.Film observe(FilmCatalog catalog, FilmCatalog.Film film, int segment, boolean live, long duration) {
        String source = film.segments().get(segment).sourceKey();
        FilmCatalog.Provenance proof = new FilmCatalog.Provenance(id(1), id(2), id(3), source, id(source.equals(A) ? 4 : 5));
        return catalog.observeTrusted(film.id(), film.revision(), segment, source, live, duration, proof);
    }
    private static String id(long value) { return new UUID(0, value).toString(); }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Runnable action, String key) {
        try { action.run(); }
        catch (IllegalArgumentException expected) {
            check(key.equals(expected.getMessage()), "expected " + key + ", got " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection: " + key);
    }
    private static void rejectsUnchanged(ManagerState state, Runnable action, String key) {
        String before = StateCodec.encode(state);
        rejects(action, key);
        check(before.equals(StateCodec.encode(state)), "rejected " + key + " changes no scheduler state");
    }
}
