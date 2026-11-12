package local.cinemamanager.catalog;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.cinemamanager.catalog.FilmCatalog.Film;
import local.cinemamanager.catalog.FilmCatalog.Observation;
import local.cinemamanager.catalog.FilmCatalog.Provenance;
import local.cinemamanager.catalog.FilmCatalog.Publication;
import local.cinemamanager.catalog.FilmCatalog.Segment;
import local.cinemamanager.catalog.FilmCatalog.Snapshot;

/**
 * Versioned persistence only: never use decode on a client catalog or client metadata request.
 * Streaming, schema-bound parsing rejects unknown/duplicate fields, coercions and excess nesting.
 * No NBT or Minecraft dependency. Missing pre-library save data is represented by null/blank.
 */
public final class FilmCatalogCodec {
    public static final int VERSION = 3;
    private FilmCatalogCodec() {}

    public static String encode(FilmCatalog catalog) {
        if (catalog == null) throw new CatalogException("corrupt");
        return encodeValues(catalog.revision(), catalog.films(), catalog.snapshots());
    }
    static String encodeValues(long revision, Collection<Film> films, Collection<Snapshot> snapshots) {
        if (films.size() > FilmCatalog.MAX_FILMS) throw new CatalogException("film_limit");
        if (snapshots.size() > FilmCatalog.MAX_SNAPSHOTS) throw new CatalogException("snapshot_limit");
        LimitedWriter sink = new LimitedWriter();
        try (JsonWriter out = new JsonWriter(sink)) {
            out.beginObject();
            out.name("version").value(VERSION);
            out.name("revision").value(revision);
            out.name("films").beginArray();
            for (Film film : films) writeFilm(out, film);
            out.endArray();
            out.name("snapshots").beginArray();
            for (Snapshot snapshot : snapshots) {
                out.beginObject(); out.name("id").value(snapshot.id());
                out.name("film"); writeFilm(out, snapshot.film());
                out.name("transitionGapMs").value(snapshot.transitionGapMs()); out.endObject();
            }
            out.endArray(); out.endObject();
        } catch (IOException e) { throw new CatalogException("corrupt", e); }
        return sink.toString();
    }
    public static FilmCatalog decode(String json) {
        if (json == null) return new FilmCatalog();
        if (json.length() > FilmCatalog.MAX_SERIALIZED_BYTES || json.getBytes(StandardCharsets.UTF_8).length > FilmCatalog.MAX_SERIALIZED_BYTES) throw new CatalogException("byte_limit");
        if (json.isBlank()) return new FilmCatalog();
        try (JsonReader in = new JsonReader(new StringReader(json))) {
            in.setLenient(false);
            require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
            Set<String> fields = new HashSet<>();
            long version = -1, revision = -1;
            List<Film> films = null;
            List<SavedSnapshot> snapshots = null;
            while (in.hasNext()) {
                switch (field(in, fields)) {
                    case "version" -> version = number(in);
                    case "revision" -> revision = number(in);
                    case "films" -> films = readFilms(in);
                    case "snapshots" -> snapshots = readSnapshots(in);
                    default -> throw new CatalogException("corrupt");
                }
            }
            in.endObject(); require(in, JsonToken.END_DOCUMENT);
            if (fields.size() != 4 || films == null || snapshots == null) throw new CatalogException("corrupt");
            if (version != 1 && version != 2 && version != VERSION) throw new CatalogException("version_unsupported");
            List<Snapshot> restoredSnapshots = new ArrayList<>(snapshots.size());
            for (SavedSnapshot snapshot : snapshots) restoredSnapshots.add(snapshot.restore(version));
            return FilmCatalog.restore(revision, films, restoredSnapshots);
        } catch (CatalogException e) { throw e; }
        catch (IOException | IllegalStateException | IllegalArgumentException e) { throw new CatalogException("corrupt", e); }
    }
    private static void writeFilm(JsonWriter out, Film film) throws IOException {
        out.beginObject();
        out.name("id").value(film.id()); out.name("revision").value(film.revision());
        out.name("title").value(film.title()); out.name("poster").value(film.poster());
        out.name("segments").beginArray();
        for (Segment segment : film.segments()) {
            out.beginObject(); out.name("sourceKey").value(segment.sourceKey());
            out.name("observation");
            Observation observation = segment.observation();
            if (observation == null) out.nullValue();
            else {
                out.beginObject(); out.name("live").value(observation.live()); out.name("durationMs").value(observation.durationMs());
                out.name("provenance").beginObject();
                Provenance proof = observation.provenance();
                out.name("authorId").value(proof.authorId()); out.name("controllerId").value(proof.controllerId());
                out.name("terminalIdentity").value(proof.terminalIdentity()); out.name("sourceKey").value(proof.sourceKey());
                out.name("evidenceId").value(proof.evidenceId());
                out.endObject(); out.endObject();
            }
            out.endObject();
        }
        out.endArray();
        out.name("publication");
        Publication publication = film.publication();
        if (publication == null) out.nullValue();
        else {
            out.beginObject(); out.name("authorId").value(publication.authorId()); out.name("authorName").value(publication.authorName());
            out.name("controllerId").value(publication.controllerId()); out.name("controllerOwner").value(publication.controllerOwner());
            out.name("posterSource").value(publication.posterSource()); out.endObject();
        }
        // Keep legacy unknown metadata implicit so an existing near-quota save does not grow on load.
        if (!film.displayMetadata().equals(FilmCatalog.DisplayMetadata.unknown(film.segments().size()))) {
            out.name("displayMetadata").beginObject();
            out.name("format").value(film.displayMetadata().format());
            out.name("durationHintsMs").beginArray();
            for (long duration : film.displayMetadata().durationHintsMs()) out.value(duration);
            out.endArray(); out.endObject();
        }
        out.endObject();
    }
    private static List<Film> readFilms(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_ARRAY); in.beginArray();
        List<Film> films = new ArrayList<>();
        while (in.hasNext()) {
            if (films.size() == FilmCatalog.MAX_FILMS) throw new CatalogException("film_limit");
            films.add(readFilm(in));
        }
        in.endArray(); return films;
    }
    /** Root members may arrive in any order, so apply the version-specific shape after parsing. */
    private record SavedSnapshot(String id, Film film, Long transitionGapMs) {
        Snapshot restore(long version) {
            if (version == 1) {
                if (transitionGapMs != null) throw new CatalogException("corrupt");
                return new Snapshot(id, film);
            }
            if (transitionGapMs == null) throw new CatalogException("corrupt");
            return new Snapshot(id, film, transitionGapMs);
        }
    }
    private static List<SavedSnapshot> readSnapshots(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_ARRAY); in.beginArray();
        List<SavedSnapshot> snapshots = new ArrayList<>();
        while (in.hasNext()) {
            if (snapshots.size() == FilmCatalog.MAX_SNAPSHOTS) throw new CatalogException("snapshot_limit");
            require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
            String id = null; Film film = null;
            Long transitionGapMs = null;
            Set<String> fields = new HashSet<>();
            while (in.hasNext()) switch (field(in, fields)) {
                case "id" -> id = string(in);
                case "film" -> film = readFilm(in);
                case "transitionGapMs" -> transitionGapMs = number(in);
                default -> throw new CatalogException("corrupt");
            }
            in.endObject();
            if (!fields.containsAll(Set.of("id", "film"))) throw new CatalogException("corrupt");
            snapshots.add(new SavedSnapshot(id, film, transitionGapMs));
        }
        in.endArray(); return snapshots;
    }
    private static Film readFilm(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
        String id = null, title = null, poster = null;
        long revision = -1;
        List<Segment> segments = null;
        Publication publication = null;
        FilmCatalog.DisplayMetadata display = null;
        Set<String> fields = new HashSet<>();
        while (in.hasNext()) switch (field(in, fields)) {
            case "id" -> id = string(in);
            case "revision" -> revision = number(in);
            case "title" -> title = string(in);
            case "poster" -> poster = string(in);
            case "segments" -> segments = readSegments(in);
            case "publication" -> publication = readPublication(in);
            case "displayMetadata" -> display = readDisplayMetadata(in);
            default -> throw new CatalogException("corrupt");
        }
        in.endObject();
        // Null/absent publication is a safe legacy record: it cannot publish a poster.
        if (!fields.containsAll(Set.of("id", "revision", "title", "poster", "segments"))) throw new CatalogException("corrupt");
        Film film = new Film(id, revision, title, poster, segments, publication,
                display == null ? FilmCatalog.DisplayMetadata.unknown(segments.size()) : display);
        if (!film.title().equals(title)) throw new CatalogException("corrupt");
        return film;
    }
    private static FilmCatalog.DisplayMetadata readDisplayMetadata(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
        Set<String> fields = new HashSet<>(); String format = null; List<Long> durations = null;
        while (in.hasNext()) switch (field(in, fields)) {
            case "format" -> format = string(in);
            case "durationHintsMs" -> {
                require(in, JsonToken.BEGIN_ARRAY); in.beginArray(); durations = new ArrayList<>();
                while (in.hasNext()) {
                    if (durations.size() == FilmCatalog.MAX_SEGMENTS) throw new CatalogException("segment_limit");
                    durations.add(number(in));
                }
                in.endArray();
            }
            default -> throw new CatalogException("corrupt");
        }
        in.endObject();
        if (fields.size() != 2) throw new CatalogException("corrupt");
        return new FilmCatalog.DisplayMetadata(format, durations);
    }
    private static List<Segment> readSegments(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_ARRAY); in.beginArray();
        List<Segment> segments = new ArrayList<>();
        while (in.hasNext()) {
            if (segments.size() == FilmCatalog.MAX_SEGMENTS) throw new CatalogException("segment_limit");
            require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
            String source = null; Observation observation = null;
            Set<String> fields = new HashSet<>();
            while (in.hasNext()) switch (field(in, fields)) {
                case "sourceKey" -> source = string(in);
                case "observation" -> observation = readObservation(in);
                default -> throw new CatalogException("corrupt");
            }
            in.endObject();
            if (fields.size() != 2) throw new CatalogException("corrupt");
            segments.add(new Segment(source, observation));
        }
        in.endArray(); return segments;
    }
    private static Observation readObservation(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
        require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
        boolean live = false; long duration = 0; Provenance provenance = null;
        Set<String> fields = new HashSet<>();
        while (in.hasNext()) switch (field(in, fields)) {
            case "live" -> { require(in, JsonToken.BOOLEAN); live = in.nextBoolean(); }
            case "durationMs" -> duration = number(in);
            case "provenance" -> provenance = readProvenance(in);
            default -> throw new CatalogException("corrupt");
        }
        in.endObject();
        if (fields.size() != 3) throw new CatalogException("corrupt");
        return new Observation(live, duration, provenance);
    }
    private static Provenance readProvenance(JsonReader in) throws IOException {
        require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
        String author = null, controller = null, terminal = null, source = null, evidence = null;
        Set<String> fields = new HashSet<>();
        while (in.hasNext()) switch (field(in, fields)) {
            case "authorId" -> author = string(in);
            case "controllerId" -> controller = string(in);
            case "terminalIdentity" -> terminal = string(in);
            case "sourceKey" -> source = string(in);
            case "evidenceId" -> {
                if (in.peek() == JsonToken.NULL) in.nextNull();
                else evidence = string(in);
            }
            default -> throw new CatalogException("corrupt");
        }
        in.endObject();
        if (!fields.containsAll(Set.of("authorId", "controllerId", "terminalIdentity", "sourceKey"))) throw new CatalogException("corrupt");
        return new Provenance(author, controller, terminal, source, evidence);
    }
    private static Publication readPublication(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
        require(in, JsonToken.BEGIN_OBJECT); in.beginObject();
        String author = null, name = null, controller = null, owner = null, source = null;
        Set<String> fields = new HashSet<>();
        while (in.hasNext()) switch (field(in, fields)) {
            case "authorId" -> author = string(in);
            case "authorName" -> name = string(in);
            case "controllerId" -> controller = string(in);
            case "controllerOwner" -> owner = string(in);
            case "posterSource" -> source = string(in);
            default -> throw new CatalogException("corrupt");
        }
        in.endObject();
        if (fields.size() != 5) throw new CatalogException("corrupt");
        return new Publication(author, name, controller, owner, source);
    }
    private static String field(JsonReader in, Set<String> seen) throws IOException {
        String field = in.nextName();
        if (!seen.add(field)) throw new CatalogException("corrupt");
        return field;
    }
    private static void require(JsonReader in, JsonToken token) throws IOException {
        if (in.peek() != token) throw new CatalogException("corrupt");
    }
    private static String string(JsonReader in) throws IOException { require(in, JsonToken.STRING); return in.nextString(); }
    private static long number(JsonReader in) throws IOException {
        require(in, JsonToken.NUMBER);
        String raw = in.nextString();
        if (!raw.matches("-?(0|[1-9][0-9]*)")) throw new CatalogException("corrupt");
        return Long.parseLong(raw);
    }
    /** Counts actual UTF-8 bytes while encoding, so quota failures do not allocate an oversized tree. */
    static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private int bytes;
        private boolean highSurrogate;
        @Override public void write(char[] chars, int offset, int length) { for (int i = offset; i < offset + length; i++) appendBounded(chars[i]); }
        @Override public void write(String value, int offset, int length) { for (int i = offset; i < offset + length; i++) appendBounded(value.charAt(i)); }
        @Override public void write(int value) { appendBounded((char) value); }
        private void appendBounded(char c) {
            if (highSurrogate) {
                if (!Character.isLowSurrogate(c)) throw new CatalogException("corrupt");
                highSurrogate = false;
            } else if (Character.isHighSurrogate(c)) { bytes += 4; highSurrogate = true; }
            else if (Character.isLowSurrogate(c)) throw new CatalogException("corrupt");
            else bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            if (bytes > FilmCatalog.MAX_SERIALIZED_BYTES) throw new CatalogException("byte_limit");
            text.append(c);
        }
        @Override public void flush() {}
        @Override public void close() { if (highSurrogate) throw new CatalogException("corrupt"); }
        @Override public String toString() { return text.toString(); }
    }
}
