package local.cinemamanager.media;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class CinemaSourceEvidenceChecks {
    public static void main(String[] args) {
        exactObservationKeepsItsIdentity();
        changedDurationReplacesThePriorObservation();
        invalidObservationsNeverBecomeAuthority();
        persistenceRoundTripIsBoundedAndSkipsMalformedRows();
        System.out.println("CinemaSourceEvidenceChecks PASS");
    }

    private static void exactObservationKeepsItsIdentity() {
        var store = new CinemaSourceEvidence();
        UUID owner = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        var first = store.remember(owner, terminal, "https://example.test/feature.mp4", 0, 90_000);
        var repeated = store.remember(owner, terminal, "https://example.test/feature.mp4", 0, 90_000);
        check(first != null && repeated != null && first.id().equals(repeated.id()), "same observation changed evidence identity");
        check(store.size() == 1 && repeated.matches(owner, "https://example.test/feature.mp4", 0), "same observation duplicated authority");
    }

    private static void changedDurationReplacesThePriorObservation() {
        var store = new CinemaSourceEvidence();
        UUID owner = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        var first = store.remember(owner, terminal, "water://feature", 2, 90_000);
        var changed = store.remember(owner, terminal, "water://feature", 2, 91_000);
        check(first != null && changed != null && !first.id().equals(changed.id()), "changed duration reused stale evidence identity");
        check(store.find(first.id()) == null && store.find(changed.id()).equals(changed) && store.size() == 1,
                "changed observation did not retire the previous record");
    }

    private static void invalidObservationsNeverBecomeAuthority() {
        var store = new CinemaSourceEvidence();
        UUID owner = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        check(store.remember(null, terminal, "water://feature", 0, 1) == null, "missing owner was accepted");
        check(store.remember(owner, null, "water://feature", 0, 1) == null, "missing terminal was accepted");
        check(store.remember(owner, terminal, " HTTPS://example.test/feature ", 0, 1) == null, "non-canonical source was accepted");
        check(store.remember(owner, terminal, "water://feature", -1, 1) == null, "negative source index was accepted");
        check(store.remember(owner, terminal, "water://feature", 0, 0) == null, "unknown duration was accepted as evidence");
        check(store.remember(owner, terminal, "water://feature", 0, CinemaSourceEvidence.MAX_DURATION_MS + 1) == null,
                "unbounded duration was accepted");
        check(store.size() == 0, "invalid observations changed the evidence store");
    }

    private static void persistenceRoundTripIsBoundedAndSkipsMalformedRows() {
        var store = new CinemaSourceEvidence();
        UUID terminal = UUID.randomUUID();
        UUID firstId = null;
        for (int i = 0; i <= CinemaSourceEvidence.MAX_RECORDS; i++) {
            var evidence = store.remember(UUID.randomUUID(), terminal, "water://evidence-" + i, 0, i + 1L);
            if (i == 0) firstId = evidence.id();
        }
        check(store.size() == CinemaSourceEvidence.MAX_RECORDS && store.find(firstId) == null, "oldest evidence was not evicted at the hard bound");

        CompoundTag saved = store.save(new CompoundTag(), null);
        saved.getList("observations", Tag.TAG_COMPOUND).add(new CompoundTag());
        var loaded = CinemaSourceEvidence.load(saved, null);
        check(loaded.size() == CinemaSourceEvidence.MAX_RECORDS, "persistence changed the bounded valid record count");
        check(loaded.save(new CompoundTag(), null).getList("observations", Tag.TAG_COMPOUND).size() == CinemaSourceEvidence.MAX_RECORDS,
                "malformed row survived the persistence boundary");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
