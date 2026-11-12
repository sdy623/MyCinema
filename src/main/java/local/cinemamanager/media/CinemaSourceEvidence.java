package local.cinemamanager.media;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * MyCinema-owned server evidence for media metadata observed by a real terminal.
 * Evidence is bounded, dimension-local, and never grants authority for a
 * non-canonical source.
 */
public final class CinemaSourceEvidence extends SavedData {
    public static final int MAX_RECORDS = 1024;
    public static final long MAX_DURATION_MS = 2_678_400_000L;

    private static final String DATA_NAME = "mycinema_source_evidence";
    private static final String LEGACY_DATA_NAME = "waterframes_source_evidence";
    private final LinkedHashMap<UUID, Evidence> records = new LinkedHashMap<>();
    private boolean legacyMigrated;

    public record Evidence(UUID id, UUID owner, UUID terminal, String url, int source,
                           long durationMs, long observedAtMs) {
        public Evidence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(terminal, "terminal");
            Objects.requireNonNull(url, "url");
        }

        public boolean matches(UUID expectedOwner, String expectedUrl, int expectedSource) {
            return owner.equals(expectedOwner) && url.equals(expectedUrl) && source == expectedSource;
        }
    }

    public static CinemaSourceEvidence get(ServerLevel level) {
        var storage = level.getDataStorage();
        var factory = new Factory<>(CinemaSourceEvidence::new, CinemaSourceEvidence::load);
        var current = storage.computeIfAbsent(factory, DATA_NAME);
        if (!current.legacyMigrated) {
            var legacy = storage.get(factory, LEGACY_DATA_NAME);
            if (legacy != null) current.mergeLegacy(legacy);
            current.legacyMigrated = true;
            current.setDirty();
        }
        return current;
    }

    public Evidence find(UUID id) {
        return id == null ? null : records.get(id);
    }

    public int size() {
        return records.size();
    }

    public Evidence remember(UUID owner, UUID terminal, String url, int source, long durationMs) {
        if (!valid(owner, terminal, url, source, durationMs)) return null;
        long now = Math.max(0, System.currentTimeMillis());
        Evidence prior = matching(owner, url, source);
        if (prior != null && prior.durationMs == durationMs) return prior;
        UUID id = UUID.randomUUID();
        if (prior != null) records.remove(prior.id);
        Evidence evidence = new Evidence(id, owner, terminal, url, source, durationMs, now);
        records.put(id, evidence);
        trim();
        setDirty();
        return evidence;
    }

    public boolean forget(UUID id) {
        if (id == null || records.remove(id) == null) return false;
        setDirty();
        return true;
    }

    public static boolean valid(UUID owner, UUID terminal, String url, int source, long durationMs) {
        return owner != null && terminal != null && url != null && url.equals(MediaSource.normalize(url))
                && source >= 0 && source <= 16_384 && durationMs > 0 && durationMs <= MAX_DURATION_MS;
    }

    private Evidence matching(UUID owner, String url, int source) {
        for (Evidence evidence : records.values()) if (evidence.matches(owner, url, source)) return evidence;
        return null;
    }

    private void mergeLegacy(CinemaSourceEvidence legacy) {
        List<Evidence> current = new ArrayList<>(records.values());
        records.clear();
        for (Evidence evidence : legacy.records.values()) restore(evidence);
        for (Evidence evidence : current) restore(evidence);
    }

    private void restore(Evidence evidence) {
        if (!valid(evidence.owner, evidence.terminal, evidence.url, evidence.source, evidence.durationMs)) return;
        Evidence duplicate = matching(evidence.owner, evidence.url, evidence.source);
        if (duplicate != null) records.remove(duplicate.id);
        records.remove(evidence.id);
        records.put(evidence.id, evidence);
        trim();
    }

    private void trim() {
        while (records.size() > MAX_RECORDS) records.remove(records.keySet().iterator().next());
    }

    public static CinemaSourceEvidence load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new CinemaSourceEvidence();
        data.legacyMigrated = tag.getBoolean("legacy_migrated");
        ListTag rows = tag.getList("observations", Tag.TAG_COMPOUND);
        int start = Math.max(0, rows.size() - MAX_RECORDS * 2);
        for (int i = start; i < rows.size(); i++) {
            try {
                CompoundTag row = rows.getCompound(i);
                if (!row.hasUUID("id") || !row.hasUUID("owner") || !row.hasUUID("terminal")
                        || !row.contains("url", Tag.TAG_STRING) || !row.contains("source", Tag.TAG_INT)
                        || !row.contains("duration", Tag.TAG_LONG) || !row.contains("observed_at", Tag.TAG_LONG)) continue;
                var evidence = new Evidence(row.getUUID("id"), row.getUUID("owner"), row.getUUID("terminal"),
                        row.getString("url"), row.getInt("source"), row.getLong("duration"),
                        Math.max(0, row.getLong("observed_at")));
                data.restore(evidence);
            } catch (RuntimeException malformed) {
                // A malformed row cannot become playback authority.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("legacy_migrated", legacyMigrated);
        ListTag rows = new ListTag();
        for (Map.Entry<UUID, Evidence> entry : records.entrySet()) {
            Evidence evidence = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putUUID("id", evidence.id);
            row.putUUID("owner", evidence.owner);
            row.putUUID("terminal", evidence.terminal);
            row.putString("url", evidence.url);
            row.putInt("source", evidence.source);
            row.putLong("duration", evidence.durationMs);
            row.putLong("observed_at", evidence.observedAtMs);
            rows.add(row);
        }
        tag.put("observations", rows);
        return tag;
    }
}
