package local.cinemamanager.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.audio.TheaterAudioSettings;
import local.cinemamanager.theater.CinemaProgrammeNbt;
import local.cinemamanager.projection.CinemaProjectionSettings;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Allowlisted MyCinema theater control endpoint; no private WaterFrames packet is required. */
public record TheaterPacket(BlockPos pos, int action, CompoundTag values) implements CustomPacketPayload {
    public static final int MEDIA=0, PLAY=1, PAUSE=2, SEEK=3, BIND=4, UNLINK=5, GEOMETRY=6, AUDIO=7,
            STOP=8, LOOP=9, SETTINGS=10, PLAYLIST=11, LINK_CONTROLS=12, GROUP_SETTINGS=13,
            AUDIO_ORIGIN=14, AUDIENCE_CENTER=15, PROGRAMME=16;
    public static final Type<TheaterPacket> TYPE = new Type<>(CinemaManager.id("theater_control"));
    public static final StreamCodec<ByteBuf, TheaterPacket> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TheaterPacket::pos, ByteBufCodecs.VAR_INT, TheaterPacket::action,
            ByteBufCodecs.COMPOUND_TAG, TheaterPacket::values, TheaterPacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register(PayloadRegistrar registrar) { registrar.playToServer(TYPE, CODEC, TheaterPacket::handle); }

    public static void handle(TheaterPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer actor)) return;
        if (!actor.level().hasChunkAt(packet.pos())) return;
        var tile = actor.level().getBlockEntity(packet.pos());
        CompoundTag tag = packet.values() == null ? new CompoundTag() : packet.values();
        if (packet.action() == AUDIO_ORIGIN && tile instanceof CinemaTerminalTile terminal) { TheaterAudioSettings.select(terminal, actor, tag); return; }
        if (packet.action() == AUDIENCE_CENTER && tile instanceof CinemaTerminalTile terminal) { TheaterAudioSettings.calibrate(terminal, actor, tag); return; }
        if (packet.action() == LINK_CONTROLS && tile instanceof CinemaTerminalTile terminal) {
            if (terminal.canControl(actor) && terminal.identity().equals(tag.hasUUID("terminal_id") ? tag.getUUID("terminal_id") : null)
                    && tag.getAllKeys().equals(java.util.Set.of("terminal_id", "linked"))) terminal.controlsLinked(tag.getBoolean("linked"));
            return;
        }
        if (packet.action() == GROUP_SETTINGS && tile instanceof CinemaTerminalTile terminal) { group(terminal, actor, tag); return; }
        if ((packet.action() == SETTINGS || packet.action() == GEOMETRY) && tile instanceof CinemaProjectorTile projector) {
            if (!projector.directControlsLocked() && projector.canControl(actor) && CinemaProjectionSettings.valid(tag)) CinemaProjectionSettings.apply(projector, tag);
            return;
        }
        if (packet.action() == STOP && tile instanceof CinemaTerminalTile terminal && terminal.canControl(actor)) {
            if (terminal.programmeManaged()) {
                var request = CinemaProgrammeNbt.readRequest(tag);
                var controller = terminal.sourceController();
                // A programme-owned TMS stop must pass the same current request,
                // owner, controller, grant and epoch checks as every other
                // programme command. Malformed or stale packets fail closed.
                if (request != null && controller != null && actor.level().hasChunkAt(controller.pos())
                        && actor.level().getBlockEntity(controller.pos()) instanceof ManagerTile manager
                        && manager.identity.equals(controller.identity()))
                    manager.theaterProgrammeControl(actor, terminal, request);
                return;
            }
            terminal.setStop(false);
        }
    }

    private static void group(CinemaTerminalTile terminal, ServerPlayer actor, CompoundTag request) {
        if (!terminal.canControl(actor) || !terminal.controlsLinked() || !request.hasUUID("terminal_id")
                || !terminal.identity().equals(request.getUUID("terminal_id")) || !request.hasUUID("projector_id")
                || !request.contains("projector_pos") || !request.contains("settings") || request.getBoolean("linked") != terminal.controlsLinked()) return;
        BlockPos referencePos = BlockPos.of(request.getLong("projector_pos"));
        if (!terminal.projectors().getOrDefault(referencePos, new java.util.UUID(0,0)).equals(request.getUUID("projector_id"))) return;
        CompoundTag settings = request.getCompound("settings"); if (!CinemaProjectionSettings.valid(settings)) return;
        ArrayList<CinemaProjectorTile> members = new ArrayList<>();
        for (var entry : terminal.projectors().entrySet()) {
            if (!actor.level().hasChunkAt(entry.getKey()) || !(actor.level().getBlockEntity(entry.getKey()) instanceof CinemaProjectorTile member)
                    || !entry.getValue().equals(member.identity()) || !member.linkedTo(terminal) || !member.ownedBy(actor.getUUID())) return;
            members.add(member);
        }
        if (members.size() != terminal.projectors().size() || !(actor.level().getBlockEntity(referencePos) instanceof CinemaProjectorTile reference)) return;
        float dx = settings.contains(CinemaProjectionSettings.OFFSET_X) ? settings.getFloat(CinemaProjectionSettings.OFFSET_X) - reference.projectionOffsetX : 0;
        float dy = settings.contains(CinemaProjectionSettings.OFFSET_Y) ? settings.getFloat(CinemaProjectionSettings.OFFSET_Y) - reference.projectionOffsetY : 0;
        float dz = settings.contains(CinemaProjectionSettings.OFFSET_Z) ? settings.getFloat(CinemaProjectionSettings.OFFSET_Z) - reference.projectionOffsetZ : 0;
        for (CinemaProjectorTile member : members) if (Math.abs(member.projectionOffsetX + dx) > 512 || Math.abs(member.projectionOffsetY + dy) > 512 || Math.abs(member.projectionOffsetZ + dz) > 512) return;
        for (CinemaProjectorTile member : members) {
            CompoundTag update = settings.copy();
            if (settings.contains(CinemaProjectionSettings.OFFSET_X)) update.putFloat(CinemaProjectionSettings.OFFSET_X, member.projectionOffsetX + dx);
            if (settings.contains(CinemaProjectionSettings.OFFSET_Y)) update.putFloat(CinemaProjectionSettings.OFFSET_Y, member.projectionOffsetY + dy);
            if (settings.contains(CinemaProjectionSettings.OFFSET_Z)) update.putFloat(CinemaProjectionSettings.OFFSET_Z, member.projectionOffsetZ + dz);
            CinemaProjectionSettings.apply(member, update);
        }
    }
}
