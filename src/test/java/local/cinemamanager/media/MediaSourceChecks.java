package local.cinemamanager.media;

public final class MediaSourceChecks {
    public static void main(String[] args) {
        normalizesNetworkAndLocalSources();
        rejectsCredentialsControlsAndUnsupportedSchemes();
        classifiesOnlyCanonicalLocalSources();
        System.out.println("MediaSourceChecks PASS");
    }

    private static void normalizesNetworkAndLocalSources() {
        check(MediaSource.normalize("  HTTPS://Example.test/a film.mp4  ").equals("https://Example.test/a%20film.mp4"), "network source normalization changed");
        check(MediaSource.normalize("media\\feature.mkv").equals("media/feature.mkv"), "relative Windows separators were not normalized");
        check(MediaSource.normalize("C:\\Cinema\\feature.mkv").equals("file:/C:/Cinema/feature.mkv"), "drive path was not converted to a file URI");
        check(MediaSource.normalize("/srv/cinema/feature.mkv").equals("file:/srv/cinema/feature.mkv"), "absolute path was not converted to a file URI");
    }

    private static void rejectsCredentialsControlsAndUnsupportedSchemes() {
        check(MediaSource.normalize("https://user:secret@example.test/film") == null, "credential-bearing URL was accepted");
        check(MediaSource.normalize("ftp://example.test/film") == null, "unsupported scheme was accepted");
        check(MediaSource.normalize("https://example.test/a\nfilm") == null, "control character was accepted");
        check(MediaSource.normalize("//server/share/film.mkv") == null, "UNC authority was accepted as a local source");
        check(MediaSource.normalize("x".repeat(MediaSource.MAX_LENGTH + 1)) == null, "oversized source was accepted");
    }

    private static void classifiesOnlyCanonicalLocalSources() {
        check(MediaSource.isNormalizedLocal("media/feature.mkv"), "canonical relative source was not local");
        check(MediaSource.isNormalizedLocal("file:/C:/Cinema/feature.mkv"), "canonical file URI was not local");
        check(!MediaSource.isNormalizedLocal("media\\feature.mkv"), "non-canonical relative source was accepted");
        check(!MediaSource.isNormalizedLocal("https://example.test/film"), "network source was classified as local");
        check(!MediaSource.relative("feature"), "ambiguous bare word became a relative media path");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
