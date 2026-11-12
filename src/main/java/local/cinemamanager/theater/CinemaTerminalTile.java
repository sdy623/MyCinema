package local.cinemamanager.theater;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.automation.CinemaAutomation;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import me.srrapero720.waterframes.client.display.Display;
import local.cinemamanager.audio.TheaterAudioAnchor;
import local.cinemamanager.audio.TheaterAudioMode;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative cinema terminal backed by the official WaterFrames display runtime. */
public class CinemaTerminalTile extends CinemaDeviceTile {
    public static final int BIND_RANGE = 16;
    public static final int MAX_PROJECTORS = 2;

    private UUID identity = UUID.randomUUID();
    private final Map<BlockPos, UUID> projectors = new LinkedHashMap<>();
    private boolean controlsLinked = true;
    private UUID mediaEpoch = UUID.randomUUID();
    private CinemaSources.ControllerRef sourceController;
    private UUID sourceAuthorityOwner;
    private UUID sourceEvidence;
    private long sourceDurationMs;
    private boolean metadataReady;
    private boolean sourceReady;
    private String sourceFailure = "";
    private boolean libraryManaged;
    private boolean programmeManaged;
    private boolean programmePresentationHold;
    private CinemaAutomation.Grant automationGrant;
    private long savedPosition;
    private boolean restorePositionPending;
    private TheaterAudioMode audioMode = TheaterAudioMode.PROJECTOR;
    private Vec3 audienceCenter;

    public CinemaTerminalTile(BlockPos pos, BlockState state) {
        super(CinemaManager.TMS_TERMINAL_TILE.get(), pos, state);
        data.maxVolumeDistance = Math.max(data.maxVolumeDistance, 64);
        data.minVolumeDistance = 64;
    }

    public UUID identity() { return identity; }
    public Map<BlockPos, UUID> projectors() { return Map.copyOf(projectors); }
    public boolean controlsLinked() { return controlsLinked; }
    public void controlsLinked(boolean value) { controlsLinked = value; setChanged(); }
    public UUID mediaEpoch() { return mediaEpoch; }
    public CinemaAutomation.Grant automationGrant() { return automationGrant; }
    public void automationGrant(CinemaAutomation.Grant grant) { automationGrant = grant; setChanged(); }
    public void revokeAutomation() { automationGrant = null; setChanged(); }
    public boolean libraryManaged() { return libraryManaged; }
    public boolean programmeManaged() { return programmeManaged; }
    public void programmeManaged(boolean value) { programmeManaged = value; setChanged(); }
    public boolean programmePresentationHeld() { return programmePresentationHold; }
    public void programmePresentationHold(boolean hold) { programmePresentationHold = hold; setChanged(); }
    public UUID sourceEvidenceId() { return sourceEvidence; }
    public long sourceDurationMs() { return sourceDurationMs; }
    public boolean sourceMetadataReady() { return metadataReady; }
    public boolean sourceReady() { return sourceReady && sourceFailure.isEmpty(); }
    public String sourceFailure() { return sourceFailure; }
    public Display activeDisplay() { return display == null || display.isReleased() ? null : display; }
    public TheaterAudioMode audioMode() { return audioMode; }
    public Vec3 audienceCenter() { return audienceCenter; }
    public void audioMode(TheaterAudioMode mode) { if (mode != null) { audioMode = mode; setChanged(); } }
    public void audienceCenter(Vec3 point) { audienceCenter = point; setChanged(); }
    public TheaterAudioAnchor.Resolution audioAnchor() { return TheaterAudioAnchor.resolve(this); }
    public Vec3 audioOrigin() { var anchor = audioAnchor(); return anchor.available() ? anchor.position() : null; }
    public CinemaSources.ControllerRef sourceController() { return sourceController; }
    public UUID sourceAuthorityOwner() { return sourceAuthorityOwner; }
    public boolean sourceControllerMatches(CinemaSources.ControllerRef controller) { return Objects.equals(sourceController, controller); }

