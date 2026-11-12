package local.cinemamanager.theater;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public final class CinemaProgrammeNbtChecks {
    private static final Set<String> REQUEST_KEYS = Set.of(
            "programme_action",
            "programme_run",
            "programme_revision",
            "programme_index",
            "programme_value"
    );

    public static void main(String[] args) {
        requestRoundTripsWithTheStableWireKeys();
        requestRejectsMalformedOrExtendedPackets();
        viewRoundTripsWithTheStableWireKeys();
        viewRejectsUnsupportedOrUnboundedPackets();
        nullViewUsesTheExistingEmptyPacket();
        System.out.println("CinemaProgrammeNbtChecks PASS");
    }

    private static void requestRoundTripsWithTheStableWireKeys() {
        var request = new CinemaProgramme.Request(
                CinemaProgramme.Action.SEEK_CURRENT,
                UUID.randomUUID(),
                12,
                2,
                4_567
        );
        CompoundTag encoded = CinemaProgrammeNbt.writeRequest(request);
        check(encoded.getAllKeys().equals(REQUEST_KEYS), "request wire keys changed");
        check(CinemaProgrammeNbt.readRequest(encoded).equals(request), "request NBT roundtrip changed values");
    }

    private static void requestRejectsMalformedOrExtendedPackets() {
        CompoundTag valid = CinemaProgrammeNbt.writeRequest(new CinemaProgramme.Request(
                CinemaProgramme.Action.PLAY,
                UUID.randomUUID(),
                1,
                0,
                0
        ));

        CompoundTag extra = valid.copy();
        extra.putBoolean("unexpected", true);
        check(CinemaProgrammeNbt.readRequest(extra) == null, "request accepted an unreviewed field");

        CompoundTag wrongType = valid.copy();
        wrongType.putString("programme_revision", "1");
        check(CinemaProgrammeNbt.readRequest(wrongType) == null, "request accepted a wrong field type");

        CompoundTag unknownAction = valid.copy();
        unknownAction.putString("programme_action", "NOT_AN_ACTION");
        check(CinemaProgrammeNbt.readRequest(unknownAction) == null, "request accepted an unknown action");
    }

    private static void viewRoundTripsWithTheStableWireKeys() {
        var view = new CinemaProgramme.View(
                CinemaProgramme.SCHEMA,
                UUID.randomUUID(),
                21,
                "Feature programme",
                List.of(
                        new CinemaProgramme.Clip(0, "Feature", 90_000),
                        new CinemaProgramme.Clip(1, "Credits", 12_000)
                ),
                0,
                1,
                7_500,
                90_000,
                102_000,
                "PLAYING",
                false,
                "",
                3_000,
                1_500
        );
        CompoundTag encoded = CinemaProgrammeNbt.writeView(view);
        check(encoded.getInt("schema") == CinemaProgramme.SCHEMA, "view schema missing");
        check(encoded.getList("clips", CompoundTag.TAG_COMPOUND).size() == 2, "view clips missing");
        check(CinemaProgrammeNbt.readView(encoded).equals(view), "view NBT roundtrip changed values");
    }

    private static void viewRejectsUnsupportedOrUnboundedPackets() {
        CompoundTag valid = CinemaProgrammeNbt.writeView(viewWithClips(1));

        CompoundTag unsupported = valid.copy();
        unsupported.putInt("schema", CinemaProgramme.SCHEMA + 1);
        check(CinemaProgrammeNbt.readView(unsupported) == null, "unsupported view schema was accepted");

        CompoundTag empty = valid.copy();
        empty.put("clips", new ListTag());
        check(CinemaProgrammeNbt.readView(empty) == null, "empty public programme was accepted");

        CompoundTag excessive = CinemaProgrammeNbt.writeView(viewWithClips(CinemaProgramme.MAX_CLIPS));
        excessive.getList("clips", CompoundTag.TAG_COMPOUND).add(new CompoundTag());
        check(CinemaProgrammeNbt.readView(excessive) == null, "oversized public programme was accepted");
    }

    private static void nullViewUsesTheExistingEmptyPacket() {
        check(CinemaProgrammeNbt.writeView(null).isEmpty(), "unavailable view must remain an empty packet");
        check(CinemaProgrammeNbt.readView(new CompoundTag()) == null, "empty packet must remain unavailable");
    }

    private static CinemaProgramme.View viewWithClips(int count) {
        var clips = new ArrayList<CinemaProgramme.Clip>();
        for (int i = 0; i < count; i++) clips.add(new CinemaProgramme.Clip(i, Integer.toString(i + 1), 1_000));
        return new CinemaProgramme.View(
                CinemaProgramme.SCHEMA,
                UUID.randomUUID(),
                1,
                "Programme",
                clips,
                0,
                -1,
                0,
                1_000,
                count * 1_000L,
                "READY",
                true,
                "",
                0,
                0
        );
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
