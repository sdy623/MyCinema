package local.cinemamanager.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side film registry and immutable scheduled programs. No world, decoder, clock or I/O access.
 * Sources and posters must already have passed the server's normalization/authorization policy.
 * This class never resolves a URL and never treats a client duration as metadata.
 * Confine the mutable catalog to the owning server thread; all returned values are immutable.
 */
public final class FilmCatalog {
    public static final int MAX_FILMS = 64;
    public static final int MAX_SEGMENTS = 16;
    public static final int MAX_SNAPSHOTS = 256;
    public static final int MAX_SERIALIZED_BYTES = 1_048_576;
    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_SOURCE_LENGTH = 2048;
    public static final long DEFAULT_TRANSITION_GAP_MS = 3_000;

    /** The entire client-editable shape: deliberately has no duration, live flag or provenance. */
    public record Draft(String title, String poster, List<String> normalizedSources) {
        public Draft {
            title = FilmCatalog.title(title);
            poster = FilmCatalog.poster(poster);
            if (normalizedSources == null || normalizedSources.isEmpty()) throw error("segments_empty");
            if (normalizedSources.size() > MAX_SEGMENTS) throw error("segment_limit");
            List<String> checked = new ArrayList<>(normalizedSources.size());
            for (String source : normalizedSources) checked.add(source(source));
            normalizedSources = List.copyOf(checked);
        }
    }

    /** Exact identities recorded by the authorized server observation, never from a client draft. */
    public record Provenance(String authorId, String controllerId, String terminalIdentity, String sourceKey, String evidenceId) {
        /** Legacy evidence may be retained for inspection, but cannot supply a schedulable duration. */
        public Provenance(String authorId, String controllerId, String terminalIdentity, String sourceKey) {
            this(authorId, controllerId, terminalIdentity, sourceKey, null);
        }
        public Provenance {
            authorId = id(authorId);
            controllerId = id(controllerId);
            terminalIdentity = id(terminalIdentity);
            sourceKey = source(sourceKey);
            if (evidenceId != null) evidenceId = id(evidenceId);
        }
    }

    /** Server-issued poster publication provenance; absent legacy data does not authorize publishing. */
    public record Publication(String authorId, String authorName, String controllerId, String controllerOwner, String posterSource) {
        public Publication {
            authorId = id(authorId);
            controllerId = id(controllerId);
            controllerOwner = id(controllerOwner);
            if (authorName == null || authorName.isBlank() || authorName.length() > 64 || !wellFormed(authorName)) throw error("publication_invalid");
            posterSource = poster(posterSource);
        }
    }

    /** durationMs is positive for finite media, or -1 for unknown/live. Zero is never a duration. */
    public record Observation(boolean live, long durationMs, Provenance provenance) {
        public Observation {
            if (provenance == null) throw error("provenance_invalid");
            if (durationMs != -1 && durationMs <= 0 || live && durationMs != -1) throw error("duration_invalid");
        }
        public OptionalLong finiteDurationMs() {
            return !live && durationMs > 0 && provenance.evidenceId() != null ? OptionalLong.of(durationMs) : OptionalLong.empty();
        }
    }

    public record Segment(String sourceKey, Observation observation) {
        public Segment {
            sourceKey = source(sourceKey);
            if (observation != null && !sourceKey.equals(observation.provenance().sourceKey())) throw error("source_mismatch");
        }
        public OptionalLong durationMs() { return observation == null ? OptionalLong.empty() : observation.finiteDurationMs(); }
        public boolean live() { return observation != null && observation.live(); }
    }

    public record SeekTarget(int segmentIndex, long localTimeMs, boolean atEnd) {}

    /** Portable display hints. These never supply trusted duration, playback authority or publication. */
    public record DisplayMetadata(String format, List<Long> durationHintsMs) {
        public static final long MAX_HINT_MS = 604_800_000L;
        public DisplayMetadata {
            if (format == null || format.isBlank() || format.length() > 32 || !format.equals(format.trim()) || !wellFormed(format)) throw error("exchange_format_invalid");
            if (durationHintsMs == null || durationHintsMs.isEmpty() || durationHintsMs.size() > MAX_SEGMENTS) throw error("segment_limit");
            for (Long duration : durationHintsMs) if (duration == null || duration != -1 && (duration <= 0 || duration > MAX_HINT_MS)) throw error("duration_invalid");
            durationHintsMs = List.copyOf(durationHintsMs);
        }
        public static DisplayMetadata unknown(int count) { return new DisplayMetadata("UNKNOWN", java.util.Collections.nCopies(count, -1L)); }
    }

