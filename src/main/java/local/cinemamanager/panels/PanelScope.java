package local.cinemamanager.panels;

/** A lobby is explicitly bound to one manager; an empty hall never implies lobby access. */
public enum PanelScope {
    HALL, LOBBY, MANUAL;
    public static PanelScope savedOrDefault(String value) {
        try { return valueOf(value); } catch (RuntimeException ignored) { return HALL; }
    }
}
