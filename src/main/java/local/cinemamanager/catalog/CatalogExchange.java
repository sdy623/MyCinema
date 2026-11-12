package local.cinemamanager.catalog;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Portable, untrusted metadata only. Deliberately separate from the authority-bearing save codec. */
public final class CatalogExchange {
    public static final String SCHEMA = "mycinema.catalog";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = FilmCatalog.MAX_SERIALIZED_BYTES;
    // The fixed streaming grammar has exactly five nested containers; no recursive skipValue/tree parsing.
    public static final int MAX_DEPTH = 5;
    public enum Conflict { SKIP, COPY, UPDATE }
    public record Clip(String source, long durationMs) {
        public Clip {
            if (source == null) throw error("source_invalid");
            new FilmCatalog.Draft("check", "", List.of(source));
            if (durationMs != -1 && (durationMs <= 0 || durationMs > FilmCatalog.DisplayMetadata.MAX_HINT_MS)) throw error("duration_invalid");
            credentialFree(source);
        }
    }
    public record Entry(String id, String title, String poster, String format, List<Clip> playlist) {
        public Entry {
            canonicalId(id);
            if (playlist == null || playlist.isEmpty() || playlist.size() > FilmCatalog.MAX_SEGMENTS) throw error("segment_limit");
            playlist = List.copyOf(playlist);
            FilmCatalog.Draft draft = new FilmCatalog.Draft(title, poster, playlist.stream().map(Clip::source).toList());
            if (!draft.title().equals(title)) throw error("exchange_corrupt");
            new FilmCatalog.DisplayMetadata(format, playlist.stream().map(Clip::durationMs).toList());
            credentialFree(poster);
        }
        public FilmCatalog.Draft draft() { return new FilmCatalog.Draft(title, poster, playlist.stream().map(Clip::source).toList()); }
        public FilmCatalog.DisplayMetadata display() { return new FilmCatalog.DisplayMetadata(format, playlist.stream().map(Clip::durationMs).toList()); }
    }
    public record Document(List<Entry> films) {
        public Document {
            if (films == null || films.size() > FilmCatalog.MAX_FILMS) throw error("film_limit");
            films = List.copyOf(films);
            Set<String> ids = new HashSet<>();
            for (Entry film : films) if (!ids.add(film.id())) throw error("duplicate_film");
        }
    }
    /** Supplied only by the server's existing save-policy adapter, never decoded from client JSON. */
    public record Approved(FilmCatalog.Draft draft, FilmCatalog.Publication publication, List<String> warnings) {
        public Approved { if (draft == null || warnings == null) throw error("exchange_corrupt"); warnings = List.copyOf(warnings); }
    }
    public record Row(String id, String title, String action, List<String> warnings, String error) {
        public Row { warnings = List.copyOf(warnings); }
    }
    public record Preview(long revision, Conflict conflict, List<Row> rows, boolean applicable) {
        public Preview { rows = List.copyOf(rows); }
    }
    public record Prepared(Preview preview, FilmCatalog candidate) {}

