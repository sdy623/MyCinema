package local.cinemamanager.theater;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** MyCinema-owned programme snapshot and optimistic control request. */
public final class CinemaProgramme {
    public static final int SCHEMA = 1;
    public static final int MAX_CLIPS = 64;

    private CinemaProgramme() {}

    public enum Action {
        PLAY,
        PAUSE,
        STOP,
        SEEK_CURRENT,
        PREVIOUS,
        NEXT,
        CHOOSE
    }

    public record Clip(int index, String label, long durationMs) {
        public Clip {
            label = Objects.requireNonNull(label, "label");
        }
    }

    public record View(
            int schema,
            UUID run,
            long operationRevision,
            String title,
            List<Clip> clips,
            int currentIndex,
            int pendingIndex,
            long clipPositionMs,
            long clipDurationMs,
            long totalDurationMs,
            String status,
            boolean desiredPaused,
            String failureCode,
            long gapDurationMs,
            long gapRemainingMs
    ) {
        public View {
            run = Objects.requireNonNull(run, "run");
            title = Objects.requireNonNull(title, "title");
            clips = List.copyOf(Objects.requireNonNull(clips, "clips"));
            status = Objects.requireNonNull(status, "status");
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            if (clips.size() > MAX_CLIPS) throw new IllegalArgumentException("too many programme clips");
        }
    }

    public record Request(Action action, UUID run, long operationRevision, int currentIndex, long value) {
        public Request {
            action = Objects.requireNonNull(action, "action");
            run = Objects.requireNonNull(run, "run");
        }
    }
}
