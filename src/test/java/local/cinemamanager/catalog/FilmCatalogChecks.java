package local.cinemamanager.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.cinemamanager.catalog.FilmCatalog.Draft;
import local.cinemamanager.catalog.FilmCatalog.Film;
import local.cinemamanager.catalog.FilmCatalog.Observation;
import local.cinemamanager.catalog.FilmCatalog.Provenance;
import local.cinemamanager.catalog.FilmCatalog.Publication;
import local.cinemamanager.catalog.FilmCatalog.SeekTarget;
import local.cinemamanager.catalog.FilmCatalog.Snapshot;

/** Pure bounded catalog contracts: no Minecraft, native player, network, file or server is opened. */
public final class FilmCatalogChecks {
    private static int checks;
    private static final String AUTHOR = "00000000-0000-0000-0000-000000000001";
    private static final String CONTROLLER = "00000000-0000-0000-0000-000000000002";
    private static final String TERMINAL = "00000000-0000-0000-0000-000000000003";
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static void rejects(Runnable action, String suffix) {
        try { action.run(); throw new AssertionError("Expected mycinema.catalog." + suffix); }
        catch (CatalogException e) { check(e.getMessage().equals("mycinema.catalog." + suffix), "failure key " + suffix + ": " + e.getMessage()); }
    }
    private static void immutable(Runnable action, String name) {
        try { action.run(); throw new AssertionError(name); }
        catch (UnsupportedOperationException e) { check(true, name); }
    }
    private static Draft draft(String title, String... sources) { return new Draft(title, "", List.of(sources)); }
    private static Provenance proof(String source) {
        return new Provenance(AUTHOR, CONTROLLER, TERMINAL, source, UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString());
    }
    private static Film observe(FilmCatalog catalog, Film film, int segment, boolean live, long duration) {
        String source = film.segments().get(segment).sourceKey();
        return catalog.observeTrusted(film.id(), film.revision(), segment, source, live, duration, proof(source));
    }
    private static String encoded(FilmCatalog catalog) { return FilmCatalogCodec.encode(catalog); }
    private static void unchanged(FilmCatalog catalog, String before, String name) { check(encoded(catalog).equals(before), name); }

    public static void main(String[] args) {
        crudAndImmutableSnapshots();
        durationsAndTimeline();
        transitionGapsAndMigration();
        exactSourceAndPublication();
        persistenceAndCorruption();
        boundedQuotas();
        System.out.println("PASS: " + checks + " film catalog assertions; immutable revisions, trusted exact-source metadata, ordered timeline, strict persistence and atomic quotas.");
    }

