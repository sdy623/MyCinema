package local.cinemamanager.theater;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CinemaProgrammeChecks {
    public static void main(String[] args) {
        actionsCoverEveryOperatorCommand();
        viewOwnsAnImmutableClipSnapshot();
        viewSupportsTheExistingSixtyFourClipWireLimit();
        requestKeepsOptimisticConcurrencyFields();
        System.out.println("CinemaProgrammeChecks PASS");
    }

    private static void viewSupportsTheExistingSixtyFourClipWireLimit() {
        var clips = new ArrayList<CinemaProgramme.Clip>();
        for (int i = 0; i < 64; i++) clips.add(new CinemaProgramme.Clip(i, Integer.toString(i + 1), 1_000));
        view(clips);
        clips.add(new CinemaProgramme.Clip(64, "65", 1_000));
        try {
            view(clips);
            throw new AssertionError("view accepted more than the 64-clip compatibility limit");
        } catch (IllegalArgumentException expected) {
            // Existing programme packets cap the public clip list at 64 entries.
        }
    }

    private static CinemaProgramme.View view(List<CinemaProgramme.Clip> clips) {
        return new CinemaProgramme.View(
                CinemaProgramme.SCHEMA,
                UUID.randomUUID(),
                1,
                "Programme",
                clips,
                0,
                -1,
                0,
                1_000,
                clips.size() * 1_000L,
                "READY",
                true,
                "",
                0,
                0
        );
    }

    private static void actionsCoverEveryOperatorCommand() {
        var actual = List.of(CinemaProgramme.Action.values());
        check(actual.equals(List.of(
                CinemaProgramme.Action.PLAY,
                CinemaProgramme.Action.PAUSE,
                CinemaProgramme.Action.STOP,
                CinemaProgramme.Action.SEEK_CURRENT,
                CinemaProgramme.Action.PREVIOUS,
                CinemaProgramme.Action.NEXT,
                CinemaProgramme.Action.CHOOSE
        )), "operator action order changed");
    }

    private static void viewOwnsAnImmutableClipSnapshot() {
        var mutable = new ArrayList<CinemaProgramme.Clip>();
        mutable.add(new CinemaProgramme.Clip(0, "Feature", 90_000));
        var view = new CinemaProgramme.View(
                CinemaProgramme.SCHEMA,
                UUID.randomUUID(),
                7,
                "Programme",
                mutable,
                0,
                -1,
                1_000,
                90_000,
                90_000,
                "PLAYING",
                false,
                "",
                3_000,
                0
        );

        mutable.clear();
        check(view.clips().size() == 1, "view retained caller-owned clip list");
        try {
            view.clips().clear();
            throw new AssertionError("view clips remained mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: public snapshots cannot mutate server state.
        }
    }

    private static void requestKeepsOptimisticConcurrencyFields() {
        UUID run = UUID.randomUUID();
        var request = new CinemaProgramme.Request(CinemaProgramme.Action.SEEK_CURRENT, run, 11, 2, 1_234);
        check(request.action() == CinemaProgramme.Action.SEEK_CURRENT, "action changed");
        check(request.run().equals(run), "run identity changed");
        check(request.operationRevision() == 11, "operation revision changed");
        check(request.currentIndex() == 2, "clip index changed");
        check(request.value() == 1_234, "request value changed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
