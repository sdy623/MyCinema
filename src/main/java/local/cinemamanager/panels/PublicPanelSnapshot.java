package local.cinemamanager.panels;

import local.cinemamanager.model.ManagerState;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.nbt.*;

/** Transient public projection, not a saved or mutable copy of ManagerState. */
public record PublicPanelSnapshot(String timezone, long generatedAt, List<PublicShowRow> rows) {
    public static final PublicPanelSnapshot EMPTY = new PublicPanelSnapshot("", 0, List.of());
    public PublicPanelSnapshot {
        if (timezone == null || timezone.length() > 128 || rows == null || rows.size() > ManagerState.MAX_SHOWS)
            throw new IllegalArgumentException("mycinema.panel.invalid");
        if (!timezone.isBlank()) ZoneId.of(timezone);
        else if (rows.stream().anyMatch(PublicShowRow::scheduled)) throw new IllegalArgumentException("mycinema.panel.invalid");
        rows = List.copyOf(rows);
    }
    public boolean sameContent(PublicPanelSnapshot other) {
        return other != null && timezone.equals(other.timezone) && rows.equals(other.rows) && PublicTimeRange.dayKey(generatedAt,timezone).equals(PublicTimeRange.dayKey(other.generatedAt,other.timezone));
    }
    /** Per-field tags avoid NBT's 65,535-byte modified-UTF ceiling on a single JSON string. */
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putString("timezone",timezone);tag.putLong("generated_at",generatedAt);
        ListTag list=new ListTag();
        for(var row:rows){CompoundTag item=new CompoundTag();item.putString("id",row.id());item.putString("title",row.title());item.putString("hall",row.hall());item.putLong("start",row.start());item.putLong("end",row.end());item.putLong("duration_ms",row.durationMs());item.putBoolean("scheduled",row.scheduled());list.add(item);}
        tag.put("rows",list);return tag;
    }
    public static PublicPanelSnapshot read(CompoundTag tag) {
        ListTag list=tag.getList("rows",Tag.TAG_COMPOUND);
        if(list.size()>ManagerState.MAX_SHOWS)throw new IllegalArgumentException("mycinema.panel.invalid");
        List<PublicShowRow> rows=new ArrayList<>();
        for(int i=0;i<list.size();i++){CompoundTag item=list.getCompound(i);rows.add(new PublicShowRow(item.getString("id"),item.getString("title"),item.getString("hall"),item.getLong("start"),item.getLong("duration_ms"),item.contains("end")?item.getLong("end"):item.getLong("start"),!item.contains("scheduled")||item.getBoolean("scheduled")));}
        return new PublicPanelSnapshot(tag.getString("timezone"),tag.getLong("generated_at"),rows);
    }
}
