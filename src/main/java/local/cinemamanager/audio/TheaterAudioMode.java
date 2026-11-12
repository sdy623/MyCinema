package local.cinemamanager.audio;

public enum TheaterAudioMode {
    SCREEN("screen"), AUDIENCE("audience"), PROJECTOR("projector");
    public final String id;
    TheaterAudioMode(String id) { this.id = id; }
    public static TheaterAudioMode parse(String value) {
        for (TheaterAudioMode mode : values()) if (mode.id.equals(value)) return mode;
        return null;
    }
}