    /** A revision is replaced as a whole; a saved program can safely retain this exact value. */
    public record Film(String id, long revision, String title, String poster, List<Segment> segments, Publication publication, DisplayMetadata displayMetadata) {
        public Film(String id, long revision, String title, String poster, List<Segment> segments) {
            this(id, revision, title, poster, segments, null);
        }
        public Film(String id, long revision, String title, String poster, List<Segment> segments, Publication publication) {
            this(id, revision, title, poster, segments, publication, null);
        }
        public Film {
            id = FilmCatalog.id(id);
            if (revision < 1) throw error("revision_invalid");
            title = FilmCatalog.title(title);
            poster = FilmCatalog.poster(poster);
            if (publication != null && !poster.equals(publication.posterSource())) throw error("poster_mismatch");
            if (segments == null || segments.isEmpty()) throw error("segments_empty");
            if (segments.size() > MAX_SEGMENTS) throw error("segment_limit");
            for (Segment segment : segments) if (segment == null) throw error("segment_invalid");
            segments = List.copyOf(segments);
            if (displayMetadata == null) displayMetadata = DisplayMetadata.unknown(segments.size());
            if (displayMetadata.durationHintsMs().size() != segments.size()) throw error("exchange_metadata_invalid");
            Map<String, Segment> sources = new LinkedHashMap<>();
            for (Segment segment : segments) {
                Segment previous = sources.putIfAbsent(segment.sourceKey(), segment);
                if (previous != null && !previous.equals(segment)) throw error("source_observation_mismatch");
            }
            // Even an incomplete program may not contain a known-duration sum that overflows.
            long known = 0;
            for (Segment segment : segments) if (segment.durationMs().isPresent()) known = addDuration(known, segment.durationMs().getAsLong());
        }
        public OptionalLong totalDurationMs() {
            long total = 0;
            for (Segment segment : segments) {
                OptionalLong duration = segment.durationMs();
                if (duration.isEmpty()) return OptionalLong.empty();
                total = addDuration(total, duration.getAsLong());
            }
            return OptionalLong.of(total);
        }
        public boolean schedulable() { return totalDurationMs().isPresent(); }
        public long requireDurationMs() { return totalDurationMs().orElseThrow(() -> error("duration_unknown")); }
        /** Start offset of an index, or the total when index == segment count; unknown prefixes stay unknown. */
        public OptionalLong offsetMs(int segmentIndex) {
            if (segmentIndex < 0 || segmentIndex > segments.size()) throw error("segment_missing");
            long offset = 0;
            for (int i = 0; i < segmentIndex; i++) {
                OptionalLong duration = segments.get(i).durationMs();
                if (duration.isEmpty()) return OptionalLong.empty();
                offset = addDuration(offset, duration.getAsLong());
            }
            return OptionalLong.of(offset);
        }
        /** Boundaries select the next segment; the exact program end selects the last segment's end. */
        public SeekTarget seek(long globalTimeMs) {
            long total = requireDurationMs();
            if (globalTimeMs < 0 || globalTimeMs > total) throw error("seek_range");
            long remaining = globalTimeMs;
            for (int i = 0; i < segments.size(); i++) {
                long duration = segments.get(i).durationMs().orElseThrow();
                if (remaining < duration || i == segments.size() - 1) return new SeekTarget(i, remaining, globalTimeMs == total);
                remaining -= duration;
            }
            throw error("seek_range");
        }
    }

