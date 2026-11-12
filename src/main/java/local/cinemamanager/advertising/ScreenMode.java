package local.cinemamanager.advertising;

/** Content selection is independent of physical geometry and ordinary panel scope. */
public enum ScreenMode {
    PICTURE, MEDIA, INFORMATION;
    public static ScreenMode saved(String value) {
        try { return valueOf(value); } catch (RuntimeException ignored) { return PICTURE; }
    }
}