    /** Builds a private candidate. Any failure prevents publication of every row, including earlier valid rows. */
    public static Prepared prepare(FilmCatalog current, long expected, Document document, Conflict conflict, Function<Entry, Approved> policy) {
        current.requireRevision(expected);
        if (document == null || conflict == null || policy == null) throw error("exchange_corrupt");
        FilmCatalog candidate = FilmCatalogCodec.decode(FilmCatalogCodec.encode(current));
        List<Row> rows = new ArrayList<>(); boolean applicable = true;
        for (Entry entry : document.films()) {
            boolean reserved = current.hasReservedFilmId(entry.id());
            boolean exists = current.films().stream().anyMatch(f -> f.id().equals(entry.id()));
            String action = reserved ? conflict.name() : "ADD";
            if (reserved && conflict == Conflict.SKIP) { rows.add(new Row(entry.id(), entry.title(), "SKIP", List.of(), "")); continue; }
            List<String> warnings = new ArrayList<>();
            try {
                if (reserved && conflict == Conflict.UPDATE && !exists) throw error("exchange_snapshot_id_conflict");
                Approved approved = policy.apply(entry);
                if (approved == null) throw error("permission");
                warnings.addAll(approved.warnings());
                warnings.add("mycinema.catalog.exchange_duration_unverified");
                // Source normalization may change spelling, never the number/order of occurrences.
                if (approved.draft().normalizedSources().size() != entry.playlist().size()) throw error("exchange_corrupt");
                String id = entry.id();
                if (reserved && conflict == Conflict.COPY) do { id = UUID.randomUUID().toString(); } while (candidate.hasReservedFilmId(id));
                candidate.importDraft(candidate.revision(), id, reserved && conflict == Conflict.UPDATE, approved.draft(), entry.display(), approved.publication());
                rows.add(new Row(entry.id(), entry.title(), action, warnings, ""));
            } catch (RuntimeException failure) {
                applicable = false;
                String key = failure.getMessage();
                rows.add(new Row(entry.id(), entry.title(), action, warnings,
                        key != null && key.matches("mycinema\\.catalog\\.[a-z_]{1,64}") ? key : "mycinema.catalog.exchange_invalid"));
            }
        }
        return new Prepared(new Preview(expected, conflict, rows, applicable), applicable ? candidate : null);
    }
    public static Document fromCatalog(FilmCatalog catalog) {
        List<Entry> films = new ArrayList<>();
        for (FilmCatalog.Film film : catalog.films()) {
            List<Clip> clips = new ArrayList<>();
            for (int i = 0; i < film.segments().size(); i++) {
                FilmCatalog.Segment clip = film.segments().get(i);
                long hint = clip.durationMs().orElse(film.displayMetadata().durationHintsMs().get(i));
                // Out-of-range historic observations must not silently become trusted import data.
                if (hint > FilmCatalog.DisplayMetadata.MAX_HINT_MS) hint = -1;
                clips.add(new Clip(clip.sourceKey(), hint));
            }
            films.add(new Entry(film.id(), film.title(), film.poster(), film.displayMetadata().format(), clips));
        }
        return new Document(films);
    }
    public static String encode(Document document) {
        FilmCatalogCodec.LimitedWriter sink = new FilmCatalogCodec.LimitedWriter();
        try (JsonWriter out = new JsonWriter(sink)) {
            out.setIndent("  "); out.beginObject(); out.name("schema").value(SCHEMA); out.name("version").value(VERSION);
            out.name("films").beginArray();
            for (Entry film : document.films()) {
                out.beginObject(); out.name("id").value(film.id()); out.name("title").value(film.title());
                out.name("poster").value(film.poster()); out.name("format").value(film.format()); out.name("playlist").beginArray();
                for (Clip clip : film.playlist()) {
                    out.beginObject(); out.name("source").value(clip.source()); out.name("durationMs").value(clip.durationMs()); out.endObject();
                }
                out.endArray(); out.endObject();
            }
            out.endArray(); out.endObject();
        } catch (IOException e) { throw new CatalogException("exchange_corrupt", e); }
        String result = sink.toString(); checkSize(result); return result;
    }
    public static Document decode(String json) {
        checkSize(json);
        try (JsonReader in = new JsonReader(new StringReader(json))) {
            in.setLenient(false); require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
            Set<String> fields = new HashSet<>(); String schema = null; long version = -1; List<Entry> films = null;
            while (in.hasNext()) switch (field(in, fields)) {
                case "schema" -> schema = string(in, 32);
                case "version" -> version = number(in);
                case "films" -> {
                    require(in, JsonToken.BEGIN_ARRAY); in.beginArray(); films = new ArrayList<>();
                    while (in.hasNext()) { if (films.size() == FilmCatalog.MAX_FILMS) throw error("film_limit"); films.add(readEntry(in)); }
                    in.endArray();
                }
                default -> throw error("exchange_corrupt");
            }
            in.endObject(); require(in, JsonToken.END_DOCUMENT);
            if (fields.size() != 3 || !SCHEMA.equals(schema) || films == null) throw error("exchange_corrupt");
            if (version != VERSION) throw error("exchange_version_unsupported");
            return new Document(films);
        } catch (CatalogException e) { throw e; }
        catch (IOException | IllegalStateException | IllegalArgumentException e) { throw new CatalogException("exchange_corrupt", e); }
    }
    private static Entry readEntry(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_OBJECT); in.beginObject(); Set<String> fields = new HashSet<>();
        String id = null, title = null, poster = null, format = null; List<Clip> playlist = null;
        while (in.hasNext()) switch (field(in, fields)) {
            case "id" -> id = string(in, 36);
            case "title" -> title = string(in, FilmCatalog.MAX_TITLE_LENGTH);
            case "poster" -> poster = string(in, FilmCatalog.MAX_SOURCE_LENGTH);
            case "format" -> format = string(in, 32);
            case "playlist" -> {
                require(in, JsonToken.BEGIN_ARRAY); in.beginArray(); playlist = new ArrayList<>();
                while (in.hasNext()) {
                    if (playlist.size() == FilmCatalog.MAX_SEGMENTS) throw error("segment_limit");
                    require(in, JsonToken.BEGIN_OBJECT); in.beginObject(); Set<String> clipFields = new HashSet<>();
                    String source = null; long duration = 0;
                    while (in.hasNext()) switch (field(in, clipFields)) {
                        case "source" -> source = string(in, FilmCatalog.MAX_SOURCE_LENGTH);
                        case "durationMs" -> duration = number(in);
                        default -> throw error("exchange_corrupt");
                    }
                    in.endObject(); if (clipFields.size() != 2) throw error("exchange_corrupt");
                    playlist.add(new Clip(source, duration));
                }
                in.endArray();
            }
            default -> throw error("exchange_corrupt");
        }
        in.endObject(); if (fields.size() != 5) throw error("exchange_corrupt");
        return new Entry(id, title, poster, format, playlist);
    }
    public static void checkSize(String json) {
        if (json == null || json.isBlank()) throw error("exchange_corrupt");
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw error("byte_limit");
    }
    /** No embedded URL credentials or signed/token query values are carried to another installation. */
    static void credentialFree(String reference) {
        try {
            // Save policy accepts spaces and later escapes them. Inspect credentials before that normalization too.
            URI uri = URI.create(reference.replace(" ", "%20"));
            if (uri.getRawUserInfo() != null) throw error("exchange_credential_reference");
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String name = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (name.matches(".*(token|secret|password|passwd|credential|signature|authorization|auth|api.?key|access.?key).*|sig|key|jwt|bearer")) throw error("exchange_credential_reference");
            }
            if (uri.getRawFragment() != null && uri.getRawFragment().toLowerCase(Locale.ROOT).matches(".*(token|secret|password|credential|authorization).*")) throw error("exchange_credential_reference");
        } catch (CatalogException e) { throw e; }
        catch (IllegalArgumentException invalid) {
            if (reference.matches("(?i)^(https?|ftp)://.*") || reference.startsWith("//")) throw error("exchange_corrupt");
            // Windows and relative paths are metadata; existing save policy will validate them.
        }
    }
    private static void canonicalId(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw error("id_invalid"); }
        catch (IllegalArgumentException e) { throw error("id_invalid"); }
    }
    private static String field(JsonReader in, Set<String> fields) throws IOException {
        String field = in.nextName(); if (!fields.add(field) || field.length() > 32) throw error("exchange_corrupt"); return field;
    }
    private static String string(JsonReader in, int max) throws IOException {
        require(in, JsonToken.STRING); String value = in.nextString(); if (value.length() > max) throw error("exchange_corrupt"); return value;
    }
    private static long number(JsonReader in) throws IOException {
        require(in, JsonToken.NUMBER); String raw = in.nextString();
        if (raw.length() > 19 || !raw.matches("-?(0|[1-9][0-9]*)")) throw error("exchange_corrupt");
        return Long.parseLong(raw);
    }
    private static void require(JsonReader in, JsonToken token) throws IOException { if (in.peek() != token) throw error("exchange_corrupt"); }
    private static CatalogException error(String key) { return new CatalogException(key); }
    private CatalogExchange() {}
}