    /** This ID, rather than a mutable film ID, belongs on an existing show's plan. */
    public record Snapshot(String id, Film film, long transitionGapMs) {
        /** Explicit legacy program: old plans retain their original gapless timing. */
        public Snapshot(String id, Film film) { this(id, film, 0); }
        public Snapshot {
            id = FilmCatalog.id(id);
            if (film == null) throw error("snapshot_invalid");
            if (transitionGapMs != 0 && transitionGapMs != DEFAULT_TRANSITION_GAP_MS) throw error("transition_gap_invalid");
            addDuration(film.requireDurationMs(), (film.segments().size() - 1L) * transitionGapMs);
        }
        /** Trusted media only; transition time is never evidence about a source's duration. */
        public long mediaDurationMs() { return film.requireDurationMs(); }
        public long totalDurationMs() { return addDuration(mediaDurationMs(), (film.segments().size() - 1L) * transitionGapMs); }
        /** Program offset including preceding gaps; index == segment count returns the total, with no trailing gap. */
        public long segmentStartMs(int segmentIndex) {
            long mediaOffset = film.offsetMs(segmentIndex).orElseThrow(() -> error("duration_unknown"));
            return addDuration(mediaOffset, Math.min(segmentIndex, film.segments().size() - 1L) * transitionGapMs);
        }
        /**
         * Internal program-time lookup, not the current-clip user seek control. Gap positions have
         * no media target and are rejected; the exact next start and final program end are valid.
         * Gapless legacy snapshots retain Film.seek's boundary behavior.
         */
        public SeekTarget seek(long globalTimeMs) {
            long total = totalDurationMs();
            if (globalTimeMs < 0 || globalTimeMs > total) throw error("seek_range");
            long remaining = globalTimeMs;
            for (int i = 0; i < film.segments().size(); i++) {
                long duration = film.segments().get(i).durationMs().orElseThrow();
                if (remaining < duration || i == film.segments().size() - 1) return new SeekTarget(i, remaining, globalTimeMs == total);
                remaining -= duration;
                if (remaining < transitionGapMs) throw error("seek_gap");
                remaining -= transitionGapMs;
            }
            throw error("seek_range");
        }
    }

    private long revision;
    private final LinkedHashMap<String, Film> films = new LinkedHashMap<>();
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();

