package local.cinemamanager.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import local.cinemamanager.theater.CinemaProgramme;
import local.cinemamanager.theater.CinemaProgrammeUi;
import me.srrapero720.waterui.screen.Dialog;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** MyCinema-owned playlist controls; no private WaterFrames programme UI is required. */
public final class CinemaProgrammeDialog extends Dialog {
    private static final int ROWS = 8;

    private final Supplier<CinemaProgramme.View> source;
    private final Consumer<CinemaProgramme.Request> send;
    private final BooleanSupplier writable;
    private CinemaProgramme.View shown;
    private CinemaProgramme.View pending;
    private int selected;
    private int ticks;
    private int sentTick;
    private String notice = "";

    public CinemaProgrammeDialog(
            Supplier<CinemaProgramme.View> source,
            Consumer<CinemaProgramme.Request> send,
            BooleanSupplier writable
    ) {
        super(ResourceLocation.fromNamespaceAndPath("mycinema", "programme"), 420, 358);
        this.source = Objects.requireNonNull(source, "source");
        this.send = Objects.requireNonNull(send, "send");
        this.writable = Objects.requireNonNull(writable, "writable");

        registerVar("heading", () -> shown == null ? CinemaProgrammeUi.text("title") : Component.literal(shown.title()));
        registerVar("summary", () -> CinemaProgrammeUi.summary(shown));
        registerVar("current", () -> CinemaProgrammeUi.current(shown));
        registerVar("status", () -> CinemaProgrammeUi.status(shown));
        registerVar("selection", () -> shown == null || shown.clips().isEmpty()
                ? CinemaProgrammeUi.text("unavailable")
                : CinemaProgrammeUi.text("selection", selected + 1, shown.clips().size()));
        registerVar("notice", () -> notice.isBlank() ? Component.empty() : CinemaProgrammeUi.text(notice));
        registerVar("can_command", () -> ready() && CinemaProgrammeUi.active(shown));
        registerVar("can_stop", () -> ready());
        registerVar("can_choose", () -> ready() && CinemaProgrammeUi.selectable(shown));
        registerVar("can_previous_page", () -> selected >= ROWS);
        registerVar("can_next_page", () -> shown != null && (selected / ROWS + 1) * ROWS < shown.clips().size());
        for (int i = 0; i < ROWS; i++) {
            int row = i;
            registerVar("clip_" + row, () -> clipLine(row));
        }

        registerEvent("play", element -> dispatch(CinemaProgramme.Action.PLAY, 0));
        registerEvent("pause", element -> dispatch(CinemaProgramme.Action.PAUSE, 0));
        registerEvent("stop", element -> dispatch(CinemaProgramme.Action.STOP, 0));
        registerEvent("previous", element -> dispatch(CinemaProgramme.Action.PREVIOUS, 0));
        registerEvent("next", element -> dispatch(CinemaProgramme.Action.NEXT, 0));
        registerEvent("choose", element -> dispatch(CinemaProgramme.Action.CHOOSE, selected));
        registerEvent("selection_previous", element -> select(-1));
        registerEvent("selection_next", element -> select(1));
        registerEvent("page_previous", element -> select(-ROWS));
        registerEvent("page_next", element -> select(ROWS));
        registerEvent("done", element -> close());
    }

    @Override public void init() {
        shown = source.get();
        selected = CinemaProgrammeUi.selectionIndex(shown);
    }

    @Override public void tick() {
        ticks++;
        CinemaProgramme.View latest = source.get();
        boolean changedRun = shown == null || latest == null || !Objects.equals(shown.run(), latest.run());
        if (pending != null && !CinemaProgrammeUi.sameGuard(pending, latest)) {
            pending = null;
            notice = "accepted";
            selected = CinemaProgrammeUi.selectionIndex(latest);
        } else if (pending != null && ticks - sentTick > 100) {
            pending = null;
            notice = "no_reply";
        } else if (changedRun) {
            selected = CinemaProgrammeUi.selectionIndex(latest);
        }
        shown = latest;
        if (shown != null && !shown.clips().isEmpty()) selected = Math.clamp(selected, 0, shown.clips().size() - 1);
        super.tick();
    }

    @Override public boolean closeable() {
        return true;
    }

    private boolean ready() {
        return shown != null && !shown.clips().isEmpty() && pending == null && writable.getAsBoolean();
    }

    private void dispatch(CinemaProgramme.Action action, long value) {
        if (!ready()) return;
        if (action != CinemaProgramme.Action.STOP && action != CinemaProgramme.Action.CHOOSE && !CinemaProgrammeUi.active(shown)) return;
        if (action == CinemaProgramme.Action.CHOOSE && !CinemaProgrammeUi.selectable(shown)) return;
        CinemaProgramme.View current = shown;
        pending = current;
        sentTick = ticks;
        notice = "waiting";
        send.accept(new CinemaProgramme.Request(action, current.run(), current.operationRevision(), current.currentIndex(), value));
    }

    private void select(int delta) {
        if (shown == null || shown.clips().isEmpty() || pending != null) return;
        selected = Math.clamp(selected + delta, 0, shown.clips().size() - 1);
    }

    private Component clipLine(int row) {
        if (shown == null) return Component.empty();
        int index = selected / ROWS * ROWS + row;
        if (index < 0 || index >= shown.clips().size()) return Component.empty();
        CinemaProgramme.Clip clip = shown.clips().get(index);
        String marker = index == selected ? "▶ " : "  ";
        String current = index == shown.currentIndex() ? " ●" : "";
        return Component.literal(marker + (index + 1) + ". " + clip.label() + "  " + CinemaProgrammeUi.clock(clip.durationMs()) + current);
    }
}
