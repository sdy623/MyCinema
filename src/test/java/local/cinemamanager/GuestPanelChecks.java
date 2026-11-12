package local.cinemamanager;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Pure contract checks: no world, GUI, font, playback, or wall-clock dependency. */
public final class GuestPanelChecks {
    private static final long NOW = 1_800_000_000_000L;
    private static int checks;

    private GuestPanelChecks() {}

    public static void run() {
        checks = 0;
        selectionBoundaries();
        delayedFilmSelection();
        deterministicOrdering();
        invalidSchedules();
        timezoneAndDuration();
        immutableProjection();
        completePagination();
        settingsMigration();
        publicRedaction();
        invalidFields();
        maximumGuestCodec();
        maximumSnapshotNbt();
        refreshRateLimits();
        System.out.println("PASS: " + checks + " guest panel checks: public selection, timezone, duration, redaction, pagination, migration, bounds, escaped codec, binary NBT, refresh limits.");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static ManagerState state() {
        ManagerState state = new ManagerState();
        state.timezone = "Asia/Shanghai";
        hall(state, "hall-a", "Hall A");
        hall(state, "hall-b", "Hall B");
        return state;
    }

    private static ManagerState.Hall hall(ManagerState state, String id, String name) {
        ManagerState.Hall hall = new ManagerState.Hall();
        hall.id = id;
        hall.name = name;
        state.halls.add(hall);
        return hall;
    }

    private static ManagerState.Show show(ManagerState state, String id, String hall, long start, long end, ManagerState.Status status) {
        ManagerState.Show show = new ManagerState.Show();
        show.id = id;
        show.hallId = hall;
        show.title = "Film " + id;
        show.url = "https://private.invalid/MEDIA_URL_SENTINEL";
        show.start = start;
        show.end = end;
        show.status = status;
        state.shows.add(show);
        return show;
    }

    private static PublicPanelSnapshot project(ManagerState state, PanelScope scope, String hall) {
        return PublicSchedule.project(state, scope, hall, NOW, show -> 0);
    }

    private static List<String> ids(PublicPanelSnapshot snapshot) {
        return snapshot.rows().stream().map(PublicShowRow::id).toList();
    }

    private static void delayedFilmSelection() {
        for(var status:List.of(ManagerState.Status.PREPARING,ManagerState.Status.RUNNING)){
            ManagerState state=state();
            var delayed=show(state,"delayed-film","hall-a",NOW-18064,NOW-1,status);
            delayed.filmSnapshot=UUID.randomUUID().toString();delayed.filmId=UUID.randomUUID().toString();delayed.filmRevision=1;delayed.filmDurationMs=18063;
            show(state,"next-film","hall-a",NOW+60000,NOW+120000,ManagerState.Status.PENDING);
            show(state,"expired-legacy","hall-a",NOW-60000,NOW-1,ManagerState.Status.RUNNING);
            var snapshot=project(state,PanelScope.HALL,"hall-a");
            check(ids(snapshot).equals(List.of(delayed.id,"next-film")),"delayed active film stays selected while expired single-source row stays excluded: "+status);
            check(PublicSchedule.firstHallShow(state,"hall-a",NOW)==delayed,"automatic poster follows exact delayed current film: "+status);
            var row=snapshot.rows().getFirst();
            check(row.start()==delayed.start&&row.end()==delayed.end&&row.durationMs()==0,"delayed guest row retains nominal times and never invents unknown runtime");
            var fields=GuestFields.forRow(PanelSettings.DEFAULT,PanelScope.HALL,snapshot,row,true,key->key);
            check(fields.stream().anyMatch(field->field.label().equals("duration_label")&&field.value().equals("—")),"unknown trusted runtime remains an em dash after nominal end");
            delayed.status=ManagerState.Status.COMPLETED;
            check(ids(project(state,PanelScope.HALL,"hall-a")).equals(List.of("next-film")),"natural completion removes delayed history from current guest selection");
        }
    }

    private static void selectionBoundaries() {
        ManagerState state = state();
        state.schedulerEnabled = false;
        show(state, "pending-current", "hall-a", NOW - 10_000, NOW + 10_000, ManagerState.Status.PENDING);
        show(state, "preparing-current", "hall-a", NOW - 7_500, NOW + 10_000, ManagerState.Status.PREPARING);
        show(state, "running-current", "hall-a", NOW - 5_000, NOW + 10_000, ManagerState.Status.RUNNING);
        show(state, "pending-at-start", "hall-a", NOW, NOW + 10_000, ManagerState.Status.PENDING);
        show(state, "running-at-start", "hall-a", NOW, NOW + 10_000, ManagerState.Status.RUNNING);
        show(state, "pending-future", "hall-a", NOW + 1, NOW + 10_000, ManagerState.Status.PENDING);
        show(state, "running-future", "hall-a", NOW + 1, NOW + 10_000, ManagerState.Status.RUNNING);
        for (ManagerState.Status status : ManagerState.Status.values()) {
            show(state, "ended-" + status, "hall-a", NOW - 10_000, NOW, status);
            if (status != ManagerState.Status.PENDING && status != ManagerState.Status.PREPARING && status != ManagerState.Status.RUNNING)
                show(state, "excluded-" + status, "hall-a", NOW - 10_000, NOW + 10_000, status);
        }
        show(state, "other-hall", "hall-b", NOW + 1, NOW + 10_000, ManagerState.Status.PENDING);
        check(ids(project(state, PanelScope.HALL, "hall-a")).equals(List.of(
                "running-current", "running-at-start", "pending-current", "preparing-current", "pending-at-start", "pending-future")),
                "hall includes current pending with automation off, prioritizes running, and obeys inclusive-start/exclusive-end boundaries");
        check(ids(project(state, PanelScope.LOBBY, "unused")).equals(List.of(
                "pending-current", "preparing-current", "running-current", "pending-at-start", "running-at-start", "pending-future", "other-hall")),
                "lobby includes eligible pending/preparing/running entries across halls in scheduled order");
        check(project(state, PanelScope.HALL, "").rows().isEmpty(), "empty hall binding never grants lobby scope");
        check(project(state, PanelScope.HALL, "missing").rows().isEmpty(), "orphaned hall binding has no schedule");
        check(project(state, PanelScope.HALL, null).rows().isEmpty(), "null hall binding never grants lobby scope");
    }

    private static void deterministicOrdering() {
        ManagerState state = state();
        state.halls.get(0).name = "Zulu";
        state.halls.get(1).name = "Alpha";
        show(state, "z", "hall-b", NOW + 20, NOW + 100, ManagerState.Status.PENDING);
        show(state, "b", "hall-a", NOW + 20, NOW + 100, ManagerState.Status.PENDING);
        show(state, "early", "hall-b", NOW + 10, NOW + 100, ManagerState.Status.PENDING);
        show(state, "a", "hall-a", NOW + 20, NOW + 100, ManagerState.Status.PENDING);
        List<String> expected = List.of("early", "a", "b", "z");
        check(ids(project(state, PanelScope.LOBBY, "")).equals(expected), "ties use stable hall ID then show ID, not translated hall label");
        Collections.reverse(state.shows);
        check(ids(project(state, PanelScope.LOBBY, "")).equals(expected), "insertion order cannot change lobby order");
        check(ids(project(state, PanelScope.HALL, "hall-a")).equals(List.of("a", "b")), "hall ties use show ID");
        show(state, "a", "hall-b", NOW + 30, NOW + 100, ManagerState.Status.PENDING);
        check(ids(project(state, PanelScope.LOBBY, "")).equals(expected), "duplicate schedule IDs do not duplicate public rows");
    }

    private static void invalidSchedules() {
        ManagerState state = state();
        show(state, "good", "hall-a", NOW + 1, NOW + 100, ManagerState.Status.PENDING);
        List<Consumer<ManagerState.Show>> corruptions = List.of(
                s -> s.id = null, s -> s.id = "", s -> s.id = " ", s -> s.id = "x".repeat(81),
                s -> s.title = null, s -> s.title = "", s -> s.title = " ", s -> s.title = "x".repeat(81),
                s -> s.url = null, s -> s.url = "", s -> s.url = " ", s -> s.url = "x".repeat(2049),
                s -> s.hallId = null, s -> s.hallId = "orphan", s -> s.status = null,
                s -> s.end = s.start, s -> s.end = s.start - 1, s -> s.end = NOW - 1);
        for (int index = 0; index < corruptions.size(); index++) {
            ManagerState.Show invalid = show(state, "bad-" + index, "hall-a", NOW + 1, NOW + 100, ManagerState.Status.PENDING);
            corruptions.get(index).accept(invalid);
        }
        state.shows.add(null);
        hall(state, "invalid-hall", "x".repeat(61));
        hall(state, "unnamed-hall", null);
        show(state, "bad-hall-label", "invalid-hall", NOW + 1, NOW + 100, ManagerState.Status.PENDING);
        show(state, "null-hall-label", "unnamed-hall", NOW + 1, NOW + 100, ManagerState.Status.PENDING);
        check(ids(project(state, PanelScope.LOBBY, "")).equals(List.of("good")), "malformed shows and unresolved/invalid halls cannot enter the guest projection");
        check(PublicSchedule.project(null, PanelScope.LOBBY, "", NOW, s -> 0).rows().isEmpty(), "missing manager gives empty projection");
        check(PublicSchedule.project(state, null, "", NOW, s -> 0).rows().isEmpty(), "missing profile gives empty projection");
    }

    private static void timezoneAndDuration() {
        ManagerState state = state();
        show(state, "feature", "hall-a", NOW - 1, NOW + 7_200_000, ManagerState.Status.RUNNING);
        for (String invalid : new String[]{null, "", " ", "Mars/Phobos", "x".repeat(129)}) {
            state.timezone = invalid;
            AtomicInteger resolved = new AtomicInteger();
            PublicPanelSnapshot snapshot = PublicSchedule.project(state, PanelScope.LOBBY, "", NOW, s -> { resolved.incrementAndGet(); return 1; });
            check(snapshot.rows().isEmpty() && snapshot.timezone().isEmpty(), "invalid or absent timezone never silently becomes UTC");
            check(resolved.get() == 0, "invalid timezone never resolves media duration");
        }
        state.timezone = "America/New_York";
        PublicPanelSnapshot unknown = project(state, PanelScope.HALL, "hall-a");
        check(unknown.timezone().equals("America/New_York") && unknown.generatedAt() == NOW, "snapshot preserves explicit configured timezone and generation time");
        check(unknown.rows().getFirst().durationMs() == 0, "unknown media duration never derives from the two-hour reserved slot");
        check(PublicSchedule.project(state, PanelScope.HALL, "hall-a", NOW, s -> -1).rows().getFirst().durationMs() == 0, "negative duration is unknown");
        check(PublicSchedule.project(state, PanelScope.HALL, "hall-a", NOW, s -> { throw new IllegalStateException("unavailable"); }).rows().getFirst().durationMs() == 0, "failed trusted duration lookup is unknown");
        check(PublicSchedule.project(state, PanelScope.HALL, "hall-a", NOW, s -> 123_456).rows().getFirst().durationMs() == 123_456, "trusted media duration is used exactly");
    }

    private static void immutableProjection() {
        ManagerState state = state();
        state.revision = 42;
        state.seekAcknowledgement = "ACK_SENTINEL";
        ManagerState.Hall hall = state.halls.getFirst();
        hall.override = true;
        hall.transport = ManagerState.Transport.PAUSED;
        hall.durationMs = 999_999;
        hall.playheadMs = 12_345;
        hall.seekToken = "SEEK_TOKEN_SENTINEL";
        hall.issue = "ADMIN_ISSUE_SENTINEL";
        show(state, "later", "hall-a", NOW + 1_000, NOW + 10_000, ManagerState.Status.PENDING).outcome = "OUTCOME_SENTINEL";
        show(state, "current", "hall-a", NOW - 1, NOW + 10_000, ManagerState.Status.RUNNING);
        String before = StateCodec.encode(state);
        PublicPanelSnapshot snapshot = project(state, PanelScope.HALL, "hall-a");
        check(StateCodec.encode(state).equals(before), "projection changes no scheduler, playback, override, source order, or revision state");
        rejects(() -> snapshot.rows().clear(), "snapshot rows are immutable");
        List<PublicShowRow> source = new ArrayList<>(snapshot.rows());
        PublicPanelSnapshot copied = new PublicPanelSnapshot(snapshot.timezone(), NOW, source);
        source.clear();
        check(copied.rows().equals(snapshot.rows()), "snapshot owns a defensive list copy");
        check(snapshot.sameContent(new PublicPanelSnapshot(snapshot.timezone(), NOW + 1, snapshot.rows())), "clock-only changes do not invalidate public content");
        check(!snapshot.sameContent(new PublicPanelSnapshot("UTC", NOW, snapshot.rows())), "timezone changes invalidate public content");
        check(!snapshot.sameContent(new PublicPanelSnapshot(snapshot.timezone(), NOW, List.of())), "schedule removal invalidates public content");
        check(!snapshot.sameContent(null), "null comparison has different content");
    }

    private static void completePagination() {
        ManagerState state = state();
        for (int index = ManagerState.MAX_SHOWS - 1; index >= 0; index--)
            show(state, "show-" + index, index % 2 == 0 ? "hall-a" : "hall-b", NOW + 1 + index, NOW + 10_000, ManagerState.Status.PENDING);
        PublicPanelSnapshot snapshot = project(state, PanelScope.LOBBY, "");
        check(snapshot.rows().size() == 128 && new HashSet<>(ids(snapshot)).size() == 128, "every one of 128 scheduled shows remains reachable");
        List<String> wrapped = new ArrayList<>();
        for (PublicShowRow row : snapshot.rows()) {
            // An unusually long first row must continue across several pages without losing later shows.
            int lines = row.id().equals("show-0") ? 73 : 7;
            for (int line = 0; line < lines; line++) wrapped.add(row.id() + ":line-" + line);
        }
        for (int capacity : List.of(1, 3, 8, 17, 100)) {
            List<List<String>> pages = PublicPanelPagination.paginate(wrapped, capacity);
            check(pages.stream().flatMap(List::stream).toList().equals(wrapped), "overflow pagination preserves every continuation and every show at capacity " + capacity);
            check(pages.stream().allMatch(page -> !page.isEmpty() && page.size() <= capacity), "page capacity respected " + capacity);
            check(pages.size() == (wrapped.size() + capacity - 1) / capacity, "page count includes full overflow " + capacity);
        }
        List<List<String>> pages = PublicPanelPagination.paginate(wrapped, 8);
        rejects(() -> pages.clear(), "page collection is immutable");
        rejects(() -> pages.getFirst().clear(), "page contents are immutable");
        check(PublicPanelPagination.paginate(List.of(), 1).equals(List.of(List.of())), "empty board retains one safe empty page");
        rejects(() -> PublicPanelPagination.paginate(wrapped, 0), "zero page height rejected");
        rejects(() -> PublicPanelPagination.paginate(wrapped, -1), "negative page height rejected");
        check(PublicPanelPagination.ROTATION_TICKS == 160, "wall rotation remains eight seconds");
        for (int page = 0; page < pages.size(); page++) {
            check(PublicPanelPagination.pageForRotation((long) page * 160, pages.size()) == page, "wall rotation reaches page " + page);
            check(PublicPanelPagination.pageForRotation((long) page * 160 + 159, pages.size()) == page, "rotation keeps page until boundary " + page);
        }
        check(PublicPanelPagination.pageForRotation((long) pages.size() * 160, pages.size()) == 0, "wall rotation wraps after final page");
        check(PublicPanelPagination.pageForRotation(-1, 3) == 2, "negative game time still yields valid rotation");
        check(PublicPanelPagination.pageForRotation(Long.MAX_VALUE, 1) == 0 && PublicPanelPagination.pageForRotation(Long.MIN_VALUE, 0) == 0, "zero/one-page rotation is safe at long limits");
        check(PublicPanelPagination.clampPage(127, 2) == 1, "refresh clamps a stale page after schedule shrink");
        check(PublicPanelPagination.clampPage(-1, 2) == 0 && PublicPanelPagination.clampPage(9, 0) == 0, "empty and negative page indexes clamp safely");
    }

    private static PanelSettings settings(String poster, int flags) {
        return new PanelSettings(PanelSettings.Layout.POSTER_LEFT, "Display hall", "Display film", "20:00",
                "ADMIN_NEXT_SENTINEL", "ADMIN_FOOTER_SENTINEL", poster, 0x123456, 0x010203, 0xabcdef, 120, flags);
    }

    private static void settingsMigration() {
        check(PanelSettings.DURATION == 64 && PanelSettings.PUBLIC == 199 && PanelSettings.ALL == 255, "public visibility mask adds duration and format without reusing legacy bits");
        check(PanelSettings.DEFAULT.visible() == PanelSettings.PUBLIC, "new panels default to the five public fields");
        for (int version : List.of(0, 1)) for (int legacy = 0; legacy <= 63; legacy++) {
            CompoundTag saved = settings("", legacy).save();
            if (version == 0) saved.remove("version"); else saved.putInt("version", version);
            PanelSettings migrated = PanelSettings.read(saved);
            check(migrated.visible() == ((legacy & 7) | PanelSettings.DURATION), "legacy visibility maps only hall/title/time and enables duration");
            check(migrated.next().equals("ADMIN_NEXT_SENTINEL") && migrated.footer().equals("ADMIN_FOOTER_SENTINEL"), "migration preserves administrator configuration for editing");
            check(migrated.save().getInt("version") == 6 && PanelSettings.read(migrated.save()).equals(migrated), "migration is stable after saving version 6");
        }
        for (int flags = 0; flags <= PanelSettings.ALL; flags++) {
            PanelSettings current = settings("", flags);
            check(PanelSettings.read(current.save()).equals(current), "version 2 preserves explicit visibility bits " + flags);
        }
        check(PanelScope.savedOrDefault("LOBBY") == PanelScope.LOBBY, "explicit lobby scope restores");
        for (String invalid : new String[]{null, "", "lobby", "ALL", "HALL_AND_LOBBY"})
            check(PanelScope.savedOrDefault(invalid) == PanelScope.HALL, "unknown or absent saved scope defaults to hall");
        for (PanelScope scope : PanelScope.values()) {
            PanelNetwork.Configuration configuration = new PanelNetwork.Configuration(settings("", 71), scope);
            check(PanelNetwork.JSON.fromJson(PanelNetwork.JSON.toJson(configuration), PanelNetwork.Configuration.class).equals(configuration), "configuration roundtrip includes explicit scope " + scope);
        }
    }

    private static void publicRedaction() {
        PanelSettings original = settings("resource:minecraft:textures/block/stone.png", PanelSettings.ALL);
        PanelSettings publicHall = original.publicOnly(PanelScope.HALL);
        check(publicHall.visible() == PanelSettings.PUBLIC && publicHall.next().isEmpty() && publicHall.footer().isEmpty(), "public settings exclude administrator status, next, and footer");
        check(publicHall.hall().equals(original.hall()) && publicHall.title().equals(original.title()) && publicHall.time().equals(original.time()), "hall keeps explicit approved display overrides");
        check(publicHall.poster().equals(original.poster()), "approved resource poster remains visible");
        PanelSettings publicLobby = original.publicOnly(PanelScope.LOBBY);
        check(publicLobby.hall().isEmpty() && publicLobby.title().isEmpty() && publicLobby.time().isEmpty()
                && publicLobby.next().isEmpty() && publicLobby.footer().isEmpty(), "lobby cannot replace individual schedule fields with display overrides");
        check(settings("https://name:password@example.com/PRIVATE_POSTER_SENTINEL.png", PanelSettings.ALL).publicOnly(PanelScope.HALL).poster().isEmpty(), "rejected credential-bearing poster never becomes public");

        ManagerState state = state();
        state.notice = "ADMIN_NOTICE_SENTINEL";
        state.seekAcknowledgement = "ADMIN_ACK_SENTINEL";
        ManagerState.Hall hall = state.halls.getFirst();
        hall.issue = "ADMIN_ISSUE_SENTINEL";
        hall.seekToken = "ADMIN_SEEK_TOKEN_SENTINEL";
        hall.seekSession = "ADMIN_SEEK_SESSION_SENTINEL";
        hall.deviceState = "ADMIN_DEVICE_SENTINEL";
        hall.terminalIdentity = "ADMIN_TERMINAL_SENTINEL";
        show(state, "public-show", "hall-a", NOW + 1, NOW + 1_000, ManagerState.Status.PENDING).outcome = "ADMIN_OUTCOME_SENTINEL";
        PanelNetwork.GuestData data = new PanelNetwork.GuestData(publicLobby, PanelScope.LOBBY, project(state, PanelScope.LOBBY, ""), 8);
        String json = PanelNetwork.JSON.toJson(data);
        for (String forbidden : List.of("MEDIA_URL_SENTINEL", "ADMIN_", "PRIVATE_POSTER_SENTINEL", "signature", "seekToken", "terminalIdentity", "outcome", "schedulerEnabled", "editable", "diagnostics"))
            check(!json.contains(forbidden), "guest DTO excludes secret/administrator sentinel " + forbidden);
        var root = JsonParser.parseString(json).getAsJsonObject();
        check(root.keySet().equals(Set.of("settings", "scope", "snapshot", "height", "width")), "guest payload has an explicit top-level allowlist");
        var snapshot = root.getAsJsonObject("snapshot");
        check(snapshot.keySet().equals(Set.of("timezone", "generatedAt", "rows")), "snapshot excludes all manager control metadata");
        check(snapshot.getAsJsonArray("rows").get(0).getAsJsonObject().keySet().equals(Set.of("id", "title", "hall", "start", "end", "durationMs", "scheduled")), "show rows contain exactly seven public data fields including planned end and schedule presence");
        check(PanelNetwork.JSON.fromJson(json, PanelNetwork.GuestData.class).equals(data), "redacted guest data roundtrips");
    }

    private static void invalidFields() {
        PublicShowRow maximum = new PublicShowRow("i".repeat(80), "t".repeat(80), "h".repeat(60), Long.MIN_VALUE, Long.MAX_VALUE);
        check(maximum.id().length() == 80 && maximum.hall().length() == 60, "inclusive row field bounds accepted");
        for (String invalid : new String[]{null, "", " ", "i".repeat(81)})
            rejects(() -> new PublicShowRow(invalid, "title", "hall", NOW, 0), "invalid public show ID rejected");
        for (String invalid : new String[]{null, "", " ", "t".repeat(81)})
            rejects(() -> new PublicShowRow("id", invalid, "hall", NOW, 0), "invalid public title rejected");
        rejects(() -> new PublicShowRow("id", "title", null, NOW, 0), "null public hall rejected");
        rejects(() -> new PublicShowRow("id", "title", "h".repeat(61), NOW, 0), "oversized public hall rejected");
        rejects(() -> new PublicShowRow("id", "title", "hall", NOW, -1), "negative public duration rejected");
        rejects(() -> new PublicPanelSnapshot(null, NOW, List.of()), "null snapshot zone rejected");
        rejects(() -> new PublicPanelSnapshot("x".repeat(129), NOW, List.of()), "oversized snapshot zone rejected");
        rejects(() -> new PublicPanelSnapshot("Mars/Phobos", NOW, List.of()), "invalid snapshot zone rejected");
        rejects(() -> new PublicPanelSnapshot("", NOW, List.of(maximum)), "populated snapshot requires an explicit valid zone");
        rejects(() -> new PublicPanelSnapshot("UTC", NOW, null), "null snapshot list rejected");
        rejects(() -> new PublicPanelSnapshot("UTC", NOW, Collections.nCopies(129, maximum)), "snapshot cannot exceed maximum schedule capacity");
        rejects(() -> new PublicPanelSnapshot("UTC", NOW, Collections.singletonList(null)), "snapshot cannot contain null rows");
        rejects(() -> new PanelNetwork.Configuration(null, PanelScope.HALL), "null configuration settings rejected");
        rejects(() -> new PanelNetwork.Configuration(PanelSettings.DEFAULT, null), "null configuration scope rejected");
        rejects(() -> PanelNetwork.JSON.fromJson("{\"settings\":" + PanelNetwork.JSON.toJson(PanelSettings.DEFAULT) + ",\"scope\":\"ALL\"}", PanelNetwork.Configuration.class), "unknown incoming profile rejected");
        rejects(() -> new PanelNetwork.GuestData(null, PanelScope.HALL, PublicPanelSnapshot.EMPTY, 1), "null guest settings rejected");
        rejects(() -> new PanelNetwork.GuestData(PanelSettings.DEFAULT, null, PublicPanelSnapshot.EMPTY, 1), "null guest scope rejected");
        rejects(() -> new PanelNetwork.GuestData(PanelSettings.DEFAULT, PanelScope.HALL, null, 1), "null guest snapshot rejected");
        for (int height : List.of(-1, 0, 33, Integer.MAX_VALUE))
            rejects(() -> new PanelNetwork.GuestData(PanelSettings.DEFAULT, PanelScope.HALL, PublicPanelSnapshot.EMPTY, height), "invalid guest panel height rejected");
        for (int height : List.of(1, 16, 32))
            check(new PanelNetwork.GuestData(PanelSettings.DEFAULT, PanelScope.HALL, PublicPanelSnapshot.EMPTY, height).height() == height, "inclusive guest panel height accepted");
    }

    private static void maximumGuestCodec() {
        check(PanelNetwork.MAX_GUEST_JSON == 262_144, "guest codec supports the complete bounded escaped schedule");
        String escaped = "\u0001";
        List<PublicShowRow> rows = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            String prefix = Integer.toString(index);
            rows.add(new PublicShowRow(prefix + escaped.repeat(80 - prefix.length()), escaped.repeat(80), escaped.repeat(60), Long.MIN_VALUE, Long.MAX_VALUE));
        }
        PanelSettings settings = new PanelSettings(PanelSettings.Layout.POSTER_LEFT, escaped.repeat(80), escaped.repeat(160), escaped.repeat(120),
                "", "", "resource:minecraft:textures/block/stone.png", 0xffffff, 0xffffff, 0xffffff, 160, PanelSettings.PUBLIC);
        PanelNetwork.GuestData data = new PanelNetwork.GuestData(settings, PanelScope.HALL, new PublicPanelSnapshot("America/Argentina/ComodRivadavia", Long.MIN_VALUE, rows), 8);
        String json = PanelNetwork.JSON.toJson(data);
        check(json.length() > 65_536 && json.length() <= PanelNetwork.MAX_GUEST_JSON, "worst-case escaped guest payload exceeds legacy view limit and fits guest codec");
        check(PanelNetwork.JSON.fromJson(json, PanelNetwork.GuestData.class).equals(data), "all 128 maximally escaped rows roundtrip without truncation");
        roundtripGuest(json, true);
        roundtripGuest("x".repeat(PanelNetwork.MAX_GUEST_JSON), false);
        var tooLarge = new PanelNetwork.GuestView(BlockPos.ZERO, UUID.randomUUID().toString(), "x".repeat(PanelNetwork.MAX_GUEST_JSON + 1), false);
        var buffer = Unpooled.buffer();
        try { rejects(() -> PanelNetwork.GuestView.CODEC.encode(buffer, tooLarge), "guest codec rejects oversized JSON"); }
        finally { buffer.release(); }
        var refresh = new PanelNetwork.GuestRefresh(new BlockPos(-100, 255, 100), UUID.randomUUID().toString());
        var refreshBuffer = Unpooled.buffer();
        try {
            PanelNetwork.GuestRefresh.CODEC.encode(refreshBuffer, refresh);
            check(PanelNetwork.GuestRefresh.CODEC.decode(refreshBuffer).equals(refresh), "guest refresh contains only target position and identity");
            check(!refreshBuffer.isReadable(), "guest refresh codec consumes the full packet");
        } finally { refreshBuffer.release(); }
    }