    public FilmCatalog() {}
    public long revision() { return revision; }
    public List<Film> films() { return List.copyOf(films.values()); }
    public List<Snapshot> snapshots() { return List.copyOf(snapshots.values()); }
    public Film film(String id) {
        Film film = films.get(id);
        if (film == null) throw error("film_missing");
        return film;
    }
    public Snapshot snapshot(String id) {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null) throw error("snapshot_missing");
        return snapshot;
    }
    public void requireRevision(long expected) {
        if (revision != expected) throw error("stale");
    }
    public Film create(long expectedCatalogRevision, Draft draft) {
        return create(expectedCatalogRevision, draft, null);
    }
    public Film create(long expectedCatalogRevision, Draft draft, Publication publication) {
        requireRevision(expectedCatalogRevision);
        if (draft == null) throw error("draft_invalid");
        if (films.size() >= MAX_FILMS) throw error("film_limit");
        List<Segment> segments = draft.normalizedSources().stream().map(s -> new Segment(s, null)).toList();
        Film film = new Film(newFilmId(), 1, draft.title(), draft.poster(), segments, publication);
        LinkedHashMap<String, Film> next = new LinkedHashMap<>(films);
        next.put(film.id(), film);
        commit(nextRevision(revision), next, snapshots);
        return film;
    }
    public Film update(String filmId, long expectedFilmRevision, Draft draft) {
        return update(filmId, expectedFilmRevision, draft, null);
    }
    public Film update(String filmId, long expectedFilmRevision, Draft draft, Publication publication) {
        Film old = requireFilmRevision(filmId, expectedFilmRevision);
        if (draft == null) throw error("draft_invalid");
        Map<String, Observation> retained = new LinkedHashMap<>();
        for (Segment segment : old.segments()) if (segment.observation() != null) retained.put(segment.sourceKey(), segment.observation());
        List<Segment> segments = draft.normalizedSources().stream().map(s -> new Segment(s, retained.get(s))).toList();
        if (old.title().equals(draft.title()) && old.poster().equals(draft.poster()) && old.segments().equals(segments) && java.util.Objects.equals(old.publication(), publication)) return old;
        // Equal sources may have different untrusted hints. Preserve each occurrence, never collapse them.
        Map<String, java.util.ArrayDeque<Long>> hints = new LinkedHashMap<>();
        for (int i = 0; i < old.segments().size(); i++) hints.computeIfAbsent(old.segments().get(i).sourceKey(), key -> new java.util.ArrayDeque<>()).addLast(old.displayMetadata().durationHintsMs().get(i));
        DisplayMetadata display = new DisplayMetadata(old.displayMetadata().format(), draft.normalizedSources().stream().map(s -> {
            var occurrences = hints.get(s); return occurrences == null || occurrences.isEmpty() ? -1L : occurrences.removeFirst();
        }).toList());
        Film changed = new Film(old.id(), nextRevision(old.revision()), draft.title(), draft.poster(), segments, publication, display);
        replace(changed);
        return changed;
    }
    /** Exchange-only candidate mutation. Caller must authorize the draft and atomically publish the whole candidate. */
    Film importDraft(long expectedCatalogRevision, String requestedId, boolean replace, Draft draft, DisplayMetadata display, Publication publication) {
        requireRevision(expectedCatalogRevision);
        id(requestedId);
        Film old = films.get(requestedId);
        if (replace && old == null || !replace && (old != null || snapshotHasFilm(requestedId))) throw error("exchange_id_conflict");
        if (!replace && films.size() >= MAX_FILMS) throw error("film_limit");
        if (draft == null) throw error("draft_invalid");
        // Imported durations are hints only; updates intentionally clear all old observations.
        List<Segment> segments = draft.normalizedSources().stream().map(source -> new Segment(source, null)).toList();
        Film film = new Film(requestedId, old == null ? 1 : nextRevision(old.revision()), draft.title(), draft.poster(), segments, publication, display);
        LinkedHashMap<String, Film> next = new LinkedHashMap<>(films);
        next.put(film.id(), film);
        commit(nextRevision(revision), next, snapshots);
        return film;
    }
    public boolean hasReservedFilmId(String id) { return films.containsKey(id) || snapshotHasFilm(id); }

    public void delete(String filmId, long expectedFilmRevision) {
        requireFilmRevision(filmId, expectedFilmRevision);
        LinkedHashMap<String, Film> next = new LinkedHashMap<>(films);
        next.remove(filmId);
        // Scheduled snapshots are independent of the registry entry and intentionally survive deletion.
        commit(nextRevision(revision), next, snapshots);
    }
    /**
     * Call only after the server verifies the actor, controller, exact terminal and CURRENT media source.
     * Client DTOs must never call this with submitted metadata. expectedSourceKey and provenance must
     * both match the selected stored source exactly. A nonpositive observation means unknown, not zero.
     * Every occurrence of the same source receives the same current observation, including repeats.
     * Evidence records an owner's observation of a source and media epoch, not content authentication.
     * The playback integration must recheck the evidence's current authorization/validity before use.
     */
    public Film observeTrusted(String filmId, long expectedFilmRevision, int segmentIndex, String expectedSourceKey,
                               boolean live, long durationMs, Provenance provenance) {
        Film old = requireFilmRevision(filmId, expectedFilmRevision);
        if (segmentIndex < 0 || segmentIndex >= old.segments().size()) throw error("segment_missing");
        String key = old.segments().get(segmentIndex).sourceKey();
        if (!key.equals(expectedSourceKey) || provenance == null || !key.equals(provenance.sourceKey())) throw error("source_mismatch");
        Observation observation = new Observation(live, live || durationMs <= 0 ? -1 : durationMs, provenance);
        List<Segment> segments = old.segments().stream().map(s -> s.sourceKey().equals(key) ? new Segment(key, observation) : s).toList();
        if (old.segments().equals(segments)) return old;
        Film changed = new Film(old.id(), nextRevision(old.revision()), old.title(), old.poster(), segments, old.publication(), old.displayMetadata());
        replace(changed);
        return changed;
    }
    /** Retains one immutable program per film revision and timing policy. Unknown/live programs cannot be scheduled. */
    public Snapshot snapshot(String filmId, long expectedFilmRevision) {
        Film film = requireFilmRevision(filmId, expectedFilmRevision);
        film.requireDurationMs();
        for (Snapshot old : snapshots.values()) if (old.film().id().equals(filmId) && old.film().revision() == expectedFilmRevision && old.transitionGapMs() == DEFAULT_TRANSITION_GAP_MS) return old;
        if (snapshots.size() >= MAX_SNAPSHOTS) throw error("snapshot_limit");
        String id;
        do { id = UUID.randomUUID().toString(); } while (snapshots.containsKey(id));
        Snapshot snapshot = new Snapshot(id, film, DEFAULT_TRANSITION_GAP_MS);
        LinkedHashMap<String, Snapshot> next = new LinkedHashMap<>(snapshots);
        next.put(id, snapshot);
        commit(nextRevision(revision), films, next);
        return snapshot;
    }
    /**
     * Server maintenance only: pass IDs from ALL surviving shows, including history and deleted films.
     * Missing IDs fail closed. Call after removing/replacing show references, never from a client list.
     */
    public void pruneSnapshots(Set<String> referencedSnapshotIds) {
        if (referencedSnapshotIds == null) throw error("snapshot_invalid");
        for (String id : referencedSnapshotIds) snapshot(id);
        LinkedHashMap<String, Snapshot> retained = new LinkedHashMap<>();
        for (Snapshot snapshot : snapshots.values()) if (referencedSnapshotIds.contains(snapshot.id())) retained.put(snapshot.id(), snapshot);
        if (retained.size() != snapshots.size()) commit(nextRevision(revision), films, retained);
    }

    private Film requireFilmRevision(String id, long expected) {
        Film film = film(id);
        if (film.revision() != expected) throw error("stale");
        return film;
    }
    private void replace(Film film) {
        LinkedHashMap<String, Film> next = new LinkedHashMap<>(films);
        next.put(film.id(), film);
        commit(nextRevision(revision), next, snapshots);
    }
    private void commit(long newRevision, Map<String, Film> newFilms, Map<String, Snapshot> newSnapshots) {
        FilmCatalogCodec.encodeValues(newRevision, newFilms.values(), newSnapshots.values());
        // Candidates can be the current maps, so snapshot them before replacing either collection.
        LinkedHashMap<String, Film> safeFilms = new LinkedHashMap<>(newFilms);
        LinkedHashMap<String, Snapshot> safeSnapshots = new LinkedHashMap<>(newSnapshots);
        films.clear(); films.putAll(safeFilms);
        snapshots.clear(); snapshots.putAll(safeSnapshots);
        revision = newRevision;
    }
    static FilmCatalog restore(long revision, Collection<Film> restoredFilms, Collection<Snapshot> restoredSnapshots) {
        if (revision < 0) throw error("revision_invalid");
        if (restoredFilms.size() > MAX_FILMS) throw error("film_limit");
        if (restoredSnapshots.size() > MAX_SNAPSHOTS) throw error("snapshot_limit");
        LinkedHashMap<String, Film> films = new LinkedHashMap<>();
        LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
        for (Film film : restoredFilms) if (films.putIfAbsent(film.id(), film) != null) throw error("duplicate_film");
        Set<String> versions = new java.util.HashSet<>();
        Map<String, Film> savedVersions = new LinkedHashMap<>();
        for (Snapshot snapshot : restoredSnapshots) {
            if (snapshots.putIfAbsent(snapshot.id(), snapshot) != null) throw error("duplicate_snapshot");
            Film saved = snapshot.film(), current = films.get(saved.id());
            String savedVersion = saved.id() + ":" + saved.revision();
            if (!versions.add(savedVersion + ":" + snapshot.transitionGapMs())) throw error("duplicate_snapshot_revision");
            Film sameRevision = savedVersions.putIfAbsent(savedVersion, saved);
            if (sameRevision != null && !sameRevision.equals(saved)) throw error("snapshot_revision_mismatch");
            if (current != null && (saved.revision() > current.revision() || saved.revision() == current.revision() && !saved.equals(current))) throw error("snapshot_revision_mismatch");
        }
        if (revision == 0 && (!films.isEmpty() || !snapshots.isEmpty())) throw error("revision_invalid");
        for (Film film : films.values()) if (film.revision() > revision) throw error("revision_invalid");
        for (Snapshot snapshot : snapshots.values()) if (snapshot.film().revision() > revision) throw error("revision_invalid");
        FilmCatalog result = new FilmCatalog();
        result.commit(revision, films, snapshots);
        return result;
    }
    private String newFilmId() {
        String id;
        do { id = UUID.randomUUID().toString(); } while (films.containsKey(id) || snapshotHasFilm(id));
        return id;
    }
    private boolean snapshotHasFilm(String id) { return snapshots.values().stream().anyMatch(s -> s.film().id().equals(id)); }
    private static long nextRevision(long value) {
        if (value == Long.MAX_VALUE) throw error("revision_exhausted");
        return value + 1;
    }
    private static long addDuration(long a, long b) {
        try { return Math.addExact(a, b); } catch (ArithmeticException e) { throw error("duration_overflow"); }
    }
    private static String title(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TITLE_LENGTH || !wellFormed(value)) throw error("title_invalid");
        return value.trim();
    }
    private static String poster(String value) {
        if (value == null || value.length() > MAX_SOURCE_LENGTH || !wellFormed(value) || !value.equals(value.trim())) throw error("poster_invalid");
        return value;
    }
    private static String source(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_SOURCE_LENGTH || !wellFormed(value) || !value.equals(value.trim())) throw error("source_invalid");
        return value;
    }
    private static String id(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw error("id_invalid"); }
        catch (IllegalArgumentException e) { throw error("id_invalid"); }
        return value;
    }
    private static boolean wellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private static CatalogException error(String key) { return new CatalogException(key); }
}
