package local.cinemamanager.media;

import java.util.Objects;
import java.util.UUID;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.theater.CinemaTerminalTile;
import me.srrapero720.waterframes.common.block.data.DisplayData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.watermedia.api.player.MediaPlayer;

/** MyCinema's stable source/session contract over unmodified WaterFrames and WaterMedia. */
public final class CinemaSources {
    public static final int MAX_SOURCE_INDEX = 16_384;

    public record ControllerRef(BlockPos pos, UUID identity) {
        public ControllerRef {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(identity, "identity");
        }
    }

    public record SourceRef(String url, int source) {}

    public record SessionView(UUID terminal, UUID epoch, String url, int source, int revision,
                              MediaPlayer.Status status, long timeMs, long durationMs, boolean live,
                              boolean metadataReady, UUID evidenceId, boolean ready, String failure) {}

    public record Result(String error, SessionView session) {}

    public static boolean validSource(SourceRef source) {
        if (source == null || source.url == null || source.source < 0 || source.source > MAX_SOURCE_INDEX) return false;
        String normalized = MediaSource.normalize(source.url);
        return source.url.equals(normalized);
    }

    public static boolean controlledBy(CinemaTerminalTile terminal, ControllerRef controller) {
        return loaded(terminal) && terminal.sourceControllerMatches(controller);
    }

    public static SessionView snapshot(CinemaTerminalTile terminal) {
        if (!loaded(terminal)) return null;
        var clock = terminal.clock();
        // A persisted source whose WaterFrames clock has not opened yet is an
        // initializing session, not proof that the source was stopped.  The -1
        // revision is WaterFrames' own initializing sentinel and lets restore
        // logic wait for an actual clock before acknowledging Halt or Ready.
        boolean initializing = clock == null && terminal.data.getUrl() != null;
        MediaPlayer.Status status = clock == null
                ? (initializing ? MediaPlayer.Status.WAITING : MediaPlayer.Status.STOPPED)
                : clock.status();
        long time = clock == null ? 0 : Math.max(0, clock.time());
        long duration = clock == null ? 0 : Math.max(0, clock.duration());
        if (duration == 0 && terminal.sourceEvidenceId() != null) duration = terminal.sourceDurationMs();
        boolean live = clock != null && clock.live();
        int revision = clock == null ? (initializing ? -1 : 0) : clock.revision();
        boolean metadata = terminal.sourceMetadataReady() || duration > 0 && !live;
        // A server clock or saved evidence is not a client decoder Ready signal.
        boolean ready = terminal.sourceReady();
        return new SessionView(terminal.identity(), terminal.mediaEpoch(), value(terminal.data.getUrl()),
                terminal.data.getSource(), revision, status, time, duration, live, metadata,
                terminal.sourceEvidenceId(), ready, terminal.sourceFailure());
    }

    public static String delegatedSourceError(CinemaTerminalTile terminal, UUID owner,
                                              ControllerRef controller, String source) {
        String authority = delegatedAuthorityError(terminal, owner, controller);
        if (authority != null) return authority;
        var grant = terminal.automationGrant();
        ManagerTile manager = controller(terminal, controller);
        String normalized = MediaSource.normalize(source);
        if (normalized == null || !normalized.equals(source)) return "invalid_source";
        ServerPlayer online = ((ServerLevel) terminal.getLevel()).getServer().getPlayerList().getPlayer(owner);
        var mode = online == null ? grant.mode() : online.gameMode.getGameModeForPlayer();
        if (CinemaPermissions.canAutomateTheater(owner, mode, terminal.getBlockState().getBlock(), source, true)) return null;
        return manager.playback.localSourceAllowed(terminal.identity(), source)
                && CinemaPermissions.canAutomateLocalProgramme(((ServerLevel) terminal.getLevel()).getServer(), owner,
                mode, terminal.getBlockState().getBlock(), source) ? null : "media_permission";
    }

    private static String delegatedAuthorityError(CinemaTerminalTile terminal, UUID owner,
                                                   ControllerRef controller) {
        ManagerTile manager = controller(terminal, controller);
        if (manager == null || owner == null || manager.owner == null || !owner.equals(manager.owner)
                || !terminal.ownedBy(owner)) return "permission";
        var grant = terminal.automationGrant();
        if (grant == null || !grant.matches(terminal.identity(), controller.identity(), owner, controller.pos()))
            return "grant_revoked";
        return null;
    }