    public void sourceState(CinemaSources.ControllerRef controller, UUID authorityOwner, UUID evidence,
                            long durationMs, boolean ready, boolean metadata, String failure, boolean managed) {
        sourceController = controller;
        sourceAuthorityOwner = authorityOwner;
        sourceEvidence = evidence;
        sourceDurationMs = Math.max(0, durationMs);
        sourceReady = ready;
        metadataReady = metadata;
        sourceFailure = failure == null ? "" : failure;
        libraryManaged = managed;
        setChanged();
    }

    public void libraryStarted() { sourceReady = true; sourceFailure = ""; setChanged(); }

    public void cancelLibraryControl() {
        sourceController = null;
        sourceAuthorityOwner = null;
        sourceEvidence = null;
        sourceDurationMs = 0;
        sourceReady = false;
        metadataReady = false;
        libraryManaged = false;
        programmeManaged = false;
        programmePresentationHold = false;
        sourceFailure = "";
        setChanged();
    }

    public boolean contains(CinemaProjectorTile projector) {
        return projector != null && Objects.equals(projectors.get(projector.getBlockPos()), projector.identity());
    }

    public boolean playbackAvailable() {
        if (getLevel() == null || projectors.isEmpty() || projectors.size() > MAX_PROJECTORS) return false;
        for (var link : projectors.entrySet()) {
            if (!getLevel().hasChunkAt(link.getKey())
                    || !(getLevel().getBlockEntity(link.getKey()) instanceof CinemaProjectorTile projector)
                    || !link.getValue().equals(projector.identity()) || !projector.linkedTo(this)) return false;
        }
        return true;
    }

