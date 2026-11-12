package local.cinemamanager.projection;

import net.minecraft.util.StringRepresentable;

public enum ProjectorStyle implements StringRepresentable {
    STANDARD("standard"), CINEMA("cinema"), IMAX_GT("imax_gt");
    private final String id;
    ProjectorStyle(String id) { this.id = id; }
    @Override public String getSerializedName() { return id; }
    public boolean grouped() { return this != STANDARD; }
    public static ProjectorStyle parse(String value) {
        for (ProjectorStyle style : values()) if (style.id.equals(value)) return style;
        return CINEMA;
    }
}