    public static Result prepare(CinemaTerminalTile terminal, ServerPlayer actor, ControllerRef controller,
                                 SourceRef source, UUID expectedEpoch, UUID evidenceId) {
        String error = manual(terminal, actor, controller, source == null ? null : source.url(), true);
        return error == null ? prepareChecked(terminal, actor.getUUID(), controller, source, expectedEpoch, evidenceId)
                : denied(error, terminal);
    }

    public static Result prepareDelegated(CinemaTerminalTile terminal, UUID owner, ControllerRef controller,
                                          SourceRef source, UUID expectedEpoch, UUID evidenceId) {
        String error = delegatedSourceError(terminal, owner, controller, source == null ? null : source.url());
        return error == null ? prepareChecked(terminal, owner, controller, source, expectedEpoch, evidenceId)
                : denied(error, terminal);
    }

    private static Result prepareChecked(CinemaTerminalTile terminal, UUID owner, ControllerRef controller,
                                         SourceRef source, UUID expectedEpoch, UUID evidenceId) {
        if (!validSource(source)) return denied("invalid_source", terminal);
        if (expectedEpoch != null && !expectedEpoch.equals(terminal.mediaEpoch())) return denied("stale_session", terminal);
        CinemaSourceEvidence.Evidence evidence = null;
        if (evidenceId != null) {
            evidence = CinemaSourceEvidence.get((ServerLevel) terminal.getLevel()).find(evidenceId);
            if (evidence == null || !evidence.matches(owner, source.url(), source.source()))
                return denied("evidence_invalid", terminal);
        }
        boolean changed = !Objects.equals(terminal.data.getUrl(), source.url())
                || terminal.data.getSource() != source.source();
        if (changed) setSource(terminal, source, owner);
        // A programme occurrence is a new lease even when two adjacent clips use
        // the same URL. Drop the old WF clock so its revision, time and Ready
        // signal cannot be reused across occurrences.
        terminal.sourceReplaced();
        terminal.cleanClock();
        terminal.data.loop = false;
        terminal.data.active = true;
        terminal.data.paused = true;
        terminal.setPause(false, true);
        terminal.sourceState(controller, owner, evidenceId, evidence == null ? 0 : evidence.durationMs(),
                false, evidence != null, "", true);
        // Official WaterFrames opens its server clock from DisplayTile.tick(). Open
        // it in this same atomic prepare so the returned revision is the revision
        // that a following guarded Resume must use, never a synthetic zero.
        if (terminal.clock() == null) terminal.tick();
        // The evidence duration was previously authenticated from this exact
        // owner/source. Seed the new server clock so natural Ended transitions
        // work before another client metadata report arrives; Ready remains a
        // separate per-session signal.
        if (evidence != null && terminal.clock() != null && evidence.durationMs() > 0
                && terminal.clock().duration() <= 0) terminal.clock().syncDuration(evidence.durationMs());
        terminal.setChanged();
        return new Result(null, snapshot(terminal));
    }

    public static Result resumeAt(CinemaTerminalTile terminal, ServerPlayer actor, ControllerRef controller,
                                  UUID expectedEpoch, int expectedRevision, long positionMs, boolean remainPaused) {
        String error = manual(terminal, actor, controller, terminal == null ? null : terminal.data.getUrl(), false);
        return error == null ? resumeChecked(terminal, expectedEpoch, expectedRevision, positionMs, remainPaused)
                : denied(error, terminal);
    }

    public static Result resumeAtDelegated(CinemaTerminalTile terminal, UUID owner, ControllerRef controller,
                                           UUID expectedEpoch, int expectedRevision, long positionMs, boolean remainPaused) {
        String error = delegatedSourceError(terminal, owner, controller, terminal == null ? null : terminal.data.getUrl());
        return error == null ? resumeChecked(terminal, expectedEpoch, expectedRevision, positionMs, remainPaused)
                : denied(error, terminal);
    }

    private static Result resumeChecked(CinemaTerminalTile terminal, UUID expectedEpoch, int expectedRevision,
                                        long positionMs, boolean remainPaused) {
        SessionView before = snapshot(terminal);
        if (before == null || expectedEpoch == null || !expectedEpoch.equals(before.epoch())
                || before.revision() != expectedRevision || positionMs < 0
                || before.durationMs() > 0 && positionMs > before.durationMs()) return denied("stale_session", terminal);
        var clock = terminal.clock();
        if (clock == null) {
            terminal.restartClock();
            clock = terminal.clock();
        }
        if (clock == null) return denied("unavailable", terminal);
        clock.seek(positionMs);
        clock.pause(remainPaused);
        terminal.data.active = true;
        terminal.data.paused = remainPaused;
        terminal.libraryStarted();
        terminal.setChanged();
        return new Result(null, snapshot(terminal));
    }