    private static void roundtripGuest(String json, boolean open) {
        var packet = new PanelNetwork.GuestView(new BlockPos(-10, 64, 20), UUID.randomUUID().toString(), json, open);
        var buffer = Unpooled.buffer();
        try {
            PanelNetwork.GuestView.CODEC.encode(buffer, packet);
            check(PanelNetwork.GuestView.CODEC.decode(buffer).equals(packet), "bounded guest packet roundtrip with open=" + open);
            check(!buffer.isReadable(), "guest codec consumes full encoded payload");
        } finally { buffer.release(); }
    }

    private static void maximumSnapshotNbt() {
        List<PublicShowRow> rows = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            String prefix = Integer.toString(index);
            String id = prefix + "映".repeat(78 - prefix.length()) + "\u0000\u0001";
            rows.add(new PublicShowRow(id, "影".repeat(78) + "\u0000\u0001", "厅".repeat(58) + "\u0000\u0001", Long.MIN_VALUE, Long.MAX_VALUE));
        }
        PublicPanelSnapshot snapshot = new PublicPanelSnapshot("Asia/Shanghai", NOW, rows);
        CompoundTag saved = snapshot.save();
        check(saved.contains("rows", Tag.TAG_LIST) && saved.getList("rows", Tag.TAG_COMPOUND).size() == 128,
                "snapshot NBT stores 128 separate row compounds, not a giant JSON string");
        check(PublicPanelSnapshot.read(saved).equals(snapshot), "snapshot tag roundtrip preserves every bounded Unicode/control field");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) { NbtIo.write(saved, output); }
            check(bytes.size() > 65_535, "binary snapshot regression exceeds the single StringTag modified-UTF ceiling");
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                CompoundTag decoded = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                check(PublicPanelSnapshot.read(decoded).equals(snapshot), "binary NbtIo roundtrip retains the full 128-row schedule beyond 65,535 bytes");
                check(input.available() == 0, "binary NBT roundtrip consumes the full snapshot");
            }
        } catch (IOException exception) {
            throw new AssertionError("bounded Unicode/control snapshot must serialize through the real NBT writer", exception);
        }
        CompoundTag excessive = saved.copy();
        excessive.getList("rows", Tag.TAG_COMPOUND).add(saved.getList("rows", Tag.TAG_COMPOUND).getCompound(0).copy());
        rejects(() -> PublicPanelSnapshot.read(excessive), "NBT snapshots reject more than 128 rows");
        CompoundTag invalidRow = saved.copy();
        invalidRow.getList("rows", Tag.TAG_COMPOUND).getCompound(0).putString("title", "x".repeat(81));
        rejects(() -> PublicPanelSnapshot.read(invalidRow), "NBT snapshots validate individual row field bounds");
    }

    private static void refreshRateLimits() {
        UUID viewer = UUID.randomUUID();
        var refreshes = new HashMap<UUID, Long>();
        var opens = new HashMap<UUID, Long>();
        check(PanelNetwork.acceptAt(refreshes, viewer, 0, 20), "first guest refresh is accepted even at tick zero");
        check(!PanelNetwork.acceptAt(refreshes, viewer, 0, 20), "same-tick refresh spam is rejected");
        check(!PanelNetwork.acceptAt(refreshes, viewer, 19, 20), "refresh is rejected before the 20-tick boundary");
        check(refreshes.get(viewer) == 0, "rejected refresh does not extend the throttle interval");
        check(PanelNetwork.acceptAt(refreshes, viewer, 20, 20), "refresh is accepted at exactly 20 ticks");
        check(PanelNetwork.acceptAt(opens, viewer, 20, 2), "recent refresh cannot block an initial guest screen open");
        check(!PanelNetwork.acceptAt(opens, viewer, 21, 2), "same open throttle blocks repeated clicks within two ticks");
        check(PanelNetwork.acceptAt(opens, viewer, 22, 2), "guest screen can reopen at the two-tick boundary independently of refresh");
        check(!PanelNetwork.acceptAt(refreshes, viewer, 39, 20), "screen open does not release refresh throttle");
        check(PanelNetwork.acceptAt(refreshes, viewer, 40, 20), "refresh resumes at its own boundary");
        check(PanelNetwork.acceptAt(refreshes, viewer, 1, 20), "server tick reset permits the first new refresh");
        check(PanelNetwork.acceptAt(refreshes, UUID.randomUUID(), 1, 20), "one viewer cannot throttle another viewer");
        var beforeInvalid = new HashMap<>(refreshes);
        rejects(() -> PanelNetwork.acceptAt(refreshes, viewer, 100, 0), "zero throttle interval is rejected");
        rejects(() -> PanelNetwork.acceptAt(refreshes, viewer, 100, -1), "negative throttle interval is rejected");
        check(refreshes.equals(beforeInvalid), "invalid intervals leave throttle state untouched");

        var crowded = new HashMap<UUID, Long>();
        for (int index = 0; index < 129; index++) crowded.put(new UUID(0, index), 0L);
        UUID live = new UUID(1, 0), future = new UUID(2, 0), incoming = new UUID(3, 0);
        crowded.put(live, 100L);
        crowded.put(future, 501L);
        check(PanelNetwork.acceptAt(crowded, incoming, 500, 20), "large throttle map still admits a new viewer");
        check(crowded.keySet().equals(Set.of(live, incoming)), "large throttle map removes expired and pre-reset future entries while retaining the 400-tick boundary");
    }
}
