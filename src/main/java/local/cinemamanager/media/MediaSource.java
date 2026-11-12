package local.cinemamanager.media;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/** Canonical, bounded media-source identity owned by MyCinema. */
public final class MediaSource {
    public static final int MAX_LENGTH = 2_048;
    private static final Set<String> NETWORK_SCHEMES = Set.of("http", "https", "water");

    private MediaSource() {}

    public static String normalize(String input) {
        if (input == null || controls(input)) return null;
        String value = input.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH || controls(value)
                || value.startsWith("\\") || value.startsWith("//")) return null;
        try {
            if (value.matches("^[A-Za-z]:[\\\\/].*")) {
                return local(new URI("file", null, "/" + value.replace('\\', '/'), null));
            }
            if (value.startsWith("/")) return local(new URI("file", null, value, null));
            if (relative(value)) return value.replace('\\', '/');
            if (value.regionMatches(true, 0, "file:", 0, 5)) value = value.replace('\\', '/');

            URI uri = new URI(value.replace(" ", "%20"));
            if (uri.isOpaque() || uri.getScheme() == null || uri.getUserInfo() != null) return null;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("file")) return local(uri);
            if (!NETWORK_SCHEMES.contains(scheme) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getPort() == 0 || uri.getPort() > 65_535) return null;
            return bounded(scheme + uri.toASCIIString().substring(uri.getScheme().length()));
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            return null;
        }
    }

    public static boolean relative(String value) {
        return value != null && !value.isBlank() && !value.equals(".") && !value.equals("..")
                && value.indexOf(':') < 0
                && !value.startsWith("/") && !value.startsWith("\\")
                && !value.endsWith("/") && !value.endsWith("\\")
                && (value.contains("/") || value.contains("\\") || value.contains("."));
    }

    public static boolean isNormalizedLocal(String value) {
        return value != null && value.equals(normalize(value))
                && (value.startsWith("file:/") || relative(value));
    }

    private static String local(URI uri) throws URISyntaxException {
        if (uri.isOpaque() || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getQuery() != null || uri.getFragment() != null) return null;
        String authority = uri.getRawAuthority();
        if (authority != null && !authority.isEmpty() && !authority.equalsIgnoreCase("localhost")) return null;
        String path = uri.getPath();
        if (path == null || controls(path)) return null;
        path = path.replace('\\', '/');
        if (!path.startsWith("/") || path.startsWith("//") || path.endsWith("/")) return null;
        return bounded(new URI("file", null, path, null).toASCIIString());
    }

    private static String bounded(String value) {
        return value.length() <= MAX_LENGTH ? value : null;
    }

    private static boolean controls(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }
}