    private static void crudAndImmutableSnapshots() {
        FilmCatalog catalog = new FilmCatalog();
        check(catalog.revision() == 0 && catalog.films().isEmpty(), "new catalog is empty");
        ArrayList<String> sourceInput = new ArrayList<>(List.of("water://feature", "water://bonus"));
        Draft input = new Draft("  First  ", "water://poster", sourceInput);
        sourceInput.clear();
        Film created = catalog.create(0, input);
        check(created.title().equals("First") && created.segments().size() == 2, "draft copies mutable input and normalizes title");
        check(UUID.fromString(created.id()).toString().equals(created.id()) && created.revision() == 1, "stable generated film ID and initial revision");
        immutable(() -> created.segments().clear(), "immutable segments");
        immutable(() -> catalog.films().clear(), "immutable registry view");
        rejects(() -> catalog.create(0, draft("Other", "water://other")), "stale");
        rejects(() -> catalog.update(created.id(), 0, draft("Changed", "water://feature")), "stale");
        rejects(() -> catalog.delete(created.id(), 0), "stale");
        rejects(() -> catalog.film("missing"), "film_missing");
        rejects(() -> catalog.snapshot("missing"), "snapshot_missing");
        rejects(() -> catalog.update("missing", 1, draft("Changed", "water://feature")), "film_missing");
        rejects(() -> catalog.snapshot(created.id(), created.revision()), "duration_unknown");
        check(created.totalDurationMs().isEmpty(), "client draft carries no metadata");
        Film ready = observe(catalog, created, 0, false, 90_000);
        ready = observe(catalog, ready, 1, false, 10_000);
        Snapshot original = catalog.snapshot(ready.id(), ready.revision());
        long retainedRevision = catalog.revision();
        check(catalog.snapshot(ready.id(), ready.revision()).equals(original) && catalog.revision() == retainedRevision, "same film revision reuses stable snapshot without mutation");
        Film changed = catalog.update(ready.id(), ready.revision(), new Draft("Renamed", "water://new-poster", List.of("water://bonus", "water://feature")));
        check(changed.id().equals(created.id()) && changed.revision() > ready.revision(), "edit replaces revision preserving ID");
        check(changed.totalDurationMs().getAsLong() == 100_000, "exact-source metadata follows reordered media");
        check(original.film().title().equals("First") && original.film().poster().equals("water://poster") && original.film().segments().getFirst().sourceKey().equals("water://feature"), "scheduled title, poster and order cannot change with registry edit");
        check(original.totalDurationMs() == 103_000 && original.mediaDurationMs() == 100_000 && original.film().revision() == ready.revision(), "scheduled duration includes the fixed gap while media duration and revision remain immutable");
        check(created.totalDurationMs().isEmpty() && created.title().equals("First"), "previous film object never mutates");
        String before = encoded(catalog);
        Film stable = changed;
        rejects(() -> catalog.delete(stable.id(), stable.revision() - 1), "stale");
        unchanged(catalog, before, "stale delete is atomic");
        catalog.delete(stable.id(), stable.revision());
        check(catalog.films().isEmpty() && catalog.snapshot(original.id()).equals(original), "deleting registry film retains scheduled snapshot");
        FilmCatalog restored = FilmCatalogCodec.decode(encoded(catalog));
        check(restored.films().isEmpty() && restored.snapshot(original.id()).equals(original), "deleted film program survives restart");
        rejects(() -> restored.pruneSnapshots(Set.of("missing")), "snapshot_missing");
        restored.pruneSnapshots(Set.of(original.id()));
        check(restored.snapshots().size() == 1, "referenced snapshot retained");
        restored.pruneSnapshots(Set.of());
        check(restored.snapshots().isEmpty(), "explicit server maintenance reclaims only unreferenced snapshot");
    }

    private static void durationsAndTimeline() {
        FilmCatalog catalog = new FilmCatalog();
        Film film = catalog.create(0, draft("Program", "water://a", "water://b", "water://c"));
        check(film.offsetMs(0).getAsLong() == 0 && film.offsetMs(1).isEmpty(), "unknown prefix cannot fabricate offsets");
        film = observe(catalog, film, 0, false, 1000);
        check(film.offsetMs(1).getAsLong() == 1000 && film.totalDurationMs().isEmpty(), "known prefix does not imply known total");
        film = observe(catalog, film, 1, true, 2500);
        check(film.segments().get(1).live() && film.segments().get(1).observation().durationMs() == -1 && !film.schedulable(), "live observation discards apparent finite duration");
        Film live = film;
        rejects(() -> catalog.snapshot(live.id(), live.revision()), "duration_unknown");
        rejects(() -> live.seek(0), "duration_unknown");
        film = observe(catalog, film, 1, false, 0);
        check(!film.segments().get(1).live() && film.segments().get(1).durationMs().isEmpty(), "zero duration is unknown and never schedulable");
        film = observe(catalog, film, 1, false, 2500);
        film = observe(catalog, film, 2, false, 600);
        check(film.totalDurationMs().getAsLong() == 4100 && film.offsetMs(3).getAsLong() == 4100, "trusted segment sum supplies total");
        check(film.seek(0).equals(new SeekTarget(0, 0, false)), "zero selects first segment");
        check(film.seek(999).equals(new SeekTarget(0, 999, false)), "last millisecond before boundary stays local");
        check(film.seek(1000).equals(new SeekTarget(1, 0, false)), "boundary selects next segment start");
        check(film.seek(3500).equals(new SeekTarget(2, 0, false)), "second boundary selects bonus start");
        check(film.seek(4100).equals(new SeekTarget(2, 600, true)), "exact end maps to final segment end explicitly");
        Film complete = film;
        rejects(() -> complete.seek(-1), "seek_range");
        rejects(() -> complete.seek(4101), "seek_range");
        rejects(() -> complete.offsetMs(4), "segment_missing");
        Snapshot snapshot = catalog.snapshot(film.id(), film.revision());
        check(snapshot.seek(4000).equals(film.seek(1000)), "snapshot programme timeline includes the first transition gap");
        check(snapshot.segmentStartMs(2) == 9500 && snapshot.segmentStartMs(3) == 10_100, "programme offsets include only preceding transitions and no trailing gap");
        film = observe(catalog, film, 0, false, -1);
        check(!film.schedulable() && snapshot.totalDurationMs() == 10_100 && snapshot.mediaDurationMs() == 4100, "later unknown observation cannot rewrite scheduled or media duration");

        FilmCatalog overflow = new FilmCatalog();
        Film huge = overflow.create(0, draft("Overflow", "water://long", "water://one"));
        huge = observe(overflow, huge, 0, false, Long.MAX_VALUE);
        Film oneMissing = huge;
        String before = encoded(overflow);
        rejects(() -> observe(overflow, oneMissing, 1, false, 1), "duration_overflow");
        unchanged(overflow, before, "overflow rejects entire observation atomically");
        rejects(() -> new Observation(false, 0, proof("water://a")), "duration_invalid");
        rejects(() -> new Observation(true, 1, proof("water://a")), "duration_invalid");
        Provenance legacy = new Provenance(AUTHOR, CONTROLLER, TERMINAL, "water://a");
        check(new Observation(false, 1000, legacy).finiteDurationMs().isEmpty(), "positive legacy observation without evidence cannot authorize scheduling");
        rejects(() -> new Provenance("unknown-author", CONTROLLER, TERMINAL, "water://a"), "id_invalid");
        rejects(() -> new Provenance(AUTHOR, CONTROLLER, TERMINAL, "water://a", "not-evidence"), "id_invalid");
    }