    public static Result halt(CinemaTerminalTile terminal, ServerPlayer actor, ControllerRef controller, UUID expectedEpoch) {
        String error = manual(terminal, actor, controller, terminal == null ? null : terminal.data.getUrl(), false);
        return error == null ? haltChecked(terminal, expectedEpoch) : denied(error, terminal);
    }

    public static Result haltDelegated(CinemaTerminalTile terminal, UUID owner, ControllerRef controller, UUID expectedEpoch) {
        String error = delegatedAuthorityError(terminal, owner, controller);
        return error == null ? haltChecked(terminal, expectedEpoch) : denied(error, terminal);
    }

    private static Result haltChecked(CinemaTerminalTile terminal, UUID expectedEpoch) {
        if (expectedEpoch == null || !expectedEpoch.equals(terminal.mediaEpoch())) return denied("stale_session", terminal);
        if (terminal.clock() == null && terminal.data.getUrl() != null)
            return denied("clock_initializing", terminal);
        if (terminal.clock() != null) terminal.clock().stop();
        terminal.data.paused = true;
        terminal.data.active = false;
        terminal.setStop(false);
        terminal.setChanged();
        return new Result(null, snapshot(terminal));
    }

    public static Result presentationHoldDelegated(CinemaTerminalTile terminal, UUID owner, ControllerRef controller,
                                                    UUID expectedEpoch, boolean hold) {
        String error = delegatedAuthorityError(terminal, owner, controller);
        if (error != null) return denied(error, terminal);
        if (expectedEpoch == null || !expectedEpoch.equals(terminal.mediaEpoch())) return denied("stale_session", terminal);
        terminal.programmePresentationHold(hold);
        return new Result(null, snapshot(terminal));
    }

    private static String manual(CinemaTerminalTile terminal, ServerPlayer actor, ControllerRef controller,
                                 String source, boolean assignsSource) {
        if (!loaded(terminal) || actor == null || actor.level() != terminal.getLevel() || !terminal.canControl(actor)
                || !terminal.ownedBy(actor.getUUID()) || !CinemaPermissions.canBindRemote(actor)
                || !CinemaPermissions.canInteractRemote(actor)) return "permission";
        ManagerTile manager = controller(terminal, controller);
        if (manager == null || manager.owner == null || !manager.owner.equals(actor.getUUID())
                || !manager.controlAllowed(actor)) return "controller";
        if (!assignsSource) return null;
        return CinemaPermissions.canSave(actor, source) ? null : "media_permission";
    }

    private static ManagerTile controller(CinemaTerminalTile terminal, ControllerRef ref) {
        if (!loaded(terminal) || ref == null || !(terminal.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(ref.pos()) || !(level.getBlockEntity(ref.pos()) instanceof ManagerTile manager)
                || !ref.identity().equals(manager.identity)) return null;
        try {
            return manager.programmeTerminal(manager.state.halls.stream()
                    .filter(hall -> hall.terminalIdentity.equals(terminal.identity().toString()))
                    .findFirst().orElseThrow().id) == terminal ? manager : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean loaded(CinemaTerminalTile terminal) {
        return terminal != null && terminal.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()
                && !terminal.isRemoved() && level.hasChunkAt(terminal.getBlockPos())
                && level.getBlockEntity(terminal.getBlockPos()) == terminal;
    }

    private static void setSource(CinemaTerminalTile terminal, SourceRef source, UUID owner) {
        if (source.source() == 0) {
            terminal.data.setUrl(source.url());
            return;
        }
        terminal.data.setUrl(null);
        terminal.data.playlist.clear();
        terminal.data.playlist.add(new DisplayData.Entry(source.url(), source.source(), owner));
        terminal.data.entryIndex = 0;
    }

    private static Result denied(String error, CinemaTerminalTile terminal) {
        return new Result(error == null ? "unavailable" : error, snapshot(terminal));
    }

    private static String value(String source) { return source == null ? "" : source; }
    private CinemaSources() {}
}
