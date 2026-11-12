package local.cinemamanager.theater;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.network.chat.Component;

/** Source-free presentation policy shared by MyCinema programme screens. */
public final class CinemaProgrammeUi {
    private static final Set<String> ACTIVE = Set.of("PREPARING", "READY", "RUNNING", "PLAYING", "PAUSED", "GAP");
    private static final Set<String> SEEKABLE = Set.of("RUNNING", "PLAYING", "PAUSED");

    private CinemaProgrammeUi() {}

    public static Component text(String key, Object... args) {
        return Component.translatable("mycinema.programme." + key, args);
    }

    public static String clock(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1_000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60);
    }

    public static Component summary(CinemaProgramme.View view) {
        if (view == null) return text("unavailable");
        return text("summary", view.clips().size(), clock(view.totalDurationMs()), view.gapDurationMs() / 1_000,
                Math.max(0, view.clips().size() - 1));
    }

    public static Component current(CinemaProgramme.View view) {
        if (view == null) return text("unavailable");
        return text("current", view.currentIndex() + 1, view.clips().size(), clock(view.clipPositionMs()), clock(view.clipDurationMs()));
    }

    public static Component status(CinemaProgramme.View view) {
        if (view == null) return text("unavailable");
        if ("GAP".equals(view.status())) {
            return text(view.desiredPaused() ? "gap_paused" : "gap",
                    Math.ceil(view.gapRemainingMs() / 100.0) / 10.0,
                    view.pendingIndex() + 1);
        }
        String key = switch (view.status()) {
            case "PREPARING" -> "preparing";
            case "READY" -> "ready";
            case "RUNNING", "PLAYING" -> view.desiredPaused() ? "paused" : "playing";
            case "PAUSED" -> "paused";
            case "STOP_PENDING" -> "stop_pending";
            case "STOPPED" -> "stopped";
            case "CANCELLED" -> "cancelled";
            case "OVERRIDDEN" -> "overridden";
            case "COMPLETED", "FINISHED", "ENDED" -> "completed";
            case "FAILED" -> "failed";
            default -> "unavailable";
        };
        Component state = text(key);
        return view.failureCode().isBlank()
                ? state
                : Component.empty().append(state).append(" · ").append(text("failure", view.failureCode()));
    }

    public static boolean sameGuard(CinemaProgramme.View first, CinemaProgramme.View second) {
        return first != null && second != null
                && Objects.equals(first.run(), second.run())
                && first.operationRevision() == second.operationRevision()
                && first.currentIndex() == second.currentIndex();
    }

    public static boolean active(CinemaProgramme.View view) {
        return view != null && ACTIVE.contains(view.status());
    }

    public static boolean seekable(CinemaProgramme.View view) {
        return view != null && view.clipDurationMs() > 0 && SEEKABLE.contains(view.status());
    }

    public static boolean selectable(CinemaProgramme.View view) {
        return seekable(view) || view != null && "GAP".equals(view.status());
    }

    public static int selectionIndex(CinemaProgramme.View view) {
        if (view == null || view.clips().isEmpty()) return 0;
        int preferred = view.pendingIndex() >= 0 ? view.pendingIndex() : view.currentIndex();
        return Math.clamp(preferred, 0, view.clips().size() - 1);
    }
}