    private static void transitionGapsAndMigration() {
        FilmCatalog catalog = new FilmCatalog();
        Film film = catalog.create(0, draft("A B A", "water://a", "water://b", "water://a"));
        film = observe(catalog, film, 0, false, 1001);
        film = observe(catalog, film, 1, false, 2002);
        Snapshot programme = catalog.snapshot(film.id(), film.revision());
        check(programme.transitionGapMs() == 3000 && programme.mediaDurationMs() == 4004 && programme.totalDurationMs() == 10_004, "A-B-A counts repeated media and exactly two gaps");
        check(film.requireDurationMs() == 4004 && film.offsetMs(2).getAsLong() == 3003, "film metadata remains raw media milliseconds");
        check(programme.segmentStartMs(0) == 0 && programme.segmentStartMs(1) == 4001 && programme.segmentStartMs(2) == 9003 && programme.segmentStartMs(3) == 10_004, "snapshot offsets preserve each repeated occurrence and omit a final gap");
        check(programme.seek(1000).equals(new SeekTarget(0, 1000, false)), "last media millisecond precedes first gap");
        rejects(() -> programme.seek(1001), "seek_gap");
        rejects(() -> programme.seek(4000), "seek_gap");
        check(programme.seek(4001).equals(new SeekTarget(1, 0, false)), "exact next start follows first gap");
        rejects(() -> programme.seek(6003), "seek_gap");
        rejects(() -> programme.seek(9002), "seek_gap");
        check(programme.seek(9003).equals(new SeekTarget(2, 0, false)), "exact repeated clip start follows second gap");
        check(programme.seek(10_004).equals(new SeekTarget(2, 1001, true)), "exact programme end has no extra transition");
        rejects(() -> programme.seek(-1), "seek_range");
        rejects(() -> programme.seek(10_005), "seek_range");
        rejects(() -> programme.segmentStartMs(-1), "segment_missing");
        rejects(() -> programme.segmentStartMs(4), "segment_missing");

        Snapshot legacy = new Snapshot(programme.id(), film);
        check(legacy.transitionGapMs() == 0 && legacy.totalDurationMs() == 4004 && legacy.segmentStartMs(2) == 3003, "two-argument snapshot explicitly preserves legacy gapless duration");
        check(legacy.seek(1001).equals(film.seek(1001)) && legacy.seek(4004).equals(film.seek(4004)), "legacy snapshot keeps original seek boundaries and final end");
        String v2 = encoded(catalog).replace("\"version\":3", "\"version\":2");
        String v1 = v2.replace("\"version\":2", "\"version\":1").replace(",\"transitionGapMs\":3000", "");
        FilmCatalog migrated = FilmCatalogCodec.decode(v1);
        check(migrated.snapshot(legacy.id()).equals(legacy), "version one snapshot migrates to zero gap without replacing its identity or media");
        check(FilmCatalogCodec.decode(versionLast(v1, 1)).snapshot(legacy.id()).equals(legacy), "version one migration works when version follows snapshots");
        check(FilmCatalogCodec.decode(versionLast(v2, 2)).snapshot(programme.id()).equals(programme), "version two gap works when version follows snapshots");
        String migratedJson = encoded(migrated);
        check(migratedJson.contains("\"version\":3") && migratedJson.contains("\"transitionGapMs\":0"), "migrated save writes explicit legacy policy in version three");
        check(FilmCatalogCodec.decode(migratedJson).snapshot(legacy.id()).equals(legacy), "rewriting the persistence version cannot rewrite legacy timing");
        long oldRevision = migrated.revision();
        Snapshot newPlan = migrated.snapshot(film.id(), film.revision());
        check(!newPlan.id().equals(legacy.id()) && newPlan.transitionGapMs() == 3000 && newPlan.totalDurationMs() == 10_004 && migrated.revision() == oldRevision + 1, "new capture of a legacy revision creates its own three-second policy snapshot");
        check(migrated.snapshot(legacy.id()).equals(legacy) && migrated.snapshots().size() == 2, "old and new timing policies coexist without changing old plans");
        long currentRevision = migrated.revision();
        check(migrated.snapshot(film.id(), film.revision()).equals(newPlan) && migrated.revision() == currentRevision, "new capture reuses only matching revision and gap policy");
        check(encoded(FilmCatalogCodec.decode(encoded(migrated))).equals(encoded(migrated)), "both timing policies restore together deterministically");

        rejects(() -> FilmCatalogCodec.decode(v2.replace(",\"transitionGapMs\":3000", "")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(v2.replace("\"version\":2", "\"version\":1")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(v2.replace("\"transitionGapMs\":3000", "\"transitionGapMs\":3000,\"transitionGapMs\":3000")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(v2.replace("\"transitionGapMs\":3000", "\"transitionGapMs\":3000,\"unknownGap\":0")), "corrupt");
        for (String invalid : List.of("null", "true", "\"3000\"", "3000.0", "3e3", "9223372036854775808"))
            rejects(() -> FilmCatalogCodec.decode(v2.replace("\"transitionGapMs\":3000", "\"transitionGapMs\":" + invalid)), "corrupt");
        for (long invalid : new long[]{-1, 1, 2999, 3001, Long.MAX_VALUE})
            rejects(() -> FilmCatalogCodec.decode(v2.replace("\"transitionGapMs\":3000", "\"transitionGapMs\":" + invalid)), "transition_gap_invalid");

        FilmCatalog single = new FilmCatalog();
        Film clip = single.create(0, draft("One clip", "water://a"));
        clip = observe(single, clip, 0, false, Long.MAX_VALUE);
        Snapshot noTransition = single.snapshot(clip.id(), clip.revision());
        check(noTransition.transitionGapMs() == 3000 && noTransition.totalDurationMs() == Long.MAX_VALUE && noTransition.segmentStartMs(1) == Long.MAX_VALUE, "one clip has no gap even at maximum duration");

        FilmCatalog oversized = new FilmCatalog();
        Film huge = oversized.create(0, draft("Gap overflow", "water://a", "water://b"));
        huge = observe(oversized, huge, 0, false, Long.MAX_VALUE - 3000);
        huge = observe(oversized, huge, 1, false, 1);
        Film overflow = huge;
        String before = encoded(oversized);
        check(new Snapshot(UUID.randomUUID().toString(), overflow).totalDurationMs() == Long.MAX_VALUE - 2999, "legacy media duration remains representable when a new gap would overflow");
        rejects(() -> oversized.snapshot(overflow.id(), overflow.revision()), "duration_overflow");
        unchanged(oversized, before, "gap overflow rejects capture atomically without changing film or revision");
        Film exact = observe(oversized, overflow, 0, false, Long.MAX_VALUE - 3001);
        Snapshot max = oversized.snapshot(exact.id(), exact.revision());
        check(max.totalDurationMs() == Long.MAX_VALUE && max.segmentStartMs(1) == Long.MAX_VALUE - 1, "checked total accepts the exact long boundary");

        Film revised = new Film(film.id(), film.revision(), "Altered historical content", film.poster(), film.segments());
        Snapshot mismatch = new Snapshot(UUID.randomUUID().toString(), revised, 3000);
        rejects(() -> FilmCatalog.restore(catalog.revision(), List.of(), List.of(legacy, mismatch)), "snapshot_revision_mismatch");
    }

    private static String versionLast(String json, int version) {
        String withoutVersion = json.replaceFirst("\\{\"version\":" + version + ",", "{");
        return withoutVersion.substring(0, withoutVersion.length() - 1) + ",\"version\":" + version + "}";
    }

    private static void exactSourceAndPublication() {
        FilmCatalog catalog = new FilmCatalog();
        Film film = catalog.create(0, draft("Exact", "water://A", "water://A"));
        Film pending = film;
        String before = encoded(catalog);
        rejects(() -> catalog.observeTrusted(pending.id(), pending.revision(), 0, "water://a", false, 100, proof("water://A")), "source_mismatch");
        rejects(() -> catalog.observeTrusted(pending.id(), pending.revision(), 0, "water://A", false, 100, proof("water://other")), "source_mismatch");
        rejects(() -> catalog.observeTrusted(pending.id(), pending.revision(), 3, "water://A", false, 100, proof("water://A")), "segment_missing");
        unchanged(catalog, before, "wrong source and stale selected segment cannot alter metadata");
        film = observe(catalog, film, 0, false, 100);
        check(film.totalDurationMs().getAsLong() == 200 && film.segments().get(0).observation().equals(film.segments().get(1).observation()), "repeated resource gets one consistent exact-source observation");
        long revision = catalog.revision();
        Film same = observe(catalog, film, 0, false, 100);
        check(same.equals(film) && catalog.revision() == revision, "identical observation is an idempotent no-op");
        Film renamed = catalog.update(film.id(), film.revision(), new Draft("Title only", "water://poster", List.of("water://A", "water://A")));
        check(renamed.totalDurationMs().getAsLong() == 200, "title and poster edit preserve exact-source metadata");
        Film replaced = catalog.update(renamed.id(), renamed.revision(), draft("Replacement", "water://a"));
        check(replaced.segments().getFirst().observation() == null && replaced.totalDurationMs().isEmpty(), "source case change clears all old metadata");
        Film returnOld = catalog.update(replaced.id(), replaced.revision(), draft("Return old URL", "water://A"));
        check(returnOld.segments().getFirst().observation() == null, "deleted source metadata never resurfaces when reintroduced");
        Film sourceReady = observe(catalog, returnOld, 0, false, 125);
        String actor = UUID.randomUUID().toString(), controller = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString();
        Publication grant = new Publication(actor, "Owner", controller, owner, "water://poster");
        Film published = catalog.update(sourceReady.id(), sourceReady.revision(), new Draft("Published", "water://poster", List.of("water://A")), grant);
        Snapshot scheduled = catalog.snapshot(published.id(), published.revision());
        Film refreshed = observe(catalog, published, 0, false, 150);
        check(refreshed.publication().equals(grant) && scheduled.film().publication().equals(grant), "trusted duration refresh preserves publication while old snapshot remains fixed");
        check(scheduled.totalDurationMs() == 125 && refreshed.requireDurationMs() == 150, "metadata revision does not change scheduled program");
        String publicationBefore = encoded(catalog);
        rejects(() -> catalog.update(refreshed.id(), refreshed.revision(), new Draft("Wrong poster", "water://other", List.of("water://A")), grant), "poster_mismatch");
        unchanged(catalog, publicationBefore, "poster publication cannot be borrowed for a changed source");
        rejects(() -> new Publication("not-uuid", "Owner", controller, owner, "water://poster"), "id_invalid");
        rejects(() -> new Publication(actor, "Owner\nInjected", controller, owner, "water://poster"), "publication_invalid");
        rejects(() -> new Publication(actor, "Owner\u0085Injected", controller, owner, "water://poster"), "publication_invalid");
        rejects(() -> new Publication(actor, "Owner\u2028Injected", controller, owner, "water://poster"), "publication_invalid");
        FilmCatalog loaded = FilmCatalogCodec.decode(encoded(catalog));
        check(loaded.film(refreshed.id()).publication().equals(grant) && loaded.snapshot(scheduled.id()).film().publication().equals(grant), "publication persists in both current film and immutable snapshot");
        check(Draft.class.getRecordComponents().length == 3, "client draft has only title, poster and sources");
    }

    private static void persistenceAndCorruption() {
        check(FilmCatalogCodec.decode(null).films().isEmpty() && FilmCatalogCodec.decode("").films().isEmpty() && FilmCatalogCodec.decode(" \n ").films().isEmpty(), "pre-library absent save restores empty catalog");
        FilmCatalog catalog = new FilmCatalog();
        Film film = catalog.create(0, new Draft("影片 🎬", "https://example.test/poster", List.of("C:\\Cinema\\影片 🎬.mkv")));
        film = observe(catalog, film, 0, false, 12345);
        Snapshot snapshot = catalog.snapshot(film.id(), film.revision());
        String json = encoded(catalog);
        FilmCatalog loaded = FilmCatalogCodec.decode(json);
        check(encoded(loaded).equals(json) && loaded.film(film.id()).equals(film) && loaded.snapshot(snapshot.id()).equals(snapshot), "full Unicode and Windows source tree round-trips deterministically");
        check(FilmCatalogCodec.decode(json.replace(",\"publication\":null", "")).film(film.id()).publication() == null, "legacy film without publication remains unpublishable");
        rejects(() -> FilmCatalogCodec.decode("{}"), "corrupt");
        rejects(() -> FilmCatalogCodec.decode("null"), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json + " {}"), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.substring(0, json.length() - 1)), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"version\":3", "\"version\":4")), "version_unsupported");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"version\":3", "\"version\":3,\"version\":3")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"version\":3", "\"version\":\"3\"")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"version\":3", "\"version\":3.0")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"durationMs\":12345", "\"durationMs\":0")), "duration_invalid");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"durationMs\":12345", "\"durationMs\":\"12345\"")), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"live\":false", "\"live\":true")), "duration_invalid");
        rejects(() -> FilmCatalogCodec.decode(json.replace("\"sourceKey\":\"C:", "\"sourceKey\":\"D:").replace("\"terminalIdentity\":\"" + TERMINAL + "\",\"sourceKey\":\"D:", "\"terminalIdentity\":\"" + TERMINAL + "\",\"sourceKey\":\"C:")), "source_mismatch");
        rejects(() -> FilmCatalogCodec.decode("{\"unknown\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"), "corrupt");
        rejects(() -> FilmCatalogCodec.decode(json.replaceFirst("\"title\":\"影片 🎬\"", "\"title\":\"Different\"")), "snapshot_revision_mismatch");
        Film persisted = film;
        rejects(() -> FilmCatalog.restore(loaded.revision(), List.of(persisted, persisted), loaded.snapshots()), "duplicate_film");
        rejects(() -> FilmCatalog.restore(loaded.revision(), loaded.films(), List.of(snapshot, snapshot)), "duplicate_snapshot");
        Snapshot duplicateRevision = new Snapshot(UUID.randomUUID().toString(), snapshot.film(), snapshot.transitionGapMs());
        rejects(() -> FilmCatalog.restore(loaded.revision(), loaded.films(), List.of(snapshot, duplicateRevision)), "duplicate_snapshot_revision");
        String noEvidence = json.replaceAll(",\"evidenceId\":\"[a-f0-9-]+\"", "");
        rejects(() -> FilmCatalogCodec.decode(noEvidence), "duration_unknown");
        FilmCatalog unscheduled = new FilmCatalog();
        Film pending = unscheduled.create(0, draft("Legacy proof", "water://legacy"));
        unscheduled.observeTrusted(pending.id(), pending.revision(), 0, "water://legacy", false, 1000, new Provenance(AUTHOR, CONTROLLER, TERMINAL, "water://legacy"));
        FilmCatalog oldSave = FilmCatalogCodec.decode(encoded(unscheduled).replace(",\"evidenceId\":null", ""));
        check(!oldSave.film(pending.id()).schedulable(), "legacy source metadata restores for inspection without scheduling authority");
        String exhausted = json.replaceFirst("\"revision\":[0-9]+", "\"revision\":" + Long.MAX_VALUE);
        FilmCatalog maxRevision = FilmCatalogCodec.decode(exhausted);
        Film old = maxRevision.films().getFirst();
        String before = encoded(maxRevision);
        rejects(() -> maxRevision.update(old.id(), old.revision(), draft("Overflow", old.segments().getFirst().sourceKey())), "revision_exhausted");
        unchanged(maxRevision, before, "revision overflow is atomic");
    }

    private static void boundedQuotas() {
        rejects(() -> draft("", "water://a"), "title_invalid");
        rejects(() -> draft("x".repeat(81), "water://a"), "title_invalid");
        rejects(() -> draft("Title", "water://" + "x".repeat(2041)), "source_invalid");
        rejects(() -> draft("Title", " water://a"), "source_invalid");
        rejects(() -> new Draft("Title", "x".repeat(2049), List.of("water://a")), "poster_invalid");
        rejects(() -> new Draft("Title", "", List.of()), "segments_empty");
        rejects(() -> new Draft("Title", "", java.util.Collections.nCopies(17, "water://a")), "segment_limit");
        rejects(() -> draft("Unpaired \ud800", "water://a"), "title_invalid");
        FilmCatalog films = new FilmCatalog();
        for (int i = 0; i < FilmCatalog.MAX_FILMS; i++) films.create(films.revision(), draft("Film " + i, "water://" + i));
        String before = encoded(films);
        rejects(() -> films.create(films.revision(), draft("One too many", "water://overflow")), "film_limit");
        unchanged(films, before, "64-film cap rejects atomically");
        FilmCatalog retained = new FilmCatalog();
        Film film = retained.create(0, draft("Revision 0", "water://short"));
        film = observe(retained, film, 0, false, 1);
        for (int i = 0; i < FilmCatalog.MAX_SNAPSHOTS; i++) {
            if (i != 0) film = retained.update(film.id(), film.revision(), draft("Revision " + i, "water://short"));
            retained.snapshot(film.id(), film.revision());
        }
        check(retained.snapshots().size() == FilmCatalog.MAX_SNAPSHOTS, "256 retained immutable snapshots fit");
        film = retained.update(film.id(), film.revision(), draft("Limit revision", "water://short"));
        Film overLimit = film;
        before = encoded(retained);
        rejects(() -> retained.snapshot(overLimit.id(), overLimit.revision()), "snapshot_limit");
        unchanged(retained, before, "snapshot cap rejects atomically without losing old plans");
        check(FilmCatalogCodec.decode(before).snapshots().size() == 256, "maximum snapshot count survives restart");

        FilmCatalog bytes = new FilmCatalog();
        List<String> largeSources = new ArrayList<>();
        for (int i = 0; i < 16; i++) largeSources.add("water://" + i + "/" + "片".repeat(2000));
        boolean limited = false;
        for (int i = 0; i < 64; i++) {
            String previous = encoded(bytes);
            try { bytes.create(bytes.revision(), new Draft("片".repeat(80), "", largeSources)); }
            catch (CatalogException e) {
                check(e.getMessage().equals("mycinema.catalog.byte_limit"), "UTF-8 quota, rather than character count or film count, rejects large catalog");
                unchanged(bytes, previous, "byte quota rejection leaves registry unchanged");
                limited = true; break;
            }
        }
        check(limited && bytes.films().size() < 64, "1 MiB quota takes precedence for large multibyte sources");
        String bounded = encoded(bytes);
        check(bounded.getBytes(StandardCharsets.UTF_8).length <= FilmCatalog.MAX_SERIALIZED_BYTES, "every committed catalog remains within serialized byte limit");
        check(encoded(FilmCatalogCodec.decode(bounded)).equals(bounded), "large source tree can be restored without truncation");
        rejects(() -> FilmCatalogCodec.decode("片".repeat(FilmCatalog.MAX_SERIALIZED_BYTES / 3 + 1)), "byte_limit");
        rejects(() -> FilmCatalogCodec.decode(" ".repeat(FilmCatalog.MAX_SERIALIZED_BYTES + 1)), "byte_limit");
        rejects(() -> FilmCatalogCodec.decode("{\"version\":1,\"revision\":1,\"films\":[" + "{},".repeat(64) + "{}],\"snapshots\":[]}"), "corrupt");
    }
}
