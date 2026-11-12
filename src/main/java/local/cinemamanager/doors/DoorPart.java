package local.cinemamanager.doors;

import net.minecraft.util.StringRepresentable;

/** Each state is one complete metre of the same three-metre leaf. */
public enum DoorPart implements StringRepresentable {
    LOWER(0), MIDDLE(1), UPPER(2);
    private final int offset;
    DoorPart(int offset) { this.offset = offset; }
    public int offset() { return offset; }
    @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
