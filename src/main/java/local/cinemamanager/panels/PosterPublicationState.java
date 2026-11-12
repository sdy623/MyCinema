package local.cinemamanager.panels;

import java.util.Objects;
import java.util.UUID;

/** Server-private selection identity; only its random token is included in public block updates. */
public final class PosterPublicationState {
    private String selection = "", source = "", token = "";
    public String select(String selected, String approvedSource) {
        Objects.requireNonNull(selected);
        Objects.requireNonNull(approvedSource);
        if (!selection.equals(selected) || !source.equals(approvedSource)) {
            selection = selected;
            source = approvedSource;
            token = approvedSource.isBlank() ? "" : UUID.randomUUID().toString();
        }
        return token;
    }
    public void clear() { selection = ""; source = ""; token = ""; }
    public static boolean validToken(String token) {
        try { return token != null && UUID.fromString(token).toString().equals(token); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
