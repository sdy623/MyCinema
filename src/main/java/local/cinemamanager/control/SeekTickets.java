package local.cinemamanager.control;

import java.util.*;

/** Ephemeral, one-use server leases. No URLs, tokens or actor grants are persisted. */
public final class SeekTickets {
    public static final long LIFETIME_TICKS = 300;
    private static final int MAX_TICKETS = 1024;
    public record Session(UUID actor, String hall, UUID terminal, Object clock, String source,
                          int revision, long duration, boolean live, boolean paused) {}
    private record Ticket(Session session, long issued) {}
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    public String issue(Session session, long tick) {
        prune(tick);
        if (!seekable(session)) return "";
        while (tickets.size() >= MAX_TICKETS) tickets.remove(tickets.keySet().iterator().next());
        String token = UUID.randomUUID().toString();
        tickets.put(token, new Ticket(session, tick));
        return token;
    }
    public boolean consume(String token, Session current, long tick, long target) {
        Ticket ticket = tickets.remove(token);
        if (ticket == null || tick < ticket.issued || tick - ticket.issued > LIFETIME_TICKS
                || !seekable(current) || target < 0 || target > current.duration) return false;
        Session before = ticket.session;
        return before.actor.equals(current.actor) && before.hall.equals(current.hall)
                && before.terminal.equals(current.terminal) && before.clock == current.clock
                && before.source.equals(current.source) && before.revision == current.revision
                && before.duration == current.duration && before.live == current.live
                && before.paused == current.paused;
    }
    private static boolean seekable(Session s) {
        return s != null && s.actor != null && s.hall != null && s.terminal != null && s.clock != null
                && s.source != null && !s.source.isBlank() && s.duration > 0 && !s.live;
    }
    private void prune(long tick) { tickets.entrySet().removeIf(e -> tick < e.getValue().issued || tick-e.getValue().issued>LIFETIME_TICKS); }
}
