package local.cinemamanager.library;

import local.cinemamanager.catalog.FilmCatalog;

import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic, server-thread-confined playback of one immutable, ordered film snapshot.
 *
 * <p>The adapter supplies authoritative CURRENT session facts, checks permissions/evidence, and
 * executes at most {@link #pending()} with its exact epoch and operation identity. Every prepare
 * must atomically replace the source in a paused state. A command is not removed until acknowledged;
 * polling it does not authorize executing it twice. Asynchronous replies use {@link #acknowledge},
 * never {@link #observe}. Observations are current reads, not historical callback snapshots.</p>
 *
 * <p>No clock, world, decoder, file, network, or client-authored duration is consulted here. Saved
 * state contains a snapshot ID, never a mutable film ID. Resolving that ID and proving the saved
 * controller lease at restore time are the adapter's responsibility.</p>
 */
public final class FilmProgramState {
    public enum Status { PREPARING, READY, PLAYING, PAUSED, GAP, FAILED, COMPLETED, STOPPED, OVERRIDDEN }
    public enum Playback { WAITING, PLAYING, PAUSED, STOPPED, ENDED }

    /**
     * Facts accepted by the server-side playback service, not values received from a client.
     * pausedIntent is the authoritative TMS data.paused value: a clock may also be held PAUSED
     * while viewer Ready is absent, without changing the requested transport intent.
     */
    public record Session(UUID epoch, String source, int sourceType, int revision, Playback playback,
                          long timeMs, long durationMs, boolean live, boolean metadataReady,
                          UUID evidenceId, boolean ready, String failure, boolean pausedIntent) {
        /** Compatibility for callers whose clock status already expresses their transport intent. */
        public Session(UUID epoch, String source, int sourceType, int revision, Playback playback,
                       long timeMs, long durationMs, boolean live, boolean metadataReady,
                       UUID evidenceId, boolean ready, String failure) {
            this(epoch, source, sourceType, revision, playback, timeMs, durationMs, live, metadataReady,
                    evidenceId, ready, failure, playback == Playback.PAUSED);
        }
        public Session {
            Objects.requireNonNull(epoch, "epoch");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(playback, "playback");
            failure = failure == null ? "" : failure;
        }
    }

    public sealed interface Command permits Prepare, Resume, Halt {
        long operation();
        UUID expectedEpoch();
    }
    /** The adapter must use this segment's evidence and disable looping when preparing it. */
    public record Prepare(long operation, UUID expectedEpoch, int segmentIndex, String source,
                          UUID evidenceId) implements Command {}
    /** Both ordinary seeks and play/pause use the exact existing session and WF revision. */
    public record Resume(long operation, UUID expectedEpoch, int expectedRevision, long localMs,
                         boolean remainPaused) implements Command {}
    public record Halt(long operation, UUID expectedEpoch) implements Command {}

    /**
     * Persistence DTO. The selected segment/position is intent; boundSource may still be the old
     * segment while a replacement is pending. Preserve both rather than inferring a segment from
     * time == duration on reload. operationRevision must survive saves to reject pre-save replies.
     * Version 2 additionally preserves the transition target, unconsumed time, and presentation
     * gate. Halt acknowledgement and elapsed-time baselines are deliberately not persisted.
     */
    public record Saved(String snapshotId, UUID owner, String scheduledShowId, int segmentIndex,
                        long localTimeMs, long globalTimeMs, boolean desiredPaused, UUID epoch,
                        String boundSource, int boundSourceType, boolean ownsSession,
                        long operationRevision, Status status, String failure, int version,
                        int transitionTargetIndex, long remainingGapMs, boolean presentationBlack) {
        /** Saves written before transitions existed resolve only a gapless legacy snapshot. */
        public Saved(String snapshotId, UUID owner, String scheduledShowId, int segmentIndex,
                     long localTimeMs, long globalTimeMs, boolean desiredPaused, UUID epoch,
                     String boundSource, int boundSourceType, boolean ownsSession,
                     long operationRevision, Status status, String failure) {
            this(snapshotId, owner, scheduledShowId, segmentIndex, localTimeMs, globalTimeMs,
                    desiredPaused, epoch, boundSource, boundSourceType, ownsSession,
                    operationRevision, status, failure, 1, -1, 0, false);
        }
    }

    private final FilmCatalog.Snapshot snapshot;
    private final UUID owner;
    private final String scheduledShowId;
    private final long timeoutTicks;
    private int segmentIndex;
    private long localTimeMs;
    private boolean desiredPaused;
    private UUID epoch;
    private String boundSource;
    private int boundSourceType;
    private boolean ownsSession;
    private long operationRevision;
    private Status status = Status.PREPARING;
    private String failure = "";
    private Command pending;
    private boolean restoring;
    private boolean restoredStopBound;
    private long waitStartedTick;
    private boolean awaitingInitialPosition;
    private int transitionTargetIndex = -1;
    private long remainingGapMs;
    private boolean presentationBlack;
    // Never persisted: even a previously acknowledged Halt needs fresh lease/Stop proof on reload.
    private boolean gapHaltAcknowledged;

    private FilmProgramState(FilmCatalog.Snapshot snapshot, UUID owner, String scheduledShowId,
                             long nowTick, long timeoutTicks) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (scheduledShowId != null && (scheduledShowId.isBlank() || scheduledShowId.length() > 128))
            throw invalid("show_id_invalid");
        if (nowTick < 0 || timeoutTicks <= 0 || timeoutTicks > 72_000) throw invalid("timeout_invalid");
        this.scheduledShowId = scheduledShowId;
        this.timeoutTicks = timeoutTicks;
        this.waitStartedTick = nowTick;
        // Snapshot already rejects unknown/live/evidence-less durations. Recheck the capability
        // keys explicitly, rather than permitting a later prepare to manufacture trusted metadata.
        for (FilmCatalog.Segment segment : snapshot.film().segments()) {
            if (segment.durationMs().isEmpty() || segment.live() || segment.observation() == null
                    || segment.observation().provenance().evidenceId() == null)
                throw invalid("unverified_duration");
            UUID.fromString(segment.observation().provenance().evidenceId());
        }
    }

    public static FilmProgramState start(FilmCatalog.Snapshot snapshot, UUID owner,
                                         String scheduledShowId, Session before,
                                         long nowTick, long timeoutTicks) {
        FilmProgramState state = new FilmProgramState(snapshot, owner, scheduledShowId, nowTick, timeoutTicks);
        if (before == null) {
            state.fail("source_unavailable", null);
        } else {
            state.bind(before, false);
            state.prepare(nowTick);
        }
        return state;
    }

    /**
     * Active saves restore without an executable command. The adapter must first prove that the
     * current source still belongs to the saved controller lease, then call rebindRestored.
     * Final states stay final and never generate a prepare, halt, seek, or automatic replay.
     */
    public static FilmProgramState restore(FilmCatalog.Snapshot snapshot, Saved saved,
                                           long nowTick, long timeoutTicks) {
        Objects.requireNonNull(saved, "saved");
        if (!snapshot.id().equals(saved.snapshotId())) throw invalid("snapshot_mismatch");
        if (saved.version() < 0 || saved.version() > 2) throw invalid("saved_version_invalid");
        if (saved.version() < 2 && snapshot.transitionGapMs() != 0) throw invalid("saved_policy_mismatch");
        FilmProgramState state = new FilmProgramState(snapshot, saved.owner(), saved.scheduledShowId(), nowTick, timeoutTicks);
        if (saved.segmentIndex() < 0 || saved.segmentIndex() >= snapshot.film().segments().size()
                || saved.localTimeMs() < 0
                || saved.localTimeMs() > snapshot.film().segments().get(saved.segmentIndex()).durationMs().orElseThrow()
                || saved.operationRevision() < 0 || saved.operationRevision() == Long.MAX_VALUE
                || saved.status() == null || saved.failure() == null || saved.failure().length() > 160
                || saved.epoch() == null && saved.ownsSession()
                || saved.boundSource() == null && saved.epoch() != null
                || saved.boundSource() != null && saved.boundSource().length() > FilmCatalog.MAX_SOURCE_LENGTH
                || saved.ownsSession() && (saved.boundSourceType() != 0
                    || snapshot.film().segments().stream().noneMatch(s -> s.sourceKey().equals(saved.boundSource())))
                || saved.status() == Status.COMPLETED && (saved.segmentIndex() != snapshot.film().segments().size() - 1
                    || saved.localTimeMs() != snapshot.film().segments().get(saved.segmentIndex()).durationMs().orElseThrow()))
            throw invalid("saved_state_invalid");
        // Old serializers supply zero for newly appended primitive fields. Ignore those fields
        // for v0/v1, but never allow them to manufacture a GAP in an old gapless plan.
        if (saved.version() < 2 && saved.status() == Status.GAP) throw invalid("saved_state_invalid");
        if (saved.version() == 2) {
            int target = saved.transitionTargetIndex();
            if (target < -1 || target >= snapshot.film().segments().size()
                    || saved.remainingGapMs() < 0 || saved.remainingGapMs() > snapshot.transitionGapMs()
                    || target == -1 && saved.remainingGapMs() != 0
                    || target >= 0 && (snapshot.transitionGapMs() == 0 || !saved.presentationBlack())
                    || saved.status() == Status.GAP && (target < 0 || !saved.ownsSession())
                    || target >= 0 && saved.status() != Status.GAP && !isFinal(saved.status())
                    || saved.status() == Status.COMPLETED && (target >= 0 || saved.presentationBlack())
                    || saved.presentationBlack() && snapshot.transitionGapMs() == 0
                    || saved.presentationBlack() && (saved.status() == Status.PLAYING || saved.status() == Status.PAUSED))
                throw invalid("saved_gap_invalid");
            state.transitionTargetIndex = target;
            state.remainingGapMs = saved.remainingGapMs();
            state.presentationBlack = saved.presentationBlack();
        }
        state.segmentIndex = saved.segmentIndex();
        state.localTimeMs = saved.localTimeMs();
        if (state.globalTimeMs() != saved.globalTimeMs()) throw invalid("saved_position_invalid");
        state.desiredPaused = saved.desiredPaused();
        state.epoch = saved.epoch();
        state.boundSource = saved.boundSource();
        state.boundSourceType = saved.boundSourceType();
        state.ownsSession = saved.ownsSession();
        state.operationRevision = saved.operationRevision() + 1; // invalidate every pre-save command
        state.failure = saved.failure();
        state.status = saved.status();
        if (!state.finished()) {
            if (state.status != Status.GAP) state.status = Status.PREPARING;
            state.failure = "";
            state.restoring = true;
            state.awaitingInitialPosition = state.status != Status.GAP;
        }
        return state;
    }

    /**
     * Explicit adapter authorization hook, ONLY after WF controlledBy proves the same saved
     * controller lease and source. An epoch difference by itself is never proof of a restart.
     * Media positions are replaced paused to obtain a fresh Ready. A restored GAP instead
     * reasserts Stop on the old source and continues its remaining time only after acknowledgement.
     */
    public void rebindRestored(Session verifiedCurrent, long nowTick) {
        if (!restoring || finished()) throw invalid("restore_not_pending");
        requireTick(nowTick);
        if (!ownsSession || verifiedCurrent == null || !Objects.equals(boundSource, verifiedCurrent.source())
                || boundSourceType != verifiedCurrent.sourceType()) {
            overridden();
            return;
        }
        bind(verifiedCurrent, true);
        restoring = false;
        if (status == Status.GAP) {
            gapHaltAcknowledged = false;
            waitStartedTick = nowTick;
            pending = new Halt(nextOperation(), epoch);
        } else prepare(nowTick);
    }

    /**
     * Stop a restored lease without preparing it. The adapter must recheck current authority
     * before every call. Bind the cold epoch only once, and wait within the original restore
     * deadline for its paused clock; neither WAITING nor absent Ready acknowledges Stop.
     */
    public void stopRestored(Session verifiedCurrent, long nowTick) {
        if (!restoring || finished()) throw invalid("restore_not_pending");
        requireTick(nowTick);
        if (!ownsSession || verifiedCurrent == null || !Objects.equals(boundSource, verifiedCurrent.source())
                || boundSourceType != verifiedCurrent.sourceType()) {
            overridden();
            return;
        }
        if (!restoredStopBound) {
            bind(verifiedCurrent, true);
            restoredStopBound = true;
        } else if (!matchesBound(verifiedCurrent)) {
            overridden();
            return;
        }
        if (timedOut(nowTick)) {
            fail("restore_required_timeout", null);
            return;
        }
        if (verifiedCurrent.playback() == Playback.WAITING && verifiedCurrent.revision() == -1
                && verifiedCurrent.pausedIntent() && verifiedCurrent.failure().isEmpty()) return;
        stop(verifiedCurrent);
    }

    /** Null means the model currently needs no service operation. */
    public Command pending() { return pending; }

    /** Replies with an old operation token are harmless, including replies received after reload. */
    public void acknowledge(long operation, Session result, String error, long nowTick) {
        requireTick(nowTick);
        if (pending == null || operation != pending.operation()) return;
        Command completed = pending;
        pending = null;
        if (completed instanceof Halt) {
            // Final-state halt acknowledgements cannot reset the saved position or resurrect a run.
            if (result != null && !matchesBound(result)) overridden();
            else if (status == Status.GAP && ownsSession && "clock_initializing".equals(error)
                    && result != null && result.playback() == Playback.WAITING && result.revision() == -1
                    && result.pausedIntent() && result.failure().isEmpty()) {
                // WF explicitly reports that the exact leased clock has not opened yet. Keep
                // the same Halt and original deadline; WAITING is never a successful Stop ACK.
                // Every adapter retry must pass the current owner/controller/epoch guards again.
                if (timedOut(nowTick)) haltRejected("timeout");
                else pending = completed;
            }
            else if (error != null && !error.isEmpty()) haltRejected(error);
            else if (result == null) haltRejected("unavailable");
            else if (result.playback() != Playback.STOPPED) haltRejected("not_stopped");
            else if (status == Status.GAP) gapHaltAcknowledged = true;
            return;
        }
        if (error != null && !error.isEmpty()) {
            if (result != null && !matchesBound(result)) overridden();
            else fail(sourceFailure(error), result);
            return;
        }
        if (result == null) {
            fail("source_unavailable", null);
            return;
        }
        if (completed instanceof Prepare prepare) {
            // A successful prepare must create a NEW epoch, even for consecutive duplicate URLs.
            if (prepare.expectedEpoch().equals(result.epoch())) {
                if (!matchesBound(result)) overridden();
                else fail("prepare_epoch_unchanged", result);
                return;
            }
            if (!prepare.source().equals(result.source()) || result.sourceType() != 0) {
                overridden();
                return;
            }
            bind(result, true);
            status = Status.PREPARING;
            awaitingInitialPosition = true;
            inspectPreparation(result, nowTick);
            return;
        }
        Resume resume = (Resume) completed;
        if (!matchesBound(result)) {
            overridden();
            return;
        }
        if (!verified(result)) return;
        if (!result.ready()) {
            fail("ready_lost", result);
            return;
        }
        // Never take a delayed report's time as the seek target: this acknowledgement confirms the
        // exact requested position. Future authoritative observations provide advancing time.
        localTimeMs = resume.localMs();
        awaitingInitialPosition = false;
        status = resume.remainPaused() ? Status.PAUSED : Status.PLAYING;
        if (desiredPaused != resume.remainPaused()) issueResume(result, nowTick);
        else presentationBlack = false; // Only the matching final Resume may reveal the new clip.
    }

    /** Poll only current service state. An epoch/source mismatch is takeover, never auto-rebind. */
    public void observe(Session current, long nowTick) {
        requireTick(nowTick);
        if (finished()) return;
        if (current == null) {
            fail("source_unavailable", null);
            return;
        }
        if (!matchesBound(current)) {
            overridden();
            return;
        }
        if (restoring) {
            if (timedOut(nowTick)) fail("restore_required_timeout", null);
            return;
        }
        if (status == Status.GAP) {
            inspectGap(current, nowTick);
            return;
        }
        if (pending instanceof Prepare) {
            if (timedOut(nowTick)) fail("prepare_timeout", current);
            return;
        }
        if (pending instanceof Resume) {
            if (!current.failure().isEmpty()) fail(sourceFailure(current.failure()), current);
            else if (timedOut(nowTick)) fail("resume_timeout", current);
            return;
        }
        if (status == Status.PREPARING || status == Status.READY) {
            inspectPreparation(current, nowTick);
            return;
        }
        if (!verified(current)) return;
        capture(current);
        if (current.playback() == Playback.STOPPED) {
            status = Status.STOPPED;
            desiredPaused = true;
            failure = "source_stopped";
            return;
        }
        // Once preparation/our own command is settled, same-session TMS intent is authoritative.
        // A viewer-Ready hold pauses the clock without changing the user's play intent.
        desiredPaused = current.pausedIntent();
        if (current.playback() == Playback.ENDED && !desiredPaused) {
            localTimeMs = durationMs();
            if (segmentIndex == snapshot.film().segments().size() - 1) {
                status = Status.COMPLETED;
            } else if (snapshot.transitionGapMs() > 0) {
                beginGap(segmentIndex + 1, nowTick);
            } else {
                segmentIndex++;
                localTimeMs = 0;
                prepare(nowTick);
            }
            return;
        }
        // Reaching a timestamp alone is never an Ended event, particularly after save/reload.
        status = !desiredPaused && current.ready() && current.playback() == Playback.PLAYING
                ? Status.PLAYING : Status.PAUSED;
    }

    /** Legacy internal absolute seek. New programs expose only current-clip seeking to the UI. */
    public void seek(long globalMs, Session current, long nowTick) {
        requireActive();
        requireTick(nowTick);
        if (snapshot.transitionGapMs() > 0) throw invalid("global_seek_disabled");
        FilmCatalog.SeekTarget target = snapshot.seek(globalMs);
        if (!checkCurrent(current)) return;
        int previous = segmentIndex;
        segmentIndex = target.segmentIndex();
        localTimeMs = target.localTimeMs();
        if (restoring) return; // rebind will prepare the newly selected target, still paused initially
        if (previous != segmentIndex) {
            prepare(nowTick);
        } else if (pending instanceof Prepare || status == Status.PREPARING) {
            awaitingInitialPosition = true;
        } else {
            if (!verified(current)) return;
            if (!current.ready()) { fail("ready_lost", current); return; }
            issueResume(current, nowTick);
        }
    }

    /** Seek never changes the selected clip, including at its exact end. */
    public void seekCurrent(long localMs, Session current, long nowTick) {
        requireActive();
        requireTick(nowTick);
        if (status == Status.GAP) throw invalid("seek_during_gap");
        if (!checkCurrent(current)) return;
        localTimeMs = Math.max(0, Math.min(durationMs(), localMs));
        // Dragging to the clip's end is a paused position, not a request to select another clip.
        // A later explicit Play may resume that position and then receive a natural Ended event.
        if (localTimeMs == durationMs()) desiredPaused = true;
        if (restoring) { nextOperation(); return; }
        if (pending instanceof Prepare || status == Status.PREPARING) {
            awaitingInitialPosition = true;
            nextOperation();
        } else {
            if (!verified(current)) return;
            if (!current.ready()) { fail("ready_lost", current); return; }
            issueResume(current, nowTick);
        }
    }

    /** Selection is by list index, so repeated A/B/A sources remain distinct clips. */
    public void selectSegment(int index, Session current, long nowTick) {
        requireActive();
        requireTick(nowTick);
        if (index < 0 || index >= snapshot.film().segments().size()) throw invalid("segment_index_invalid");
        if (!checkCurrent(current)) return;
        if (restoring) throw invalid("restore_not_confirmed");
        if (index == segmentIndex && status != Status.GAP) {
            seekCurrent(0, current, nowTick);
            return;
        }
        if (snapshot.transitionGapMs() > 0) {
            if (!ownsSession) throw invalid("selection_not_ready");
            if (status != Status.GAP && !awaitingInitialPosition && !(pending instanceof Prepare)) capture(current);
            beginGap(index, nowTick);
        } else {
            segmentIndex = index;
            localTimeMs = 0;
            prepare(nowTick);
        }
    }

    public void next(Session current, long nowTick) {
        selectSegment((status == Status.GAP ? transitionTargetIndex : segmentIndex) + 1, current, nowTick);
    }

    public void previous(Session current, long nowTick) {
        selectSegment((status == Status.GAP ? transitionTargetIndex : segmentIndex) - 1, current, nowTick);
    }

    /**
     * Consume only the adapter's explicitly measured, continuously loaded elapsed milliseconds.
     * A freshly restored state cannot burn offline time: rebind and a matching Halt must occur
     * first, and the adapter establishes a fresh monotonic baseline when it observes that proof.
     */
    public void advanceGap(Session current, long elapsedMs, long nowTick) {
        requireTick(nowTick);
        if (elapsedMs < 0) throw invalid("elapsed_invalid");
        if (status != Status.GAP || finished()) return;
        if (!checkCurrent(current)) return;
        if (restoring) {
            if (timedOut(nowTick)) fail("restore_required_timeout", null);
            return;
        }
        if (!inspectGap(current, nowTick) || desiredPaused || !gapHaltAcknowledged) return;
        remainingGapMs -= Math.min(remainingGapMs, elapsedMs);
        if (remainingGapMs == 0) {
            segmentIndex = transitionTargetIndex;
            transitionTargetIndex = -1;
            localTimeMs = 0;
            gapHaltAcknowledged = false;
            prepare(nowTick);
        }
    }

    /** Pausing preparation is sticky; it cannot accidentally be undone by a later Ready callback. */
    public void pause(boolean paused, Session current, long nowTick) {
        requireActive();
        requireTick(nowTick);
        if (!checkCurrent(current)) return;
        desiredPaused = paused;
        if (status == Status.GAP) { nextOperation(); return; }
        if (restoring || pending instanceof Prepare || status == Status.PREPARING) { nextOperation(); return; }
        if (!verified(current)) return;
        if (!current.ready()) { fail("ready_lost", current); return; }
        if (pending instanceof Resume resume) localTimeMs = resume.localMs();
        else capture(current);
        issueResume(current, nowTick);
    }

    /** Explicit Stop is final, preserves the reported position, and never advances to another clip. */
    public void stop(Session current) {
        if (finished()) return;
        if (!checkCurrent(current)) return;
        if (status != Status.GAP && !awaitingInitialPosition && !(pending instanceof Prepare) && !restoring) capture(current);
        pending = null;
        status = Status.STOPPED;
        desiredPaused = true;
        restoring = false;
        gapHaltAcknowledged = false;
        if (ownsSession) pending = new Halt(nextOperation(), epoch);
    }

    /**
     * Fail a CURRENT adapter authorization/availability check without inventing an acknowledgement.
     * Supply only an authoritative current session, or null when it cannot be read. A matching
     * owned session permits one cleanup Halt; rejecting that Halt records an unconfirmed stop and
     * does not queue another. Ownership is retained for inspection, never inferred from absence.
     * This is not an asynchronous response hook: delayed replies must use acknowledge's token.
     */
    public void reject(String reason, Session current) {
        if (finished() && !(pending instanceof Halt)) return;
        if (current != null && !matchesBound(current)) {
            overridden();
        } else if (pending instanceof Halt) {
            haltRejected(reason);
        } else {
            fail(sourceFailure(reason), current);
        }
    }

    public Saved save() {
        return new Saved(snapshot.id(), owner, scheduledShowId, segmentIndex, localTimeMs,
                globalTimeMs(), desiredPaused, epoch, boundSource, boundSourceType, ownsSession,
                operationRevision, status, failure, 2, transitionTargetIndex, remainingGapMs, presentationBlack);
    }

    public FilmCatalog.Snapshot snapshot() { return snapshot; }
    public UUID owner() { return owner; }
    public String scheduledShowId() { return scheduledShowId; }
    public int segmentIndex() { return segmentIndex; }
    public long localTimeMs() { return localTimeMs; }
    public long globalTimeMs() {
        long position = transitionTargetIndex >= 0
                ? snapshot.segmentStartMs(transitionTargetIndex) - remainingGapMs
                : snapshot.segmentStartMs(segmentIndex) + localTimeMs;
        return Math.max(0, Math.min(snapshot.totalDurationMs(), position));
    }
    public int transitionTargetIndex() { return transitionTargetIndex; }
    public long remainingGapMs() { return remainingGapMs; }
    public boolean presentationBlack() { return presentationBlack; }
    public boolean gapHaltAcknowledged() { return gapHaltAcknowledged; }
    public boolean desiredPaused() { return desiredPaused; }
    public UUID epoch() { return epoch; }
    public String boundSource() { return boundSource; }
    public int boundSourceType() { return boundSourceType; }
    public boolean ownsSession() { return ownsSession; }
    public long operationRevision() { return operationRevision; }
    public Status status() { return status; }
    public String failure() { return failure; }
    public boolean restoring() { return restoring; }
    /** Model-only eligibility; the adapter must still verify the retained display scope and live source. */
    public boolean completedDisplayCandidate() {
        return status == Status.COMPLETED && failure.isEmpty() && ownsSession && !restoring && pending == null;
    }
    public boolean finished() {
        return isFinal(status);
    }

    private static boolean isFinal(Status status) {
        return status == Status.FAILED || status == Status.COMPLETED || status == Status.STOPPED || status == Status.OVERRIDDEN;
    }

    private void beginGap(int targetIndex, long nowTick) {
        transitionTargetIndex = targetIndex;
        remainingGapMs = snapshot.transitionGapMs();
        presentationBlack = true;
        gapHaltAcknowledged = false;
        awaitingInitialPosition = false;
        waitStartedTick = nowTick;
        status = Status.GAP;
        failure = "";
        pending = new Halt(nextOperation(), epoch);
    }

    private boolean inspectGap(Session current, long nowTick) {
        if (!current.failure().isEmpty()) { fail(sourceFailure(current.failure()), current); return false; }
        if (pending instanceof Halt) {
            if (timedOut(nowTick)) haltRejected("timeout");
            return false;
        }
        if (!gapHaltAcknowledged || current.playback() != Playback.STOPPED) {
            fail("gap_stop_lost", current);
            return false;
        }
        return true;
    }

    private void prepare(long nowTick) {
        FilmCatalog.Segment segment = snapshot.film().segments().get(segmentIndex);
        status = Status.PREPARING;
        failure = "";
        waitStartedTick = nowTick;
        awaitingInitialPosition = true;
        pending = new Prepare(nextOperation(), epoch, segmentIndex, segment.sourceKey(),
                UUID.fromString(segment.observation().provenance().evidenceId()));
    }

    private void inspectPreparation(Session current, long nowTick) {
        if (!current.failure().isEmpty()) { fail(sourceFailure(current.failure()), current); return; }
        if (current.live()) { fail("live_unschedulable", current); return; }
        if (current.playback() == Playback.PLAYING) { fail("prepare_not_paused", current); return; }
        if (!current.metadataReady()) {
            if (timedOut(nowTick)) fail("metadata_timeout", current);
            return;
        }
        if (!current.ready()) {
            if (timedOut(nowTick)) fail("awaiting_viewer_timeout", current);
            return;
        }
        // WaterFrames exposes revision -1 while its exact display clock is still
        // initializing. Decoder Ready is the boundary after which a revision may
        // authorize Resume; before then this is a wait state, not corrupt proof.
        if (!verified(current)) return;
        issueResume(current, nowTick);
    }

    /** Returns false after making a terminal transition; exact duration/proof are never relaxed. */
    private boolean verified(Session current) {
        String reason = null;
        if (!current.failure().isEmpty()) reason = sourceFailure(current.failure());
        else if (current.live()) reason = "live_unschedulable";
        else if (!current.metadataReady() || current.durationMs() <= 0) reason = "unverified_duration";
        else if (current.durationMs() != durationMs()) reason = "duration_mismatch";
        else if (current.evidenceId() == null || !evidenceId().equals(current.evidenceId())) reason = "evidence_unavailable";
        // WaterFrames revisions are opaque signed ints; only its documented -1
        // sentinel means the clock has not opened. Other negative values are
        // valid compare-and-set tokens and must be preserved verbatim.
        else if (current.revision() == -1) reason = "revision_unavailable";
        if (reason != null) { fail(reason, current); return false; }
        return true;
    }

    private void issueResume(Session current, long nowTick) {
        status = Status.READY;
        waitStartedTick = nowTick;
        pending = new Resume(nextOperation(), epoch, current.revision(), localTimeMs, desiredPaused);
    }

    private boolean checkCurrent(Session current) {
        if (current == null) { fail("source_unavailable", null); return false; }
        if (!matchesBound(current)) { overridden(); return false; }
        return true;
    }

    private boolean matchesBound(Session current) {
        return Objects.equals(epoch, current.epoch()) && Objects.equals(boundSource, current.source())
                && boundSourceType == current.sourceType();
    }

    private void bind(Session session, boolean owned) {
        epoch = session.epoch();
        boundSource = session.source();
        boundSourceType = session.sourceType();
        ownsSession = owned;
    }

    private void capture(Session current) { localTimeMs = Math.max(0, Math.min(durationMs(), current.timeMs())); }
    private long durationMs() { return snapshot.film().segments().get(segmentIndex).durationMs().orElseThrow(); }
    private UUID evidenceId() { return UUID.fromString(snapshot.film().segments().get(segmentIndex).observation().provenance().evidenceId()); }
    private boolean timedOut(long nowTick) { return nowTick < waitStartedTick || nowTick - waitStartedTick >= timeoutTicks; }

    private void fail(String key, Session current) {
        pending = null;
        status = Status.FAILED;
        failure = key;
        restoring = false;
        gapHaltAcknowledged = false;
        // The adapter must still check this exact epoch when executing Halt. Never stop an
        // unowned initial source, or infer that a missing/new session belongs to this program.
        if (ownsSession && current != null && matchesBound(current)) pending = new Halt(nextOperation(), epoch);
    }

    private void haltRejected(String reason) {
        pending = null;
        status = Status.FAILED;
        failure = "stop_unconfirmed_" + sourceFailure(reason);
        restoring = false;
        gapHaltAcknowledged = false;
    }

    private void overridden() {
        pending = null;
        status = Status.OVERRIDDEN;
        failure = "external_takeover";
        ownsSession = false;
        restoring = false;
        gapHaltAcknowledged = false;
    }

    private long nextOperation() {
        if (operationRevision == Long.MAX_VALUE) throw invalid("operation_exhausted");
        return ++operationRevision;
    }
    private void requireActive() { if (finished()) throw invalid("program_finished"); }
    private static void requireTick(long nowTick) { if (nowTick < 0) throw invalid("tick_invalid"); }
    private static IllegalArgumentException invalid(String key) { return new IllegalArgumentException("film_program." + key); }
    private static String sourceFailure(String key) {
        return "source_" + (key != null && key.length() <= 120 && key.matches("[a-z_]{1,120}") ? key : "unavailable");
    }
}
