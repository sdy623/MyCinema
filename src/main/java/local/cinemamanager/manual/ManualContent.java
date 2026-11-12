package local.cinemamanager.manual;

import java.util.List;

/** Translation keys, in reading order. Text is supplied entirely by language resources. */
public final class ManualContent {
    public static final String PREFIX = "mycinema.manual.";
    public static final List<String> CHAPTERS = List.of(
            "start", "install", "register", "tablet", "sources", "projection", "linked",
            "geometry", "limits", "audio", "playback", "seek", "schedule", "film_library", "automatic",
            "lights", "information", "panels", "advertising", "screen_modes", "staff_access", "seats", "row_guides", "picture_transfer", "music", "limits_and_status");

    private ManualContent() {}

    public static String titleKey(String chapter) { return PREFIX + "chapter." + chapter + ".title"; }
    public static String bodyKey(String chapter) { return PREFIX + "chapter." + chapter + ".body"; }
}
