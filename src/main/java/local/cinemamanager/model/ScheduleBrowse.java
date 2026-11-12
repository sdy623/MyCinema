package local.cinemamanager.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Stable IDs and deterministic pages; no media or game access. Empty hall filter means all halls. */
public final class ScheduleBrowse {
    public static final int PAGE_SIZE=5;
    private ScheduleBrowse() {}
    public static List<ManagerState.Show> filtered(ManagerState state,String hallId) {
        return state.shows.stream().filter(s -> hallId.isEmpty() || s.hallId.equals(hallId))
                .sorted(Comparator.comparingLong((ManagerState.Show s)->s.start).thenComparing(s->s.id)).toList();
    }
    public static int pages(List<ManagerState.Show> list) { return Math.max(1,(list.size()+PAGE_SIZE-1)/PAGE_SIZE); }
    public static int clampPage(List<ManagerState.Show> list,int page) { return Math.clamp(page,0,pages(list)-1); }
    public static List<ManagerState.Show> page(List<ManagerState.Show> list,int page) {
        int start=clampPage(list,page)*PAGE_SIZE;return list.subList(start,Math.min(start+PAGE_SIZE,list.size()));
    }
    public static ManagerState.Show selected(ManagerState state,String id,String hallId) {
        return state.shows.stream().filter(s -> Objects.equals(s.id,id) && (hallId.isEmpty() || s.hallId.equals(hallId))).findFirst().orElse(null);
    }
}