    public int bindNearby(ServerPlayer actor) {
        if (!canControl(actor) || getLevel() == null || getLevel().isClientSide) return 0;
        var candidates = new ArrayList<CinemaProjectorTile>();
        int minChunkX = (getBlockPos().getX() - BIND_RANGE) >> 4;
        int maxChunkX = (getBlockPos().getX() + BIND_RANGE) >> 4;
        int minChunkZ = (getBlockPos().getZ() - BIND_RANGE) >> 4;
        int maxChunkZ = (getBlockPos().getZ() + BIND_RANGE) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++) {
            if (!getLevel().getChunkSource().hasChunk(x, z)) continue;
            for (var tile : getLevel().getChunk(x, z).getBlockEntities().values()) {
                if (tile instanceof CinemaProjectorTile projector && projector.allows(actor)
                        && getBlockPos().distSqr(projector.getBlockPos()) <= BIND_RANGE * BIND_RANGE)
                    candidates.add(projector);
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getBlockPos().distSqr(getBlockPos())));
        int before = projectors.size();
        for (var projector : candidates) {
            if (projectors.size() >= MAX_PROJECTORS) break;
            if (!contains(projector)) projector.bind(this);
        }
        // Re-running the explicit group bind is also the recovery path from
        // per-projector edit mode.  It never changes membership implicitly.
        if (!projectors.isEmpty()) controlsLinked = true;
        setChanged();
        return projectors.size() - before;
    }

    public void unlinkAll(ServerPlayer actor) {
        if (!canControl(actor) || getLevel() == null) return;
        for (BlockPos pos : new ArrayList<>(projectors.keySet())) {
            if (getLevel().hasChunkAt(pos) && getLevel().getBlockEntity(pos) instanceof CinemaProjectorTile projector)
                projector.unbind(this);
        }
        projectors.clear();
        setChanged();
    }

    public boolean link(CinemaProjectorTile projector) {
        if (projector == null || projector.getLevel() != getLevel()) return false;
        pruneLinks();
        if (!contains(projector) && projectors.size() >= MAX_PROJECTORS) return false;
        projectors.put(projector.getBlockPos().immutable(), projector.identity());
        setChanged();
        return true;
    }

    public void unlink(CinemaProjectorTile projector) {
        if (projector != null && Objects.equals(projectors.get(projector.getBlockPos()), projector.identity())) {
            projectors.remove(projector.getBlockPos());
            setChanged();
        }
    }

    private void pruneLinks() {
        if (getLevel() == null) return;
        projectors.entrySet().removeIf(entry -> getLevel().hasChunkAt(entry.getKey())
                && (!(getLevel().getBlockEntity(entry.getKey()) instanceof CinemaProjectorTile projector)
                || !entry.getValue().equals(projector.identity()) || !projector.linkedTo(this)));
    }

    public boolean theaterClockCurrent() {
        return clock() != null && data.getUrl() != null;
    }

    public void resetPosition() {
        savedPosition = 0;
        restorePositionPending = false;
        if (clock() != null) clock().seek(0);
        setChanged();
    }

    public void sourceReplaced() {
        mediaEpoch = UUID.randomUUID();
        savedPosition = 0;
        restorePositionPending = false;
        cancelLibraryControl();
    }

    public void rememberMetadata() {
        if (clock() != null && clock().duration() > 0 && !clock().live()) {
            metadataReady = true;
            sourceReady = true;
            savedPosition = clock().time();
            setChanged();
        }
    }

    @Override
    public void report(long duration, boolean live) {
        super.report(duration, live);
        if (getLevel() instanceof net.minecraft.server.level.ServerLevel server && duration > 0) {
            metadataReady = true;
            sourceReady = true;
            sourceDurationMs = duration;
            if (!live && sourceAuthorityOwner != null && data.getUrl() != null) {
                var evidence = local.cinemamanager.media.CinemaSourceEvidence.get(server)
                        .remember(sourceAuthorityOwner, identity, data.getUrl(), data.getSource(), duration);
                if (evidence != null) sourceEvidence = evidence.id();
            }
            setChanged();
        }
    }

    @Override
    protected void onOwnerChanged() {
        super.onOwnerChanged();
        revokeAutomation();
        cancelLibraryControl();
    }

    @Override
    public void tick() {
        super.tick();
        pruneLinks();
        if (clock() != null) {
            if (restorePositionPending) {
                if (sourceDurationMs > 0 && clock().duration() <= 0) clock().syncDuration(sourceDurationMs);
                if (savedPosition == 0 || clock().duration() <= 0 || savedPosition <= clock().duration()) {
                    clock().seek(savedPosition);
                    clock().pause(true);
                    restorePositionPending = false;
                }
            }
            // Viewer readiness is separate from the user's transport intent.
            // Keep the official clock paused behind MyCinema's presentation
            // gate without rewriting data.paused (the saved Play/Pause intent).
            if (programmePresentationHold && clock().playing()) clock().pause(true);
            long observed = Math.max(0, clock().time());
            // WaterMedia's server clock is wall-clock based. A dedicated server tick
            // can complete inside one millisecond (especially GameTest), so retain a
            // monotonic floor without changing normal real-time progression.
            if (clock().playing() && observed <= savedPosition) {
                clock().syncTime(savedPosition + 1);
                observed = clock().time();
            }
            savedPosition = observed;
        }
    }

    public void tick(BlockState ignored) { tick(); }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putUUID("terminal_id", identity);
        tag.putBoolean("controls_linked", controlsLinked);
        tag.putUUID("mycinema_media_epoch", mediaEpoch);
        tag.putLong("theater_position", clock() == null ? savedPosition : clock().time());
        tag.putBoolean("programme_managed", programmeManaged);
        tag.putBoolean("programme_hold", programmePresentationHold);
        CompoundTag audio = new CompoundTag(); audio.putString("mode", audioMode.id);
        if (audienceCenter != null) { audio.putDouble("x", audienceCenter.x); audio.putDouble("y", audienceCenter.y); audio.putDouble("z", audienceCenter.z); }
        tag.put("theater_audio", audio);
        if (automationGrant != null) tag.put("theater_automation", automationGrant.save());
        if (libraryManaged && sourceController != null) {
            var source = new CompoundTag();
            source.putString("source", data.getUrl() == null ? "" : data.getUrl());
            source.putUUID("controller", sourceController.identity());
            source.putLong("pos", sourceController.pos().asLong());
            if (sourceAuthorityOwner != null) source.putUUID("owner", sourceAuthorityOwner);
            if (sourceEvidence != null) source.putUUID("evidence", sourceEvidence);
            if (sourceDurationMs > 0) source.putLong("duration", sourceDurationMs);
            source.putBoolean("ready", sourceReady);
            source.putBoolean("metadata", metadataReady);
            source.putString("failure", sourceFailure);
            tag.put("library_source", source);
        }
        var links = new ListTag();
        for (var entry : projectors.entrySet()) {
            var link = new CompoundTag();
            link.putLong("pos", entry.getKey().asLong());
            link.putUUID("id", entry.getValue());
            links.add(link);
        }
        tag.put("projectors", links);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        // A disk load is a new in-memory media session even when the serialized
        // URL is unchanged. DisplayTile keeps a same-key clock by default, so
        // explicitly discard it before rotating MyCinema's epoch.
        cleanClock();
        if (tag.hasUUID("terminal_id")) identity = tag.getUUID("terminal_id");
        controlsLinked = !tag.contains("controls_linked", Tag.TAG_BYTE) || tag.getBoolean("controls_linked");
        // A media epoch identifies one in-memory WaterMedia session. Disk data may
        // describe the source to restore, but must never resurrect command authority.
        mediaEpoch = UUID.randomUUID();
        savedPosition = tag.getLong("theater_position");
        restorePositionPending = savedPosition > 0 && data.getUrl() != null;
        programmeManaged = tag.getBoolean("programme_managed");
        programmePresentationHold = tag.getBoolean("programme_hold");
        CompoundTag audio = tag.getCompound("theater_audio"); TheaterAudioMode loadedMode = TheaterAudioMode.parse(audio.getString("mode"));
        audioMode = loadedMode == null ? TheaterAudioMode.PROJECTOR : loadedMode;
        audienceCenter = audio.contains("x") && audio.contains("y") && audio.contains("z")
                ? new Vec3(audio.getDouble("x"), audio.getDouble("y"), audio.getDouble("z")) : null;
        automationGrant = tag.contains("theater_automation", Tag.TAG_COMPOUND)
                ? CinemaAutomation.Grant.load(tag.getCompound("theater_automation")) : null;
        projectors.clear();
        ListTag links = tag.getList("projectors", Tag.TAG_COMPOUND);
        for (int i = 0; i < links.size() && projectors.size() < MAX_PROJECTORS; i++) {
            CompoundTag link = links.getCompound(i);
            if (link.contains("pos", Tag.TAG_LONG) && link.hasUUID("id"))
                projectors.put(BlockPos.of(link.getLong("pos")), link.getUUID("id"));
        }
        sourceController = null;
        sourceAuthorityOwner = null;
        sourceEvidence = null;
        sourceDurationMs = 0;
        sourceReady = false;
        metadataReady = false;
        libraryManaged = false;
        sourceFailure = "";
        if (tag.contains("library_source", Tag.TAG_COMPOUND)) {
            CompoundTag source = tag.getCompound("library_source");
            if (source.hasUUID("controller") && source.contains("pos", Tag.TAG_LONG)) {
                sourceController = new CinemaSources.ControllerRef(BlockPos.of(source.getLong("pos")), source.getUUID("controller"));
                sourceAuthorityOwner = source.hasUUID("owner") ? source.getUUID("owner") : theaterOwner();
                sourceEvidence = source.hasUUID("evidence") ? source.getUUID("evidence") : null;
                sourceDurationMs = source.contains("duration", Tag.TAG_LONG) ? Math.max(0, source.getLong("duration")) : 0;
                // Viewer Ready and decoder metadata belong to the old in-memory
                // session. The persisted evidence remains available for revalidation.
                sourceReady = false;
                metadataReady = false;
                sourceFailure = source.getString("failure");
                libraryManaged = true;
            }
        }
        data.paused = true;
    }
}
