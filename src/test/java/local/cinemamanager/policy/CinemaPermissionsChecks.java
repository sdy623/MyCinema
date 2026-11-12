package local.cinemamanager.policy;

public final class CinemaPermissionsChecks {
    public static void main(String[] args) {
        check(CinemaPermissions.isWhitelisted("water://feature"), "WaterMedia source was not accepted");
        check(CinemaPermissions.isWhitelisted("https://i.imgur.com/feature.png"), "documented media host was rejected");
        check(CinemaPermissions.isWhitelisted("https://media.githubusercontent.com/path/feature.mp4"), "subdomain host matching failed");
        check(!CinemaPermissions.isWhitelisted("https://example.com/private.mp4"), "unknown host was silently trusted");
        check(!CinemaPermissions.isWhitelisted(" HTTPS://i.imgur.com/feature.png "), "non-canonical URL bypassed policy");
        check(!CinemaPermissions.isWhitelisted("file:/C:/Cinema/feature.mp4"), "local file became network-whitelisted");
        check(!CinemaPermissions.isWhitelisted("https://user:secret@i.imgur.com/feature.png"), "credential URL bypassed policy");
        System.out.println("CinemaPermissionsChecks PASS");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
