package local.cinemamanager.qa;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.StateCodec;
import local.cinemamanager.catalog.CatalogException;
import local.cinemamanager.catalog.CatalogExchange;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.catalog.FilmCatalogCodec;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Isolated service transactions against real loaded manager/TMS tiles. Only the server-owned
 * evidence and pending-probe fixtures are synthetic; these checks do not claim decoder or authenticated-report QA.
 * No source is prepared, opened, downloaded or played. Production code never mints test evidence.
 */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class FilmLibraryGameTests {
    private static final String RESOURCE_POSTER = "resource:mycinema:textures/block/info.png";

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerPlayer owner;
        final ManagerTile manager;
        final CinemaTerminalTile terminal;
        final String hallId;
        final String otherHallId;
        final List<UUID> evidenceIds = new ArrayList<>();

        Fixture(GameTestHelper helper) throws ReflectiveOperationException {
            this.helper = helper;
            helper.assertTrue(Boolean.getBoolean("mycinema.qa")
                    && helper.getLevel().getServer() instanceof GameTestServer,
                    "synthetic evidence is restricted to the opt-in isolated GameTest server");
            owner = PanelGameTests.actor(helper);
            helper.setBlock(new BlockPos(1, 1, 1), CinemaManager.MANAGER.get());
            manager = helper.getBlockEntity(new BlockPos(1, 1, 1));
            manager.owner = owner.getUUID();
            manager.state.setTimezone("UTC");
            helper.setBlock(new BlockPos(4, 1, 1), CinemaDisplays.TMS_TERMINAL.get());
            terminal = helper.getBlockEntity(new BlockPos(4, 1, 1));
            terminal.owner(owner.getUUID());
            hallId = manager.enroll(owner, terminal, true).id;
            helper.setBlock(new BlockPos(7, 1, 1), CinemaDisplays.TMS_TERMINAL.get());
            CinemaTerminalTile other = helper.getBlockEntity(new BlockPos(7, 1, 1));
            other.owner(owner.getUUID());
            otherHallId = manager.enroll(owner, other, true).id;
        }

        String source() { return "https://i.imgur.com/film-service-qa-" + UUID.randomUUID() + ".mkv"; }
        String at(int minutes) {
            return manager.state.inputTime((System.currentTimeMillis() / 60_000 + minutes) * 60_000);
        }
        CinemaSourceEvidence.Evidence evidence(UUID ownerId, UUID terminalId, String source,
                                               int index, long duration) {
            var evidence = CinemaSourceEvidence.get(helper.getLevel()).remember(ownerId, terminalId, source, index, duration);
            helper.assertTrue(evidence != null, "bounded source evidence fixture accepted");
            evidenceIds.add(evidence.id());
            return evidence;
        }
        FilmCatalog.Film ready(String title, String poster, String... sources) throws ReflectiveOperationException {
            FilmCatalog.Film film = manager.library.create(owner, manager.library.catalog().revision(),
                    new FilmCatalog.Draft(title, poster, List.of(sources)));
            for (int i = 0; i < sources.length; i++) {
                if (film.segments().get(i).durationMs().isPresent()) continue;
                var proof = evidence(owner.getUUID(), terminal.identity(), sources[i], 0, 30_000);
                film = manager.library.catalog().observeTrusted(film.id(), film.revision(), i, sources[i],
                        false, proof.durationMs(), new FilmCatalog.Provenance(owner.getUUID().toString(),
                                manager.identity.toString(), proof.terminal().toString(), sources[i], proof.id().toString()));
            }
            return film;
        }
        ManagerState.Show schedule(FilmCatalog.Film film, int minutes) {
            return manager.library.schedule(owner, hallId, film.id(), film.revision(),
                    manager.state.revision, at(minutes), "", true);
        }
        void revoke(UUID id) {
            CinemaSourceEvidence.get(helper.getLevel()).forget(id);
        }
        @Override public void close() throws ReflectiveOperationException {
            for (UUID id : evidenceIds) revoke(id);
        }
    }

    private static void rejects(GameTestHelper helper, Runnable action, String expected) {
        try {
            action.run();
            helper.fail("Expected " + expected);
        } catch (CatalogException failure) {
            helper.assertTrue(failure.getMessage().equals("mycinema.catalog." + expected),
                    "exact service error: " + failure.getMessage());
        }
    }
    private static void unchanged(GameTestHelper helper, Fixture fixture, String catalog, String manager) {
        helper.assertTrue(FilmCatalogCodec.encode(fixture.manager.library.catalog()).equals(catalog)
                        && StateCodec.encode(fixture.manager.state).equals(manager),
                "rejected transaction changes neither serialized state nor revision");
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void catalog_exchange_preview_confirm_retains_snapshots_and_playback(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            String source = f.source();
            FilmCatalog.Film film = f.ready("Saved programme", RESOURCE_POSTER, source, f.source(), source);
            var show = f.schedule(film, 10);
            var snapshot = f.manager.library.snapshot(show);
            var entry = new CatalogExchange.Entry(film.id(), "Imported display title", RESOURCE_POSTER, "IMAX GT",
                    List.of(new CatalogExchange.Clip(source, 1000), new CatalogExchange.Clip(film.segments().get(1).sourceKey(), 2000), new CatalogExchange.Clip(source, 3000)));
            var document = new CatalogExchange.Document(List.of(entry));
            String before = FilmCatalogCodec.encode(f.manager.library.catalog()), state = StateCodec.encode(f.manager.state);
            CompoundTag playbackBefore = new CompoundTag(); f.manager.playback.save(playbackBefore);
            String terminalSource = f.terminal.data.getUrl(); Object clock = f.terminal.clock();
            long revision = f.manager.library.catalog().revision();
            var preview = f.manager.library.previewCatalog(f.owner, revision, document, CatalogExchange.Conflict.UPDATE);
            helper.assertTrue(preview.applicable() && preview.rows().getFirst().action().equals("UPDATE"), "server preview accepts authorized metadata update");
            unchanged(helper, f, before, state);
            rejects(helper, () -> f.manager.library.importCatalog(f.owner, revision, document, CatalogExchange.Conflict.UPDATE, false), "confirm_required");
            unchanged(helper, f, before, state);
            f.manager.library.importCatalog(f.owner, revision, document, CatalogExchange.Conflict.UPDATE, true);
            var imported = f.manager.library.catalog().film(film.id());
            helper.assertTrue(imported.title().equals(entry.title()) && imported.poster().equals(RESOURCE_POSTER)
                            && imported.displayMetadata().equals(entry.display()) && imported.totalDurationMs().isEmpty(),
                    "confirmed metadata retains every occurrence and cannot supply trusted runtime");
            helper.assertTrue(imported.publication().authorId().equals(f.owner.getUUID().toString())
                            && imported.publication().controllerId().equals(f.manager.identity.toString()),
                    "publication is minted from the current real actor/controller, never imported JSON");
            helper.assertTrue(f.manager.library.snapshot(show).equals(snapshot), "saved show's snapshot remains immutable");
            CompoundTag playbackAfter = new CompoundTag(); f.manager.playback.save(playbackAfter);
            helper.assertTrue(playbackAfter.equals(playbackBefore) && !f.manager.state.schedulerEnabled
                            && java.util.Objects.equals(terminalSource, f.terminal.data.getUrl()) && clock == f.terminal.clock(),
                    "exchange grants no local consent, creates no playback programme and never touches the terminal clock");
            CompoundTag saved = new CompoundTag(); f.manager.library.save(saved);
            var restored = new local.cinemamanager.library.FilmLibrary(f.manager); restored.load(saved);
            helper.assertTrue(restored.catalog().film(imported.id()).equals(imported)
                            && restored.catalog().snapshot(snapshot.id()).equals(snapshot), "NBT reload preserves portable metadata and old programmes");
            helper.assertTrue(CatalogExchange.decode(f.manager.library.exportCatalog(f.owner)).films().getFirst().equals(entry), "authorized export round-trips metadata only");
            rejects(helper, () -> f.manager.library.importCatalog(f.owner, revision, document, CatalogExchange.Conflict.UPDATE, true), "stale");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void catalog_exchange_rechecks_actor_and_save_policy_atomically(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            var valid = new CatalogExchange.Entry(UUID.randomUUID().toString(), "Allowed", "", "2D", List.of(new CatalogExchange.Clip(f.source(), -1)));
            String deniedSource = "https://film-service-qa.invalid/" + UUID.randomUUID() + ".mkv";
            helper.assertFalse(CinemaPermissions.canSave(f.owner, deniedSource), "isolated ordinary actor cannot bypass source policy");
            var denied = new CatalogExchange.Entry(UUID.randomUUID().toString(), "Denied", "", "2D", List.of(new CatalogExchange.Clip(deniedSource, -1)));
            var document = new CatalogExchange.Document(List.of(valid, denied));
            long revision = f.manager.library.catalog().revision();
            String before = FilmCatalogCodec.encode(f.manager.library.catalog()), state = StateCodec.encode(f.manager.state);
            var preview = f.manager.library.previewCatalog(f.owner, revision, document, CatalogExchange.Conflict.SKIP);
            helper.assertTrue(!preview.applicable() && preview.rows().get(1).error().equals("mycinema.catalog.source_permission"), "preview reports denied row after an allowed row");
            rejects(helper, () -> f.manager.library.importCatalog(f.owner, revision, document, CatalogExchange.Conflict.SKIP, true), "exchange_review_failed");
            unchanged(helper, f, before, state);
            ServerPlayer guest = helper.makeMockServerPlayerInLevel(); guest.setPos(f.owner.position());
            rejects(helper, () -> f.manager.library.exportCatalog(guest), "permission");
            rejects(helper, () -> f.manager.library.previewCatalog(guest, revision, document, CatalogExchange.Conflict.COPY), "permission");
            var validDocument = new CatalogExchange.Document(List.of(valid));
            helper.assertTrue(f.manager.library.previewCatalog(f.owner, revision, validDocument, CatalogExchange.Conflict.SKIP).applicable(), "preview valid before revocation");
            UUID owner = f.manager.owner; f.manager.owner = UUID.randomUUID();
            try {
                rejects(helper, () -> f.manager.library.importCatalog(f.owner, revision, validDocument, CatalogExchange.Conflict.SKIP, true), "permission");
                unchanged(helper, f, before, state);
            } finally { f.manager.owner = owner; }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_schedule_failure_is_atomic_and_success_preserves_transport(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            String source = f.source();
            FilmCatalog.Film film = f.ready("Repeated programme", RESOURCE_POSTER, source, source, f.source());
            helper.assertTrue(f.manager.library.supportsOrderedPlayback(), "ordered runtime adapter is integrated");
            ManagerState.Hall hall = f.manager.state.hall(f.hallId);
            hall.override = true;
            hall.transport = ManagerState.Transport.PAUSED;
            hall.current = "Existing transport";
            String hallBefore = StateCodec.encode(f.manager.state);
            String catalogBefore = FilmCatalogCodec.encode(f.manager.library.catalog());
            long managerRevision = f.manager.state.revision;
            rejects(helper, () -> f.manager.library.schedule(f.owner, f.hallId, film.id(), film.revision(),
                    managerRevision, f.at(10), "", false), "confirm_required");
            unchanged(helper, f, catalogBefore, hallBefore);
            rejects(helper, () -> f.manager.library.schedule(f.owner, f.hallId, film.id(), film.revision() - 1,
                    managerRevision, f.at(10), "", true), "stale_film");
            unchanged(helper, f, catalogBefore, hallBefore);
            rejects(helper, () -> f.manager.library.schedule(f.owner, f.hallId, film.id(), film.revision(),
                    managerRevision - 1, f.at(10), "", true), "stale_manager");
            unchanged(helper, f, catalogBefore, hallBefore);
            PanelGameTests.rejects(helper, () -> f.manager.library.schedule(f.owner, f.hallId, film.id(), film.revision(),
                    managerRevision, f.at(-10), "", true), "past time rejected after candidate snapshot capture");
            unchanged(helper, f, catalogBefore, hallBefore);
            String terminalSource = f.terminal.data.getUrl();
            Object clock = f.terminal.clock();
            var show = f.schedule(film, 10);
            helper.assertTrue(f.manager.state.revision == managerRevision + 1,
                    "one plan transaction advances manager revision once");
            helper.assertTrue(show.filmDurationMs == 96_000 && show.end - show.start == 96_000,
                    "all three ordered occurrences contribute their full media duration plus two three-second gaps");
            helper.assertTrue(f.manager.library.snapshot(show).film().segments().get(0)
                            .equals(f.manager.library.snapshot(show).film().segments().get(1)),
                    "equal repeated sources remain distinct ordered entries");
            ManagerState.Hall after = f.manager.state.hall(f.hallId);
            helper.assertTrue(!f.manager.state.schedulerEnabled && after.override
                            && after.transport == ManagerState.Transport.PAUSED && after.current.equals("Existing transport")
                            && java.util.Objects.equals(f.terminal.data.getUrl(), terminalSource) && f.terminal.clock() == clock,
                    "saving does not enable scheduling, release takeover or touch a media clock");
            helper.assertTrue(f.manager.library.approvedPoster(show).equals(RESOURCE_POSTER),
                    "resource poster remains valid without URL permission bypass");
            String committedCatalog = FilmCatalogCodec.encode(f.manager.library.catalog());
            String committedState = StateCodec.encode(f.manager.state);
            PanelGameTests.rejects(helper, () -> f.schedule(film, 10), "overlap rejected");
            unchanged(helper, f, committedCatalog, committedState);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_replacement_and_reschedule_keep_immutable_retained_programmes(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film original = f.ready("Original", "", f.source());
            var show = f.schedule(original, 10);
            String showId = show.id;
            var saved = f.manager.library.snapshot(show);
            FilmCatalog.Film revised = f.manager.library.update(f.owner, original.id(), original.revision(),
                    new FilmCatalog.Draft("Revised", "", List.of(original.segments().getFirst().sourceKey())));
            helper.assertTrue(f.manager.library.snapshot(f.manager.state.show(showId)).equals(saved),
                    "editing the catalogue never rewrites an existing plan");
            String catalogBefore = FilmCatalogCodec.encode(f.manager.library.catalog());
            String stateBefore = StateCodec.encode(f.manager.state);
            rejects(helper, () -> f.manager.library.schedule(f.owner, f.hallId, revised.id(), revised.revision(),
                    f.manager.state.revision, f.at(15), showId, false), "confirm_required");
            unchanged(helper, f, catalogBefore, stateBefore);
            rejects(helper, () -> f.manager.library.schedule(f.owner, f.otherHallId, revised.id(), revised.revision(),
                    f.manager.state.revision, f.at(15), showId, true), "snapshot_invalid");
            unchanged(helper, f, catalogBefore, stateBefore);
            var moved = f.manager.library.reschedule(f.owner, showId, f.manager.state.revision, f.at(15));
            helper.assertTrue(moved.filmSnapshot.equals(saved.id()) && moved.title.equals("Original")
                            && f.manager.library.catalog().revision() == FilmCatalogCodec.decode(catalogBefore).revision(),
                    "rescheduling preserves the exact saved film and catalogue revision");
            var replaced = f.manager.library.schedule(f.owner, f.hallId, revised.id(), revised.revision(),
                    f.manager.state.revision, f.at(20), showId, true);
            helper.assertTrue(replaced.id.equals(showId) && replaced.title.equals("Revised")
                            && !replaced.filmSnapshot.equals(saved.id())
                            && f.manager.library.catalog().snapshot(saved.id()).equals(saved),
                    "confirmed replacement keeps show identity and retains the old immutable snapshot");
            f.manager.library.delete(f.owner, revised.id(), revised.revision(), true);
            helper.assertTrue(f.manager.library.catalog().films().isEmpty()
                            && f.manager.library.snapshot(f.manager.state.show(showId)).film().equals(revised),
                    "deleting the registry record preserves the already saved plan");
            var afterDelete = f.manager.library.reschedule(f.owner, showId, f.manager.state.revision, f.at(25));
            helper.assertTrue(afterDelete.filmRevision == revised.revision(), "deleted film's retained plan can still move");
            afterDelete.status = ManagerState.Status.RUNNING;
            String runningState = StateCodec.encode(f.manager.state);
            String runningCatalog = FilmCatalogCodec.encode(f.manager.library.catalog());
            PanelGameTests.rejects(helper, () -> f.manager.library.reschedule(f.owner, showId,
                    f.manager.state.revision, f.at(30)), "running plan cannot be rescheduled");
            unchanged(helper, f, runningCatalog, runningState);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_evidence_revocation_identity_and_permission_fail_closed(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film film = f.ready("Evidence", "", f.source());
            var snapshot = f.manager.library.captureSnapshot(f.owner, film.id(), film.revision());
            f.manager.library.validateSnapshot(snapshot, f.owner);
            f.manager.library.validateSnapshot(snapshot, null);
            ServerPlayer guest = helper.makeMockServerPlayerInLevel();
            guest.setPos(f.owner.position());
            rejects(helper, () -> f.manager.library.validateSnapshot(snapshot, guest), "permission");
            UUID identity = f.manager.identity;
            f.manager.identity = UUID.randomUUID();
            rejects(helper, () -> f.manager.library.validateSnapshot(snapshot, null), "publication_invalid");
            f.manager.identity = identity;
            UUID owner = f.manager.owner;
            f.manager.owner = UUID.randomUUID();
            rejects(helper, () -> f.manager.library.validateSnapshot(snapshot, null), "publication_invalid");
            f.manager.owner = owner;
            var proof = film.segments().getFirst().observation().provenance();
            f.revoke(UUID.fromString(proof.evidenceId()));
            rejects(helper, () -> f.manager.library.validateSnapshot(snapshot, null), "evidence_unavailable");
            String catalogBefore = FilmCatalogCodec.encode(f.manager.library.catalog());
            String stateBefore = StateCodec.encode(f.manager.state);
            rejects(helper, () -> f.schedule(film, 10), "evidence_unavailable");
            unchanged(helper, f, catalogBefore, stateBefore);
            String deniedSource = "https://film-service-qa.invalid/" + UUID.randomUUID() + ".mkv";
            helper.assertFalse(CinemaPermissions.canSave(f.owner, deniedSource), "ordinary actor lacks source bypass");
            FilmCatalog.Film denied = f.manager.library.catalog().update(film.id(), film.revision(),
                    new FilmCatalog.Draft("No bypass", "", List.of(deniedSource)), film.publication());
            var deniedEvidence = f.evidence(f.owner.getUUID(), f.terminal.identity(), deniedSource, 0, 30_000);
            denied = f.manager.library.catalog().observeTrusted(denied.id(), denied.revision(), 0, deniedSource,
                    false, 30_000, new FilmCatalog.Provenance(f.owner.getUUID().toString(),
                            f.manager.identity.toString(), f.terminal.identity().toString(), deniedSource,
                            deniedEvidence.id().toString()));
            FilmCatalog.Film deniedFilm = denied;
            rejects(helper, () -> f.manager.library.captureSnapshot(f.owner, deniedFilm.id(), deniedFilm.revision()),
                    "source_permission");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_evidence_requires_exact_owner_source_index_terminal_and_duration(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            for (int mismatch = 0; mismatch < 5; mismatch++) {
                String source = f.source();
                FilmCatalog.Film film = f.manager.library.create(f.owner, f.manager.library.catalog().revision(),
                        new FilmCatalog.Draft("Mismatch " + mismatch, "", List.of(source)));
                var evidence = f.evidence(mismatch == 0 ? UUID.randomUUID() : f.owner.getUUID(),
                        mismatch == 3 ? UUID.randomUUID() : f.terminal.identity(),
                        mismatch == 1 ? f.source() : source, mismatch == 2 ? 1 : 0,
                        mismatch == 4 ? 31_000 : 30_000);
                film = f.manager.library.catalog().observeTrusted(film.id(), film.revision(), 0, source,
                        false, 30_000, new FilmCatalog.Provenance(f.owner.getUUID().toString(),
                                f.manager.identity.toString(), f.terminal.identity().toString(), source,
                                evidence.id().toString()));
                FilmCatalog.Film candidate = film;
                String catalogBefore = FilmCatalogCodec.encode(f.manager.library.catalog());
                String stateBefore = StateCodec.encode(f.manager.state);
                rejects(helper, () -> f.manager.library.captureSnapshot(f.owner, candidate.id(), candidate.revision()),
                        "evidence_unavailable");
                unchanged(helper, f, catalogBefore, stateBefore);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_prepare_all_plans_unique_sources_and_clears_only_current_observations(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            String a = f.source(), b = f.source(), c = f.source();
            FilmCatalog.Film film = f.ready("Ordered inspection", "", a, b, a, c, b);
            var snapshot = f.manager.library.captureSnapshot(f.owner, film.id(), film.revision());
            var planner = f.manager.library.getClass().getDeclaredMethod("uniqueSegments", FilmCatalog.Film.class);
            planner.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Integer> plan = (List<Integer>) planner.invoke(null, film);
            helper.assertTrue(plan.equals(List.of(0, 1, 3)),
                    "sequential inspection selects the first occurrence of each exact source in film order");
            FilmCatalog candidate = FilmCatalogCodec.decode(FilmCatalogCodec.encode(f.manager.library.catalog()));
            var clear = f.manager.library.getClass().getDeclaredMethod("clearObservations", FilmCatalog.class,
                    FilmCatalog.Film.class, List.class, ServerPlayer.class, CinemaTerminalTile.class);
            clear.setAccessible(true);
            FilmCatalog.Film cleared = (FilmCatalog.Film) clear.invoke(f.manager.library, candidate, film, plan, f.owner, f.terminal);
            helper.assertTrue(cleared.segments().size() == 5 && cleared.totalDurationMs().isEmpty()
                            && cleared.segments().stream().allMatch(segment -> segment.durationMs().isEmpty()),
                    "every old observation clears before a complete revalidation, including duplicate occurrences");
            helper.assertTrue(cleared.revision() == film.revision() + 3
                            && candidate.snapshot(snapshot.id()).equals(snapshot)
                            && f.manager.library.catalog().film(film.id()).equals(film),
                    "one revision per unique invalidation preserves the prior snapshot and uncommitted live catalogue");
            helper.assertTrue(snapshot.film().requireDurationMs() == 150_000,
                    "retained scheduled duration still counts all duplicate occurrences");
            String before = StateCodec.encode(f.manager.state);
            rejects(helper, () -> f.manager.library.prepareAll(f.owner, f.hallId, film.id(), film.revision(), false),
                    "confirm_required");
            rejects(helper, () -> f.manager.library.prepareAll(f.owner, f.hallId, film.id(), film.revision() - 1, true),
                    "stale_film");
            helper.assertTrue(before.equals(StateCodec.encode(f.manager.state)),
                    "unconfirmed or stale bulk inspection never changes transport or starts media");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_prepare_all_cancels_on_edit_delete_manual_prepare_and_rejects_stale_continuation(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film film = f.ready("Queued inspection", "", f.source(), f.source());
            Class<?> probeType = java.util.Arrays.stream(f.manager.library.getClass().getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("Probe")).findFirst().orElseThrow();
            var constructor = probeType.getDeclaredConstructor(java.util.Arrays.stream(probeType.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
            constructor.setAccessible(true);
            var field = f.manager.library.getClass().getDeclaredField("probes");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> pending = (java.util.Map<String, Object>) field.get(f.manager.library);
            Object probe = constructor.newInstance(film.id(), film.revision(), 0,
                    film.segments().getFirst().sourceKey(), f.owner.getUUID(), f.manager.owner,
                    f.manager.identity, f.terminal.identity(), f.terminal.mediaEpoch(),
                    helper.getLevel().getGameTime() + 600, List.of(1), true);
            pending.put(f.hallId, probe);
            FilmCatalog.Film changed = f.manager.library.update(f.owner, film.id(), film.revision(),
                    new FilmCatalog.Draft("Edited during inspection", "",
                            film.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()));
            helper.assertTrue(pending.isEmpty(), "saving an edit cancels every pending continuation for that film");
            f.terminal.data.paused = true;
            var guard = f.manager.library.getClass().getDeclaredMethod("checkProbe", probeType,
                    ServerPlayer.class, FilmCatalog.Film.class, CinemaTerminalTile.class);
            guard.setAccessible(true);
            try {
                guard.invoke(f.manager.library, probe, f.owner, changed, f.terminal);
                helper.fail("Expected stale continuation to fail");
            } catch (java.lang.reflect.InvocationTargetException failure) {
                helper.assertTrue(failure.getCause() instanceof CatalogException
                                && failure.getCause().getMessage().equals("mycinema.catalog.source_stale_session"),
                        "a queued step cannot cross a film revision change");
            }
            Object current = constructor.newInstance(changed.id(), changed.revision(), 0,
                    changed.segments().getFirst().sourceKey(), f.owner.getUUID(), f.manager.owner,
                    f.manager.identity, f.terminal.identity(), f.terminal.mediaEpoch(),
                    helper.getLevel().getGameTime() + 600, List.of(1), true);
            pending.put(f.hallId, current);
            helper.assertFalse(f.terminal.playbackAvailable(), "fixture has no projector or active decoder");
            PanelGameTests.rejects(helper, () -> f.manager.library.prepare(f.owner, f.hallId,
                    changed.id(), changed.revision(), 0, true), "device rejects inspection without a projector");
            helper.assertTrue(pending.isEmpty(),
                    "an explicit single-source preparation cancels the old queue even when the device then rejects it");
            pending.put(f.hallId, current);
            f.manager.library.delete(f.owner, changed.id(), changed.revision(), true);
            helper.assertTrue(pending.isEmpty(), "deleting the film cancels queued source preparation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_reobserved_in_another_hall_keeps_canonical_evidence_and_saved_snapshot(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film film = f.ready("Shared source", "", f.source());
            var snapshot = f.manager.library.captureSnapshot(f.owner, film.id(), film.revision());
            var original = film.segments().getFirst().observation().provenance();
            CinemaTerminalTile other = f.manager.catalogTerminal(f.owner, f.otherHallId);
            var repeated = f.evidence(f.owner.getUUID(), other.identity(), original.sourceKey(), 0,
                    film.requireDurationMs());
            helper.assertTrue(repeated.id().toString().equals(original.evidenceId())
                            && repeated.terminal().toString().equals(original.terminalIdentity()),
                    "same owner/source/duration reuses the immutable canonical evidence identity and terminal");
            f.manager.library.validateSnapshot(snapshot, null);
            var show = f.schedule(film, 10);
            helper.assertTrue(show.filmSnapshot.equals(snapshot.id()),
                    "observing the same media in a second hall never invalidates an existing saved programme");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_probe_results_stay_bound_to_selected_hall_film_and_revision(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film film = f.ready("Probe result", "", f.source());
            var outcome = f.manager.library.getClass().getDeclaredMethod("probeOutcome", String.class,
                    String.class, long.class, String.class);
            outcome.setAccessible(true);
            outcome.invoke(f.manager.library, f.hallId, film.id(), film.revision(), "mycinema.catalog.metadata_timeout");
            helper.assertTrue(f.manager.library.probeNotice(f.hallId, film.id()).equals("mycinema.catalog.metadata_timeout"),
                    "a completed asynchronous failure remains visible on its matching selection");
            helper.assertTrue(f.manager.library.probeNotice(f.otherHallId, film.id()).isEmpty()
                            && f.manager.library.probeNotice(f.hallId, UUID.randomUUID().toString()).isEmpty(),
                    "another hall or film never inherits the private probe result");
            FilmCatalog.Film revised = f.manager.library.update(f.owner, film.id(), film.revision(),
                    new FilmCatalog.Draft("Changed while waiting", "", List.of(film.segments().getFirst().sourceKey())));
            helper.assertTrue(f.manager.library.probeNotice(f.hallId, revised.id()).isEmpty(),
                    "editing the film invalidates an old result");
            outcome.invoke(f.manager.library, f.hallId, revised.id(), revised.revision(), "https://private.invalid/owner/source");
            helper.assertTrue(f.manager.library.probeNotice(f.hallId, revised.id()).equals("mycinema.catalog.metadata_unavailable"),
                    "probe notices expose only bounded translation keys, never arbitrary source or owner data");
            var saved = new net.minecraft.nbt.CompoundTag();
            f.manager.library.save(saved);
            f.manager.library.load(saved);
            helper.assertTrue(f.manager.library.probeNotice(f.hallId, revised.id()).isEmpty(),
                    "restart does not revive a probe outcome from an old session");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_playback_evidence_does_not_depend_on_guest_poster_publication(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film film = f.ready("Private poster", "", f.source());
            for (String poster : List.of("file:///private/film-service-qa-poster.png", "private/film-service-qa-poster.png")) {
                var publication = new FilmCatalog.Publication(f.owner.getUUID().toString(),
                        f.owner.getGameProfile().getName(), f.manager.identity.toString(),
                        f.owner.getUUID().toString(), poster);
                // Simulates an already saved authorized poster; source evidence is independent.
                film = f.manager.library.catalog().update(film.id(), film.revision(),
                        new FilmCatalog.Draft("Private poster", poster,
                                List.of(film.segments().getFirst().sourceKey())), publication);
                var snapshot = f.manager.library.catalog().snapshot(film.id(), film.revision());
                var show = f.manager.state.addFilmShow(f.hallId, snapshot, f.at(10 + f.manager.state.shows.size() * 5),
                        System.currentTimeMillis());
                f.manager.library.validateSnapshot(snapshot, null);
                helper.assertTrue(f.manager.library.approvedPoster(show).isEmpty(),
                        "automatic guest poster never reveals a private local path");
                helper.assertFalse(f.manager.library.posterTarget(show, poster, "https://i.imgur.com/poster.png"),
                        "whitelisted destination cannot launder an unpublishable original poster");
            }
            String revokedPoster = "https://film-service-qa.invalid/" + UUID.randomUUID() + ".png";
            var publication = new FilmCatalog.Publication(f.owner.getUUID().toString(),
                    f.owner.getGameProfile().getName(), f.manager.identity.toString(),
                    f.owner.getUUID().toString(), revokedPoster);
            film = f.manager.library.catalog().update(film.id(), film.revision(),
                    new FilmCatalog.Draft("Revoked poster", revokedPoster,
                            List.of(film.segments().getFirst().sourceKey())), publication);
            var snapshot = f.manager.library.catalog().snapshot(film.id(), film.revision());
            var show = f.manager.state.addFilmShow(f.hallId, snapshot, f.at(30), System.currentTimeMillis());
            f.manager.library.validateSnapshot(snapshot, null);
            helper.assertTrue(f.manager.library.approvedPoster(show).isEmpty(),
                    "revoked poster permission hides only the poster without invalidating source evidence");
            helper.assertFalse(f.manager.library.posterTarget(show, revokedPoster, "https://i.imgur.com/poster.png"),
                    "redirect target cannot bypass revoked original-source publication");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void film_schedule_requires_loaded_exact_enrolled_hall_without_forcing_chunks(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            FilmCatalog.Film film = f.ready("Loaded hall", "", f.source());
            var hall = f.manager.state.hall(f.hallId);
            BlockPos far = new BlockPos(100000, 64, 100000);
            helper.assertFalse(helper.getLevel().hasChunkAt(far), "target begins unloaded");
            hall.terminalPos = far.asLong();
            String catalogBefore = FilmCatalogCodec.encode(f.manager.library.catalog());
            String stateBefore = StateCodec.encode(f.manager.state);
            PanelGameTests.rejects(helper, () -> f.schedule(film, 10), "unloaded hall rejected");
            unchanged(helper, f, catalogBefore, stateBefore);
            helper.assertFalse(helper.getLevel().hasChunkAt(far), "schedule validation never forces target chunk");
            hall.terminalPos = f.terminal.getBlockPos().asLong();
            hall.terminalIdentity = UUID.randomUUID().toString();
            PanelGameTests.rejects(helper, () -> f.schedule(film, 10), "replacement TMS identity rejected");
            helper.assertTrue(f.manager.library.catalog().snapshots().isEmpty(), "failed hall checks retain no snapshot");
        }
        helper.succeed();
    }
}
