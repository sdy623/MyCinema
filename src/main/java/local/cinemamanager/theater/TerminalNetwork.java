package local.cinemamanager.theater;

import io.netty.buffer.ByteBuf;
import java.util.Locale;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.audio.TheaterAudioSettings;
import local.cinemamanager.media.MediaSource;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** MyCinema-owned TMS UI protocol over the unmodified WaterFrames media runtime. */
public final class TerminalNetwork {
    private TerminalNetwork() {}

    public record Request(BlockPos pos, String identity, String action, CompoundTag args) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID, "terminal_request"));
        public static final StreamCodec<ByteBuf, Request> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Request::pos, ByteBufCodecs.stringUtf8(36), Request::identity,
                ByteBufCodecs.stringUtf8(24), Request::action, ByteBufCodecs.COMPOUND_TAG, Request::args, Request::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Snapshot(BlockPos pos, String identity, boolean open, CompoundTag state) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CinemaManager.ID, "terminal_snapshot"));
        public static final StreamCodec<ByteBuf, Snapshot> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Snapshot::pos, ByteBufCodecs.stringUtf8(36), Snapshot::identity,
                ByteBufCodecs.BOOL, Snapshot::open, ByteBufCodecs.COMPOUND_TAG, Snapshot::state, Snapshot::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Request.TYPE, Request.CODEC, TerminalNetwork::handle);
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> local.cinemamanager.client.CinemaTerminalScreen.open(packet));
    }

    public static void open(ServerPlayer actor, CinemaTerminalTile terminal) {
        if (terminal == null || !terminal.canControl(actor)) return;
        PacketDistributor.sendToPlayer(actor, snapshot(terminal, true, ""));
    }

    private static Snapshot snapshot(CinemaTerminalTile terminal, boolean open, String notice) {
        CompoundTag state = new CompoundTag();
        state.putString("url", terminal.data.getUrl() == null ? "" : terminal.data.getUrl());
        state.putBoolean("active", terminal.data.active);
        state.putBoolean("paused", terminal.data.paused);
        state.putBoolean("loop", terminal.data.loop);
        state.putBoolean("linked", terminal.controlsLinked());
        state.putBoolean("playback_available", terminal.playbackAvailable());
        state.putBoolean("programme_managed", terminal.programmeManaged());
        state.putBoolean("library_managed", terminal.libraryManaged());
        state.putString("audio_mode", terminal.audioMode().id);
        var audio = terminal.audioAnchor();
        state.putString("audio_status", audio.status().name().toLowerCase(Locale.ROOT));
        if (audio.position() != null) {
            state.putDouble("audio_x", audio.position().x);
            state.putDouble("audio_y", audio.position().y);
            state.putDouble("audio_z", audio.position().z);
        }
        if (terminal.audienceCenter() != null) {
            state.putDouble("audience_x", terminal.audienceCenter().x);
            state.putDouble("audience_y", terminal.audienceCenter().y);
            state.putDouble("audience_z", terminal.audienceCenter().z);
        }
        if (terminal.clock() != null) {
            state.putLong("time", Math.max(0, terminal.clock().time()));
            state.putLong("duration", Math.max(0, terminal.clock().duration()));
            state.putBoolean("live", terminal.clock().live());
        }
        ListTag projectors = new ListTag();
        for (var entry : terminal.projectors().entrySet()) {
            CompoundTag member = new CompoundTag();
            member.putLong("pos", entry.getKey().asLong());
            member.putUUID("id", entry.getValue());
            if (terminal.getLevel() != null && terminal.getLevel().hasChunkAt(entry.getKey())
                    && terminal.getLevel().getBlockEntity(entry.getKey()) instanceof CinemaProjectorTile projector
                    && projector.identity().equals(entry.getValue()))
                member.putString("style", projector.style().getSerializedName());
            projectors.add(member);
        }
        state.put("projectors", projectors);
        if (notice != null && !notice.isBlank()) state.putString("notice", notice);
        return new Snapshot(terminal.getBlockPos(), terminal.identity().toString(), open, state);
    }

    private static void handle(Request packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer actor) || !actor.level().hasChunkAt(packet.pos())) return;
        if (!(actor.level().getBlockEntity(packet.pos()) instanceof CinemaTerminalTile terminal)
                || !terminal.identity().toString().equals(packet.identity()) || !terminal.canControl(actor)) return;
        CompoundTag args = packet.args() == null ? new CompoundTag() : packet.args();
        String notice = "";
        try {
            boolean managed = terminal.programmeManaged() || terminal.libraryManaged();
            switch (packet.action()) {
                case "refresh" -> { }
                case "bind" -> {
                    if (managed) throw new IllegalArgumentException("mycinema.terminal.managed");
                    terminal.bindNearby(actor);
                }
                case "unlink" -> {
                    if (managed) throw new IllegalArgumentException("mycinema.terminal.managed");
                    terminal.unlinkAll(actor);
                }
                case "linked" -> terminal.controlsLinked(args.getBoolean("value"));
                case "media" -> load(terminal, actor, args.getString("url"), managed);
                case "play" -> {
                    requireManual(managed);
                    if (terminal.data.getUrl() == null) throw new IllegalArgumentException("mycinema.terminal.no_media");
                    terminal.data.active = true;
                    if (terminal.clock() == null) terminal.tick();
                    terminal.setPause(false, false);
                }
                case "pause" -> { requireManual(managed); terminal.setPause(false, true); }
                case "stop" -> { requireManual(managed); terminal.setStop(false); }
                case "seek" -> {
                    requireManual(managed);
                    long target = args.getLong("time"), duration = terminal.clock() == null ? 0 : terminal.clock().duration();
                    if (target < 0 || duration > 0 && target > duration) throw new IllegalArgumentException("mycinema.terminal.seek_invalid");
                    terminal.seek(false, target);
                }
                case "loop" -> { requireManual(managed); terminal.loop(false, args.getBoolean("value")); }
                case "audio" -> {
                    CompoundTag request = new CompoundTag(); request.putUUID("terminal_id", terminal.identity()); request.putString("mode", args.getString("mode"));
                    if (!TheaterAudioSettings.select(terminal, actor, request)) throw new IllegalArgumentException("mycinema.terminal.audio_unavailable");
                }
                case "audience" -> {
                    CompoundTag request = new CompoundTag(); request.putUUID("terminal_id", terminal.identity());
                    request.putDouble("x", args.getDouble("x")); request.putDouble("y", args.getDouble("y")); request.putDouble("z", args.getDouble("z"));
                    if (!TheaterAudioSettings.calibrate(terminal, actor, request)) throw new IllegalArgumentException("mycinema.terminal.audience_invalid");
                }
                case "projector" -> {
                    if (terminal.controlsLinked()) throw new IllegalArgumentException("mycinema.terminal.unlink_controls_first");
                    BlockPos pos = BlockPos.of(args.getLong("pos"));
                    if (!args.hasUUID("id") || !args.getUUID("id").equals(terminal.projectors().get(pos)))
                        throw new IllegalArgumentException("mycinema.terminal.projector_changed");
                    me.srrapero720.waterframes.common.network.DisplayNetwork.openScreen(actor, pos, false);
                    return;
                }
                default -> throw new IllegalArgumentException("mycinema.terminal.invalid_action");
            }
            terminal.setChanged();
        } catch (RuntimeException exception) {
            notice = exception.getMessage() == null ? "mycinema.terminal.action_unavailable" : exception.getMessage();
        }
        PacketDistributor.sendToPlayer(actor, snapshot(terminal, false, notice));
    }

    private static void load(CinemaTerminalTile terminal, ServerPlayer actor, String source, boolean managed) {
        requireManual(managed);
        String normalized = MediaSource.normalize(source);
        if (normalized == null || !normalized.equals(source) || !CinemaPermissions.canSave(actor, source))
            throw new IllegalArgumentException("mycinema.terminal.media_denied");
        terminal.cleanClock();
        terminal.sourceReplaced();
        terminal.data.setUrl(source);
        terminal.data.active = true;
        terminal.data.paused = true;
        terminal.setPause(false, true);
        terminal.tick();
    }

    private static void requireManual(boolean managed) {
        if (managed) throw new IllegalArgumentException("mycinema.terminal.managed");
    }
}
