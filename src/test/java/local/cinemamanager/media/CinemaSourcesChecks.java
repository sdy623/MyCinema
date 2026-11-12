package local.cinemamanager.media;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.watermedia.api.player.MediaPlayer;

public final class CinemaSourcesChecks {
    public static void main(String[] args) {
        sourceValidationRequiresCanonicalBoundedInput();
        controllerReferencesDetachFromMutablePositions();
        sessionAndResultCarryTheCompatibilityContract();
        System.out.println("CinemaSourcesChecks PASS");
    }

    private static void sourceValidationRequiresCanonicalBoundedInput() {
        check(CinemaSources.validSource(new CinemaSources.SourceRef("https://example.test/feature.mp4", 0)),
                "canonical source was rejected");
        check(CinemaSources.validSource(new CinemaSources.SourceRef("water://feature", 16_384)),
                "highest supported source index was rejected");
        check(!CinemaSources.validSource(new CinemaSources.SourceRef(" HTTPS://example.test/feature.mp4 ", 0)),
                "non-canonical source was accepted");
        check(!CinemaSources.validSource(new CinemaSources.SourceRef("water://feature", -1)),
                "negative source index was accepted");
        check(!CinemaSources.validSource(new CinemaSources.SourceRef("water://feature", 16_385)),
                "unbounded source index was accepted");
        check(!CinemaSources.validSource(null), "missing source was accepted");
    }

    private static void controllerReferencesDetachFromMutablePositions() {
        var mutable = new BlockPos.MutableBlockPos(4, 5, 6);
        UUID identity = UUID.randomUUID();
        var reference = new CinemaSources.ControllerRef(mutable, identity);
        mutable.set(9, 9, 9);
        check(reference.pos().equals(new BlockPos(4, 5, 6)) && reference.identity().equals(identity),
                "controller reference retained mutable caller state");
    }

    private static void sessionAndResultCarryTheCompatibilityContract() {
        UUID terminal = UUID.randomUUID(), epoch = UUID.randomUUID(), evidence = UUID.randomUUID();
        var session = new CinemaSources.SessionView(terminal, epoch, "water://feature", 2, 7,
                MediaPlayer.Status.PAUSED, 1_000, 90_000, false, true, evidence, true, "");
        var result = new CinemaSources.Result(null, session);
        check(result.error() == null && result.session().terminal().equals(terminal)
                        && result.session().epoch().equals(epoch) && result.session().evidenceId().equals(evidence)
                        && result.session().status() == MediaPlayer.Status.PAUSED,
                "source session contract lost compatibility data");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
