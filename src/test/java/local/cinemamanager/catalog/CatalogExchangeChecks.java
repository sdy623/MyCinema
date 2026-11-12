package local.cinemamanager.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import local.cinemamanager.catalog.CatalogExchange.*;
import local.cinemamanager.catalog.FilmCatalog.*;

/** Portable metadata contracts. No source/file/network/decoder is opened by these checks. */
public final class CatalogExchangeChecks {
    private static int checks;
    private static final String A = "media/影片 🎬.mp4", B = "C:\\Cinema\\bonus.mkv";
    private static final String POSTER = "resource:mycinema:textures/block/info.png";
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action, String key) {
        try { action.run(); throw new AssertionError("Expected " + key); }
        catch (CatalogException error) { check(error.getMessage().equals("mycinema.catalog." + key), error.getMessage()); }
    }
    private static Entry entry(String id, String title) {
        return new Entry(id, title, POSTER, "IMAX GT", List.of(new Clip(A, 1000), new Clip(B, 2000), new Clip(A, 3000)));
    }
    private static Approved allowed(Entry entry) { return new Approved(entry.draft(), null, List.of()); }
    private static Document document(Entry entry) { return new Document(List.of(entry)); }
    private static FilmCatalog imported(Document document) { return CatalogExchange.prepare(new FilmCatalog(), 0, document, Conflict.SKIP, CatalogExchangeChecks::allowed).candidate(); }
    private static String save(FilmCatalog catalog) { return FilmCatalogCodec.encode(catalog); }

    public static void main(String[] args) {
        portableRoundTripAndHints();
        conflictsAndAtomicity();
        immutableSnapshotsAndLegacySaves();
        strictUntrustedGrammar();
        quotasAndCredentialReferences();
        System.out.println("PASS: " + checks + " catalog exchange assertions; ordered metadata, conflict/revision atomicity, authority separation and strict bounded JSON.");
    }

    private static void portableRoundTripAndHints() {
        Entry input = entry(UUID.randomUUID().toString(), "中文 programme 🎬");
        Document document = document(input);
        String json = CatalogExchange.encode(document);
        check(CatalogExchange.decode(json).equals(document), "Unicode, Windows paths and duplicate occurrences round-trip exactly");
        FilmCatalog catalog = imported(document);
        Film film = catalog.film(input.id());
        check(film.segments().stream().map(Segment::sourceKey).toList().equals(List.of(A, B, A)), "ordered duplicates are not deduplicated");
        check(film.displayMetadata().equals(input.display()) && film.poster().equals(POSTER), "poster, format and occurrence hints survive import");
        check(film.totalDurationMs().isEmpty() && film.segments().stream().allMatch(s -> s.observation() == null), "positive imported hints grant no trusted runtime");
        rejects(() -> catalog.snapshot(film.id(), film.revision()), "duration_unknown");
        check(CatalogExchange.fromCatalog(FilmCatalogCodec.decode(save(catalog))).equals(document), "persistence and export retain portable metadata");
        check(!json.contains("publication") && !json.contains("authorId") && !json.contains("revision") && !json.contains("snapshots"), "portable document carries no authority or saved programmes");
        Film renamed = catalog.update(film.id(), film.revision(), new Draft("Renamed", film.poster(), List.of(A, B, A)));
        check(renamed.displayMetadata().equals(input.display()), "title edit preserves distinct hints for repeated sources");
        Film moved = catalog.update(renamed.id(), renamed.revision(), new Draft("Reordered", POSTER, List.of(A, A, B, A)));
        check(moved.displayMetadata().durationHintsMs().equals(List.of(1000L, 3000L, 2000L, -1L)), "reorder consumes matching occurrence hints and new occurrence stays unknown");
        check(moved.displayMetadata().format().equals("IMAX GT"), "ordinary edits retain display format");
    }

    private static void conflictsAndAtomicity() {
        Entry input = entry(UUID.randomUUID().toString(), "Existing");
        FilmCatalog current = imported(document(input));
        String before = save(current);
        AtomicInteger policies = new AtomicInteger();
        Prepared skipped = CatalogExchange.prepare(current, current.revision(), document(input), Conflict.SKIP, e -> { policies.incrementAndGet(); return allowed(e); });
        check(skipped.preview().applicable() && skipped.preview().rows().getFirst().action().equals("SKIP") && save(skipped.candidate()).equals(before) && policies.get() == 0, "skip leaves catalog and its revision unchanged");
        Prepared copied = CatalogExchange.prepare(current, current.revision(), document(input), Conflict.COPY, CatalogExchangeChecks::allowed);
        check(copied.candidate().films().size() == 2 && copied.candidate().film(input.id()).equals(current.film(input.id())), "copy preserves original film");
        Film copy = copied.candidate().films().stream().filter(f -> !f.id().equals(input.id())).findFirst().orElseThrow();
        check(copy.displayMetadata().equals(input.display()) && copy.revision() == 1, "copy has new identity and same portable metadata");
        Prepared updated = CatalogExchange.prepare(current, current.revision(), document(entry(input.id(), "Updated")), Conflict.UPDATE, CatalogExchangeChecks::allowed);
        check(updated.candidate().film(input.id()).title().equals("Updated") && updated.candidate().film(input.id()).revision() == current.film(input.id()).revision() + 1, "update preserves ID and advances film revision");
        check(save(current).equals(before), "previews never mutate original registry");
        rejects(() -> CatalogExchange.prepare(current, current.revision() - 1, document(input), Conflict.UPDATE, CatalogExchangeChecks::allowed), "stale");
        Entry first = entry(UUID.randomUUID().toString(), "Valid"), second = entry(UUID.randomUUID().toString(), "Denied");
        Prepared rejected = CatalogExchange.prepare(current, current.revision(), new Document(List.of(first, second)), Conflict.SKIP, e -> {
            if (e.id().equals(second.id())) throw new CatalogException("source_permission");
            return allowed(e);
        });
        check(!rejected.preview().applicable() && rejected.candidate() == null && rejected.preview().rows().size() == 2, "late policy failure discards all earlier candidate rows");
        check(rejected.preview().rows().get(1).error().equals("mycinema.catalog.source_permission") && save(current).equals(before), "failed preview explains row without partial publication");
        Prepared sanitized = CatalogExchange.prepare(current, current.revision(), document(first), Conflict.SKIP, e -> { throw new IllegalArgumentException("private source path"); });
        check(sanitized.preview().rows().getFirst().error().equals("mycinema.catalog.exchange_invalid"), "policy exceptions cannot leak arbitrary details into review");
    }

    private static void immutableSnapshotsAndLegacySaves() {
        Entry input = entry(UUID.randomUUID().toString(), "Retained");
        FilmCatalog current = imported(document(input));
        Film film = current.film(input.id());
        String actor = UUID.randomUUID().toString(), controller = UUID.randomUUID().toString(), terminal = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            String source = film.segments().get(i).sourceKey();
            film = current.observeTrusted(film.id(), film.revision(), i, source, false, 4000 + i,
                    new Provenance(actor, controller, terminal, source, UUID.randomUUID().toString()));
        }
        Snapshot snapshot = current.snapshot(film.id(), film.revision());
        Prepared changed = CatalogExchange.prepare(current, current.revision(), document(entry(input.id(), "Replacement")), Conflict.UPDATE, CatalogExchangeChecks::allowed);
        check(changed.candidate().snapshot(snapshot.id()).equals(snapshot), "update retains old immutable scheduled programme including hints");
        check(changed.candidate().film(input.id()).totalDurationMs().isEmpty(), "update clears old source observations even for matching URLs");
        check(FilmCatalogCodec.decode(save(changed.candidate())).snapshot(snapshot.id()).equals(snapshot), "snapshot remains exact after persistence round-trip");
        current.delete(film.id(), film.revision());
        Prepared reserved = CatalogExchange.prepare(current, current.revision(), document(input), Conflict.UPDATE, CatalogExchangeChecks::allowed);
        check(!reserved.preview().applicable() && reserved.preview().rows().getFirst().error().equals("mycinema.catalog.exchange_snapshot_id_conflict"), "deleted ID retained by a show cannot be resurrected as update");
        check(CatalogExchange.prepare(current, current.revision(), document(input), Conflict.SKIP, CatalogExchangeChecks::allowed).candidate().films().isEmpty(), "skip respects retained IDs");
        FilmCatalog copy = CatalogExchange.prepare(current, current.revision(), document(input), Conflict.COPY, CatalogExchangeChecks::allowed).candidate();
        check(copy.films().size() == 1 && !copy.films().getFirst().id().equals(input.id()) && copy.snapshot(snapshot.id()).equals(snapshot), "copy permits retained-ID conflict without altering a show");
        FilmCatalog legacy = new FilmCatalog();
        Film old = legacy.create(0, new Draft("Pre3", "", List.of(A)));
        String v3 = save(legacy);
        check(!v3.contains("displayMetadata"), "unknown metadata is implicit and adds no size to old films");
        for (int version : new int[]{1, 2}) {
            FilmCatalog restored = FilmCatalogCodec.decode(v3.replace("\"version\":3", "\"version\":" + version));
            check(restored.film(old.id()).equals(old) && restored.revision() == legacy.revision(), "legacy v" + version + " preserves identity, unknown hints and revision");
        }
    }

    private static void strictUntrustedGrammar() {
        String valid = CatalogExchange.encode(document(entry(UUID.randomUUID().toString(), "Strict")));
        for (String malformed : List.of("{}", "[]", "null", valid + " {}", valid.substring(0, valid.length() - 1),
                valid.replace("\"version\": 1", "\"version\": 1, \"version\": 1"),
                valid.replace("\"version\": 1", "\"version\": \"1\""),
                valid.replace("\"version\": 1", "\"version\": 1.0"),
                valid.replace("\"version\": 1", "\"version\": 1, \"owner\": \"forged\""),
                valid.replace("\"durationMs\": 1000", "\"durationMs\": true"),
                valid.replace("\"durationMs\": 1000", "\"durationMs\": 1e3"),
                valid.replace("\"durationMs\": 1000", "\"durationMs\": 9223372036854775808"),
                "{\"schema\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"))
            rejects(() -> CatalogExchange.decode(malformed), "exchange_corrupt");
        rejects(() -> CatalogExchange.decode(valid.replace("\"version\": 1", "\"version\": 2")), "exchange_version_unsupported");
        rejects(() -> CatalogExchange.decode(valid.replace("\"durationMs\": 1000", "\"durationMs\": 0")), "duration_invalid");
        rejects(() -> CatalogExchange.decode(valid.replace("\"format\": \"IMAX GT\"", "\"format\": \"bad\\nlabel\"")), "exchange_format_invalid");
        rejects(() -> CatalogExchange.decode(valid.replace("\"title\": \"Strict\"", "\"title\": \"\\ud800\"")), "title_invalid");
        rejects(() -> CatalogExchange.decode(valid.replace("\"poster\":", "\"publication\": {}, \"poster\":")), "exchange_corrupt");
        Entry duplicate = entry(UUID.randomUUID().toString(), "Duplicate");
        rejects(() -> new Document(List.of(duplicate, duplicate)), "duplicate_film");
        rejects(() -> new Clip(A, DisplayMetadata.MAX_HINT_MS + 1), "duration_invalid");
        check(new Clip(A, -1).durationMs() == -1 && new Clip(A, DisplayMetadata.MAX_HINT_MS).durationMs() == DisplayMetadata.MAX_HINT_MS, "unknown and maximum finite hints accepted");
    }

    private static void quotasAndCredentialReferences() {
        rejects(() -> CatalogExchange.decode(" ".repeat(CatalogExchange.MAX_BYTES) + "{}"), "byte_limit");
        rejects(() -> CatalogExchange.decode("中".repeat(CatalogExchange.MAX_BYTES / 3 + 1)), "byte_limit");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < FilmCatalog.MAX_FILMS; i++) entries.add(entry(UUID.randomUUID().toString(), "Film " + i));
        Document full = new Document(entries);
        check(CatalogExchange.decode(CatalogExchange.encode(full)).equals(full), "maximum film count has deterministic bounded round-trip");
        FilmCatalog catalog = imported(full);
        String before = save(catalog);
        Prepared overflow = CatalogExchange.prepare(catalog, catalog.revision(), document(entry(UUID.randomUUID().toString(), "Overflow")), Conflict.SKIP, CatalogExchangeChecks::allowed);
        check(!overflow.preview().applicable() && overflow.candidate() == null && overflow.preview().rows().getFirst().error().equals("mycinema.catalog.film_limit") && save(catalog).equals(before), "full catalog rejects entire add without changing original");
        entries.add(entry(UUID.randomUUID().toString(), "65"));
        rejects(() -> new Document(entries), "film_limit");
        rejects(() -> new Entry(UUID.randomUUID().toString(), "Too many", "", "2D", java.util.Collections.nCopies(17, new Clip(A, -1))), "segment_limit");
        var longClips = java.util.Collections.nCopies(16, new Clip("media/" + "x".repeat(FilmCatalog.MAX_SOURCE_LENGTH - 6), -1));
        List<Entry> oversized = new ArrayList<>();
        for (int i = 0; i < FilmCatalog.MAX_FILMS; i++) oversized.add(new Entry(UUID.randomUUID().toString(), "Large " + i, "", "2D", longClips));
        rejects(() -> CatalogExchange.encode(new Document(oversized)), "byte_limit");
        for (String source : List.of("https://user:pass@example.test/film.mp4", "https://example.test/film.mp4?token=private",
                "https://example.test/my film.mp4?token=private", "https://example.test/film.mp4?%74oken=private",
                "https://example.test/film.mp4?sig=private", "https://example.test/film.mp4?key=private"))
            rejects(() -> new Clip(source, -1), "exchange_credential_reference");
        rejects(() -> new Clip("https://example.test/film.mp4?%bad%=private", -1), "exchange_corrupt");
        check(new Clip("https://example.test/film.mp4?quality=high", -1).source().endsWith("quality=high"), "ordinary noncredential query preserved exactly");
    }
}
