package local.cinemamanager.theater;

import java.util.ArrayList;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Stable MyCinema wire codec for programme snapshots and commands. */
public final class CinemaProgrammeNbt {
    public static final String VIEW_TAG = "programme_view";

    private static final String ACTION = "programme_action";
    private static final String RUN = "programme_run";
    private static final String REVISION = "programme_revision";
    private static final String INDEX = "programme_index";
    private static final String VALUE = "programme_value";
    private static final Set<String> REQUEST_KEYS = Set.of(ACTION, RUN, REVISION, INDEX, VALUE);

    private CinemaProgrammeNbt() {}

    public static CompoundTag writeRequest(CinemaProgramme.Request request) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ACTION, request.action().name());
        tag.putUUID(RUN, request.run());
        tag.putLong(REVISION, request.operationRevision());
        tag.putInt(INDEX, request.currentIndex());
        tag.putLong(VALUE, request.value());
        return tag;
    }

    public static CinemaProgramme.Request readRequest(CompoundTag tag) {
        if (tag == null
                || !tag.getAllKeys().equals(REQUEST_KEYS)
                || !tag.contains(ACTION, Tag.TAG_STRING)
                || !tag.hasUUID(RUN)
                || !tag.contains(REVISION, Tag.TAG_LONG)
                || !tag.contains(INDEX, Tag.TAG_INT)
                || !tag.contains(VALUE, Tag.TAG_LONG)) {
            return null;
        }
        try {
            String action = tag.getString(ACTION);
            if (action.length() > 16) return null;
            return new CinemaProgramme.Request(
                    CinemaProgramme.Action.valueOf(action),
                    tag.getUUID(RUN),
                    tag.getLong(REVISION),
                    tag.getInt(INDEX),
                    tag.getLong(VALUE)
            );
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static CompoundTag writeView(CinemaProgramme.View view) {
        CompoundTag tag = new CompoundTag();
        if (view == null) return tag;

        tag.putInt("schema", view.schema());
        tag.putUUID("run", view.run());
        tag.putLong("operation_revision", view.operationRevision());
        tag.putString("title", view.title());
        ListTag clips = new ListTag();
        for (CinemaProgramme.Clip clip : view.clips()) {
            CompoundTag encoded = new CompoundTag();
            encoded.putInt("index", clip.index());
            encoded.putString("label", clip.label());
            encoded.putLong("duration_ms", clip.durationMs());
            clips.add(encoded);
        }
        tag.put("clips", clips);
        tag.putInt("current_index", view.currentIndex());
        tag.putInt("pending_index", view.pendingIndex());
        tag.putLong("clip_position_ms", view.clipPositionMs());
        tag.putLong("clip_duration_ms", view.clipDurationMs());
        tag.putLong("total_duration_ms", view.totalDurationMs());
        tag.putString("status", view.status());
        tag.putBoolean("desired_paused", view.desiredPaused());
        tag.putString("failure_code", view.failureCode());
        tag.putLong("gap_duration_ms", view.gapDurationMs());
        tag.putLong("gap_remaining_ms", view.gapRemainingMs());
        return tag;
    }

    public static CinemaProgramme.View readView(CompoundTag tag) {
        if (tag == null || tag.getInt("schema") != CinemaProgramme.SCHEMA || !tag.hasUUID("run")) return null;
        try {
            ListTag encodedClips = tag.getList("clips", Tag.TAG_COMPOUND);
            if (encodedClips.isEmpty() || encodedClips.size() > CinemaProgramme.MAX_CLIPS) return null;
            var clips = new ArrayList<CinemaProgramme.Clip>(encodedClips.size());
            for (int i = 0; i < encodedClips.size(); i++) {
                CompoundTag encoded = encodedClips.getCompound(i);
                clips.add(new CinemaProgramme.Clip(
                        encoded.getInt("index"),
                        encoded.getString("label"),
                        encoded.getLong("duration_ms")
                ));
            }
            return new CinemaProgramme.View(
                    tag.getInt("schema"),
                    tag.getUUID("run"),
                    tag.getLong("operation_revision"),
                    tag.getString("title"),
                    clips,
                    tag.getInt("current_index"),
                    tag.getInt("pending_index"),
                    tag.getLong("clip_position_ms"),
                    tag.getLong("clip_duration_ms"),
                    tag.getLong("total_duration_ms"),
                    tag.getString("status"),
                    tag.getBoolean("desired_paused"),
                    tag.getString("failure_code"),
                    tag.getLong("gap_duration_ms"),
                    tag.getLong("gap_remaining_ms")
            );
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
