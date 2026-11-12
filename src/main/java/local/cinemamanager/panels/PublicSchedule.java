package local.cinemamanager.panels;

import local.cinemamanager.model.ManagerState;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Deterministic schedule selection. The resolver reads trusted media facts without changing playback. */
public final class PublicSchedule {
    private PublicSchedule() {}
    private static String timezone(ManagerState state) {
        if (state == null || state.timezone == null || state.timezone.isBlank() || state.timezone.length() > 128) return "";
        try { return ZoneId.of(state.timezone).getId(); }
        catch (DateTimeException ignored) { return ""; }
    }
    private static Map<String, ManagerState.Hall> halls(ManagerState state) {
        Map<String, ManagerState.Hall> halls = new HashMap<>();
        for (var hall : state.halls) if (hall != null && hall.id != null && hall.name != null && hall.name.length() <= 60)
            halls.put(hall.id, hall);
        return halls;
    }
    private static List<ManagerState.Show> selected(ManagerState state, PanelScope scope, String hallId, long now,
                                                     Map<String, ManagerState.Hall> halls, Predicate<ManagerState.Show> eligible) {
        List<ManagerState.Show> selected = new ArrayList<>();
        for (var show : state.shows) {
            boolean activeFilm=show!=null&&ManagerState.filmProgramme(show)
                    &&(show.status==ManagerState.Status.PREPARING||show.status==ManagerState.Status.RUNNING)&&show.start<=now;
            if (show == null || show.id == null || show.id.isBlank() || show.id.length() > 80
                    || show.title == null || show.title.isBlank() || show.title.length() > 80
                    || show.url == null || show.url.isBlank() || show.url.length() > 2048
                    || !halls.containsKey(show.hallId) || show.start >= show.end || (show.end <= now&&!activeFilm)
                    || scope == PanelScope.HALL && !Objects.equals(show.hallId, hallId) || !eligible.test(show)) continue;
            if (show.status == ManagerState.Status.PENDING
                    || (show.status == ManagerState.Status.PREPARING || show.status == ManagerState.Status.RUNNING) && show.start <= now) selected.add(show);
        }
        Comparator<ManagerState.Show> order = Comparator.comparingLong((ManagerState.Show s) -> s.start)
                .thenComparing(s -> s.hallId).thenComparing(s -> s.id);
        if (scope == PanelScope.HALL) order = Comparator.comparingInt((ManagerState.Show s) -> s.status == ManagerState.Status.RUNNING ? 0 : s.start <= now ? 1 : 2).thenComparing(order);
        selected.sort(order);
        return selected;
    }
    /** Server-only selection for the poster. It is exactly the first public hall row, never a catalog lookup. */
    public static ManagerState.Show firstHallShow(ManagerState state, String hallId, long now) {
        return firstHallShow(state, hallId, now, show -> true);
    }
    public static ManagerState.Show firstHallShow(ManagerState state, String hallId, long now,
                                                  Predicate<ManagerState.Show> eligible) {
        if (timezone(state).isEmpty()) return null;
        List<ManagerState.Show> shows = selected(state, PanelScope.HALL, hallId, now, halls(state), eligible);
        return shows.isEmpty() ? null : shows.getFirst();
    }
    public static PublicPanelSnapshot project(ManagerState state, PanelScope scope, String hallId,
                                              long now, ToLongFunction<ManagerState.Show> duration) {
        return project(state, scope, hallId, now, duration, show -> true);
    }
    public static PublicPanelSnapshot project(ManagerState state, PanelScope scope, String hallId,
                                              long now, ToLongFunction<ManagerState.Show> duration,
                                              Predicate<ManagerState.Show> eligible) {
        if (state == null || scope == null || scope == PanelScope.MANUAL) return PublicPanelSnapshot.EMPTY;
        String zone = timezone(state);
        if (zone.isEmpty()) return PublicPanelSnapshot.EMPTY;
        Map<String, ManagerState.Hall> halls = halls(state);
        List<PublicShowRow> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var show : selected(state, scope, hallId, now, halls, eligible)) {
            if (rows.size() >= ManagerState.MAX_SHOWS) break;
            if (!ids.add(show.id)) continue;
            long milliseconds = 0;
            try { milliseconds = Math.max(0, duration.applyAsLong(show)); } catch (RuntimeException ignored) { }
            rows.add(new PublicShowRow(show.id, show.title, halls.get(show.hallId).name, show.start, milliseconds, show.end));
        }
        return new PublicPanelSnapshot(zone, now, rows);
    }
}
