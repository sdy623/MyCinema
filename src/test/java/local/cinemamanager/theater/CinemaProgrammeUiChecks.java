package local.cinemamanager.theater;

import java.util.List;
import java.util.UUID;

public final class CinemaProgrammeUiChecks {
    public static void main(String[] args) {
        clockIsStableAndNeverNegative();
        optimisticGuardUsesRunRevisionAndCurrentClip();
        activeAndSeekableStatesStayNarrow();
        selectableAddsOnlyTheGapState();
        System.out.println("CinemaProgrammeUiChecks PASS");
    }

    private static void clockIsStableAndNeverNegative() {
        check(CinemaProgrammeUi.clock(-1).equals("00:00:00"), "negative clock leaked into UI");
        check(CinemaProgrammeUi.clock(3_661_999).equals("01:01:01"), "clock format changed");
    }

    private static void optimisticGuardUsesRunRevisionAndCurrentClip() {
        UUID run = UUID.randomUUID();
        var original = view(run, 8, 1, "PLAYING", 1_000);
        check(CinemaProgrammeUi.sameGuard(original, view(run, 8, 1, "PAUSED", 1_000)), "status-only refresh invalidated a command");
        check(!CinemaProgrammeUi.sameGuard(original, view(run, 9, 1, "PLAYING", 1_000)), "revision change kept a stale command");
        check(!CinemaProgrammeUi.sameGuard(original, view(run, 8, 0, "PLAYING", 1_000)), "clip change kept a stale command");
        check(!CinemaProgrammeUi.sameGuard(original, view(UUID.randomUUID(), 8, 1, "PLAYING", 1_000)), "run change kept a stale command");
    }

    private static void activeAndSeekableStatesStayNarrow() {
        for (String status : List.of("PREPARING", "READY", "RUNNING", "PLAYING", "PAUSED", "GAP")) {
            check(CinemaProgrammeUi.active(view(UUID.randomUUID(), 1, 0, status, 1_000)), status + " should remain active");
        }
        for (String status : List.of("STOP_PENDING", "STOPPED", "CANCELLED", "OVERRIDDEN", "COMPLETED", "FINISHED", "ENDED", "FAILED")) {
            check(!CinemaProgrammeUi.active(view(UUID.randomUUID(), 1, 0, status, 1_000)), status + " should not remain active");
        }
        for (String status : List.of("RUNNING", "PLAYING", "PAUSED")) {
            check(CinemaProgrammeUi.seekable(view(UUID.randomUUID(), 1, 0, status, 1_000)), status + " should remain seekable");
        }
        check(!CinemaProgrammeUi.seekable(view(UUID.randomUUID(), 1, 0, "PLAYING", 0)), "unknown duration became seekable");
        check(!CinemaProgrammeUi.seekable(view(UUID.randomUUID(), 1, 0, "GAP", 1_000)), "gap became seekable");
    }

    private static void selectableAddsOnlyTheGapState() {
        check(CinemaProgrammeUi.selectable(view(UUID.randomUUID(), 1, 0, "PLAYING", 1_000)), "playing clip should be selectable");
        check(CinemaProgrammeUi.selectable(view(UUID.randomUUID(), 1, 0, "GAP", 1_000)), "gap should permit choosing a clip");
        check(!CinemaProgrammeUi.selectable(view(UUID.randomUUID(), 1, 0, "READY", 1_000)), "ready state unexpectedly permits choosing a clip");
    }

    private static CinemaProgramme.View view(UUID run, long revision, int current, String status, long duration) {
        return new CinemaProgramme.View(
                CinemaProgramme.SCHEMA,
                run,
                revision,
                "Programme",
                List.of(
                        new CinemaProgramme.Clip(0, "One", duration),
                        new CinemaProgramme.Clip(1, "Two", duration)
                ),
                current,
                -1,
                0,
                duration,
                duration * 2,
                status,
                false,
                "",
                3_000,
                0
        );
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
