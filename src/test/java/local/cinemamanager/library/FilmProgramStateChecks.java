package local.cinemamanager.library;

import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.library.FilmProgramState.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure contract checks: no Minecraft bootstrap, decoder, files, clocks, network, or GUI. */
public final class FilmProgramStateChecks {
    private static int checks;
    private static final UUID OWNER = id(1);
    private static final long TIMEOUT = 20;
    private static final String A = "water://a", B = "water://b";

    public static void main(String[] args) {
        sourceBoundReady();
        orderedAndRepeatedSegments();
        delayedReadyPreservesFullRuntime();
        naturalEndAndExplicitStop();
        completedDisplayEligibility();
        outputFailureCannotAcknowledgeStop();
        externalTransport();
        viewerHoldPreservesIntent();
        globalSeeks();
        staleOperationsAndTakeover();
        failuresAndTimeouts();
        adapterRejections();
        saveAndRestore();
        finalStateRestore();
        invalidPersistence();
        gapBoundariesAndOrder();
        gapSelectionAndCurrentSeek();
        gapPauseStopAndRejection();
        gapSaveAndRestore();
        gapColdClockOrdering();
        restoredStopColdClockOrdering();
        gapInvalidPersistenceAndLegacy();
        System.out.println("PASS: " + checks + " film program checks; source-bound Ready, repeated ordered segments, "
                + "single advance, legacy seek, current-clip seek, 3000ms transitions, pause/stop, epochs, "
                + "failures/timeouts, persistence and explicit restore lease.");
    }

    private static void gapBoundariesAndOrder() {
        FilmProgramState p = start(gapSnapshot(A, B, A), 40_000);
        check(p.snapshot().mediaDurationMs() == 4000 && p.snapshot().totalDurationMs() == 10_000,
                "A/B/A total includes both exact three-second transitions");
        check(!p.presentationBlack(), "initial preparation is not a between-clip transition");
        playing(p, 40_001, 1);
        Session ended = view(p, 40_001, 1, Playback.ENDED, 1000, true, true);
        p.observe(ended, 3);
        Halt halt = halt(p);
        check(p.status() == Status.GAP && p.segmentIndex() == 0 && p.transitionTargetIndex() == 1
                && p.remainingGapMs() == 3000 && p.globalTimeMs() == 1000 && p.presentationBlack(),
                "natural Ended retains outgoing clip and begins full black transition");
        p.observe(ended, 4);
        p.advanceGap(ended, 9000, 4);
        check(p.pending().equals(halt) && p.remainingGapMs() == 3000 && !p.gapHaltAcknowledged(),
                "repeated Ended and elapsed time cannot skip unacknowledged Stop");
        Session stopped = view(p, 40_001, 2, Playback.STOPPED, 0, true, false);
        p.acknowledge(halt.operation() - 1, stopped, null, 4);
        check(!p.gapHaltAcknowledged() && p.pending().equals(halt), "stale Halt cannot start transition clock");
        p.acknowledge(halt.operation(), stopped, null, 5);
        check(p.gapHaltAcknowledged() && p.pending() == null, "matching stopped epoch unlocks countdown");
        p.advanceGap(stopped, 2999, 6);
        check(p.status() == Status.GAP && p.remainingGapMs() == 1 && p.globalTimeMs() == 3999
                && p.segmentIndex() == 0 && p.pending() == null, "2999ms is still outgoing clip and black");
        p.advanceGap(stopped, 0, 10_000);
        check(p.remainingGapMs() == 1, "tick age alone never consumes transition milliseconds");
        p.advanceGap(stopped, 1, 10_001);
        Prepare second = prepare(p);
        check(p.segmentIndex() == 1 && second.source().equals(B) && p.localTimeMs() == 0
                && p.globalTimeMs() == 4000 && p.presentationBlack() && p.transitionTargetIndex() == -1,
                "exact 3000ms prepares next clip paused while preserving black output");
        p.acknowledge(halt.operation(), stopped, null, 10_002);
        check(p.pending().equals(second) && p.presentationBlack(), "late Halt cannot reveal or skip preparing successor");
        p.acknowledge(second.operation(), view(p, 40_002, 0, Playback.PAUSED, 0, true, false), null, 10_003);
        check(p.status() == Status.PREPARING && p.presentationBlack(), "new epoch without viewer Ready remains black");
        p.observe(view(p, 40_002, 0, Playback.PAUSED, 0, true, true), 10_004);
        Resume resume = resume(p);
        check(p.presentationBlack() && resume.localMs() == 0, "Ready alone cannot reveal the new frame");
        p.acknowledge(resume.operation() - 1, view(p, 40_002, 1, Playback.PLAYING, 0, true, true), null, 10_005);
        check(p.presentationBlack() && p.pending().equals(resume), "stale Resume cannot release transition black");
        p.acknowledge(resume.operation(), view(p, 40_002, 1, Playback.PLAYING, 0, true, true), null, 10_006);
        check(p.status() == Status.PLAYING && !p.presentationBlack(), "matching Resume releases new clip");
        p.observe(view(p, 40_002, 1, Playback.ENDED, 2000, true, true), 10_007);
        check(p.status() == Status.GAP && p.transitionTargetIndex() == 2 && p.globalTimeMs() == 6000,
                "second natural end keeps last A as its own list item");
        stopped = acknowledgeGapHalt(p, 40_002, 10_008);
        p.advanceGap(stopped, 3000, 10_009);
        check(prepare(p).source().equals(A) && p.segmentIndex() == 2, "A/B/A order survives both transitions");
        playing(p, 40_003, 10_010);
        p.observe(view(p, 40_003, 1, Playback.ENDED, 1000, true, true), 10_012);
        check(p.status() == Status.COMPLETED && p.globalTimeMs() == 10_000 && p.pending() == null
                && !p.presentationBlack(), "last Ended completes without a trailing gap or replay");

        FilmProgramState duplicate = start(gapSnapshot(A, A), 40_100);
        playing(duplicate, 40_101, 1);
        duplicate.observe(view(duplicate, 40_101, 1, Playback.ENDED, 1000, true, true), 3);
        stopped = acknowledgeGapHalt(duplicate, 40_101, 4);
        duplicate.advanceGap(stopped, 3000, 5);
        check(prepare(duplicate).segmentIndex() == 1 && prepare(duplicate).source().equals(A),
                "adjacent identical sources also receive a full gap and separate prepare");
        playing(duplicate, 40_102, 6);
        check(duplicate.epoch().equals(id(40_102)), "duplicate clip requires fresh epoch");

        FilmProgramState single = start(gapSnapshot(A), 40_200);
        playing(single, 40_201, 1);
        single.observe(view(single, 40_201, 1, Playback.ENDED, 1000, true, true), 3);
        check(single.status() == Status.COMPLETED && single.pending() == null
                && single.snapshot().totalDurationMs() == 1000 && !single.presentationBlack(),
                "single-clip playlist contains no gap");
        FilmProgramState replay = start(p.snapshot(), 40_300);
        check(replay.segmentIndex() == 0 && replay.snapshot().film().segments().size() == 3
                && prepare(replay).source().equals(A), "fresh replay starts whole immutable list at index zero");
    }

    private static void gapSelectionAndCurrentSeek() {
        FilmProgramState p = start(gapSnapshot(A, B, A), 41_000);
        Prepare initial = prepare(p);
        Saved before = p.save();
        rejects(() -> p.selectSegment(1, initial(41_000), 0), "unowned selection rejects");
        check(p.save().equals(before) && p.pending().equals(initial) && !p.ownsSession(),
                "initial selection cannot create Halt capability over unowned source");
        playing(p, 41_001, 1);
        Session current = view(p, 41_001, 1, Playback.PLAYING, 450, true, true);
        rejects(() -> p.seek(4500, current, 3), "global seek cannot bypass current-clip contract");
        p.seekCurrent(-99, current, 3);
        Resume zero = resume(p);
        check(zero.localMs() == 0 && p.segmentIndex() == 0, "negative local seek clamps within current clip");
        p.acknowledge(zero.operation(), current, null, 4);
        p.seekCurrent(99999, current, 5);
        Resume end = resume(p);
        check(end.localMs() == 1000 && end.remainPaused() && p.segmentIndex() == 0 && !p.presentationBlack(),
                "out-of-range seek clamps and pauses at current clip end without transition");
        p.acknowledge(end.operation(), view(p, 41_001, 2, end.remainPaused() ? Playback.PAUSED : Playback.PLAYING,
                1000, true, true), null, 6);
        check(p.segmentIndex() == 0 && p.pending() == null, "seek acknowledgement never selects next clip");
        p.observe(view(p, 41_001, 2, Playback.ENDED, 1000, true, true, true), 6);
        check(p.segmentIndex() == 0 && p.status() == Status.PAUSED && p.pending() == null,
                "Ended at a seek-paused end position cannot advance clip");
        p.selectSegment(0, view(p, 41_001, 2, Playback.PAUSED, 1000, true, true), 7);
        check(resume(p).localMs() == 0 && !p.presentationBlack(), "selecting current item restarts without gap");
        Resume restart = resume(p);
        p.acknowledge(restart.operation(), view(p, 41_001, 3, Playback.PAUSED, 0, true, true), null, 8);
        p.selectSegment(2, view(p, 41_001, 3, Playback.PAUSED, 0, true, true), 9);
        Halt lastTarget = halt(p);
        check(p.segmentIndex() == 0 && p.transitionTargetIndex() == 2 && p.remainingGapMs() == 3000,
                "explicit last-item selection addresses repeated A by index");
        p.previous(view(p, 41_001, 3, Playback.STOPPED, 0, true, true), 10);
        check(p.transitionTargetIndex() == 1 && p.remainingGapMs() == 3000
                && halt(p).operation() > lastTarget.operation(), "previous during transition retargets one item back and invalidates old Halt");
        Session stopped = view(p, 41_001, 4, Playback.STOPPED, 0, true, false);
        p.acknowledge(lastTarget.operation(), stopped, null, 11);
        check(!p.gapHaltAcknowledged(), "old selection Halt cannot unlock newer transition");
        p.next(stopped, 11);
        check(p.transitionTargetIndex() == 2, "next during transition follows intended target");
        rejects(() -> p.next(stopped, 12), "next at final item rejects without wrapping");
        p.selectSegment(0, stopped, 12);
        check(p.transitionTargetIndex() == 0 && p.globalTimeMs() == 0 && p.remainingGapMs() == 3000,
                "explicit return to outgoing clip still uses gap and bounded global projection");
        rejects(() -> p.seekCurrent(500, stopped, 12), "seek during gap is explicitly rejected");
        rejects(() -> p.previous(stopped, 12), "previous at first item cannot wrap");
        p.pause(false, stopped, 12);
        acknowledgeGapHalt(p, 41_001, 13);
        p.advanceGap(stopped, 3000, 14);
        check(prepare(p).segmentIndex() == 0 && p.globalTimeMs() == 0, "backward gap prepares chosen first clip at zero");

        for (FilmCatalog.Snapshot snapshot : List.of(snapshot(A, B), gapSnapshot(A, B))) {
            FilmProgramState seekEnd = start(snapshot, 41_100);
            playing(seekEnd, 41_101, 1);
            Session playing = view(seekEnd, 41_101, 1, Playback.PLAYING, 500, true, true);
            seekEnd.seekCurrent(1000, playing, 3);
            Resume atEnd = resume(seekEnd);
            check(atEnd.remainPaused(), "current-clip end seek pauses for both legacy and new snapshots");
            Session pausedEnd = view(seekEnd, 41_101, 2, Playback.ENDED, 1000, true, true, true);
            seekEnd.acknowledge(atEnd.operation(), pausedEnd, null, 4);
            seekEnd.observe(pausedEnd, 5);
            check(seekEnd.segmentIndex() == 0 && seekEnd.status() == Status.PAUSED && seekEnd.pending() == null,
                    "reported Ended after local end seek preserves current clip");
            seekEnd.pause(false, pausedEnd, 6);
            Resume explicitPlay = resume(seekEnd);
            seekEnd.acknowledge(explicitPlay.operation(), view(seekEnd, 41_101, 3, Playback.PLAYING, 1000, true, true), null, 7);
            seekEnd.observe(view(seekEnd, 41_101, 3, Playback.ENDED, 1000, true, true), 8);
            check(snapshot.transitionGapMs() == 0 ? seekEnd.status() == Status.PREPARING && seekEnd.segmentIndex() == 1
                    : seekEnd.status() == Status.GAP && seekEnd.transitionTargetIndex() == 1,
                    "explicit Play from clip end restores natural advancement according to immutable gap policy");
        }
    }

    private static void gapPauseStopAndRejection() {
        FilmProgramState p = naturalGap(42_000);
        Halt firstHalt = halt(p);
        Session stopped = view(p, 42_001, 2, Playback.STOPPED, 0, true, false);
        long revision = p.operationRevision();
        p.pause(true, stopped, 4);
        check(p.operationRevision() > revision && p.desiredPaused() && p.pending().equals(firstHalt),
                "pause changes UI revision without replacing exact pending Halt");
        p.acknowledge(firstHalt.operation(), stopped, null, 5);
        p.advanceGap(stopped, 100_000, 1000);
        check(p.remainingGapMs() == 3000 && p.status() == Status.GAP && p.pending() == null,
                "paused gap freezes even across large loaded elapsed interval");
        revision = p.operationRevision();
        p.pause(false, stopped, 1001);
        check(p.operationRevision() > revision && p.pending() == null, "gap Play changes intent without resuming old clip");
        p.advanceGap(stopped, 1200, 1002);
        check(p.remainingGapMs() == 1800 && p.globalTimeMs() == 2200, "gap resumes from exact remaining duration");
        p.pause(true, stopped, 1003);
        p.advanceGap(stopped, 5000, 1004);
        check(p.remainingGapMs() == 1800, "second pause preserves partial transition");
        p.stop(stopped);
        long stopPosition = p.globalTimeMs();
        Halt stop = halt(p);
        p.acknowledge(stop.operation(), stopped, null, 1005);
        p.advanceGap(stopped, 100_000, 1006);
        p.observe(view(p, 42_001, 2, Playback.ENDED, 1000, true, true), 1007);
        check(p.status() == Status.STOPPED && p.globalTimeMs() == stopPosition
                && p.segmentIndex() == 0 && p.pending() == null, "Stop during gap cannot advance or reset saved progress");
        FilmProgramState stoppedRestore = FilmProgramState.restore(p.snapshot(), p.save(), 0, TIMEOUT);
        check(stoppedRestore.status() == Status.STOPPED && stoppedRestore.pending() == null
                && stoppedRestore.globalTimeMs() == stopPosition, "stopped transition restores as nonexecuting final state");

        FilmProgramState denied = naturalGap(42_100);
        denied.reject("permission", view(denied, 42_101, 1, Playback.ENDED, 1000, true, true));
        check(denied.status() == Status.FAILED && denied.failure().equals("stop_unconfirmed_source_permission")
                && denied.pending() == null && !denied.gapHaltAcknowledged(), "denied transition Halt is final and cannot loosen ownership checks");
        FilmProgramState timeout = naturalGap(42_200);
        timeout.observe(view(timeout, 42_201, 1, Playback.ENDED, 1000, true, true), 23);
        check(timeout.status() == Status.FAILED && timeout.failure().equals("stop_unconfirmed_source_timeout")
                && timeout.pending() == null, "unacknowledged transition Halt times out without retry loop");
        FilmProgramState takeover = naturalGap(42_300);
        takeover.advanceGap(view(takeover, 42_302, 1, Playback.STOPPED, 0, true, false), 3000, 4);
        check(takeover.status() == Status.OVERRIDDEN && !takeover.ownsSession() && takeover.pending() == null,
                "new external epoch during gap cannot be prepared or halted");
        FilmProgramState restarted = naturalGap(42_400);
        acknowledgeGapHalt(restarted, 42_401, 4);
        restarted.advanceGap(view(restarted, 42_401, 3, Playback.PLAYING, 10, true, true), 3000, 5);
        check(restarted.status() == Status.FAILED && restarted.failure().equals("gap_stop_lost")
                && restarted.pending() instanceof Halt, "old source unexpectedly playing cannot count as black silent gap");
        FilmProgramState preparing = naturalGap(42_500);
        stopped = acknowledgeGapHalt(preparing, 42_501, 4);
        preparing.advanceGap(stopped, 3000, 5);
        preparing.pause(true, stopped, 6);
        readyPrepared(preparing, 42_502, 7);
        check(resume(preparing).remainPaused() && preparing.presentationBlack(), "pause during successor preparation is sticky and black");
        Resume pausedResume = resume(preparing);
        preparing.acknowledge(pausedResume.operation(), view(preparing, 42_502, 1, Playback.PAUSED, 0, true, true), null, 8);
        check(preparing.status() == Status.PAUSED && !preparing.presentationBlack(), "acknowledged prepared paused clip can replace transition black");
    }

    private static void gapSaveAndRestore() {
        for (boolean paused : new boolean[]{false, true}) {
            FilmProgramState original = naturalGap(paused ? 43_100 : 43_000);
            long epoch = paused ? 43_101 : 43_001;
            Session stopped = acknowledgeGapHalt(original, epoch, 4);
            original.advanceGap(stopped, 1234, 5);
            if (paused) original.pause(true, stopped, 6);
            Saved saved = original.save();
            check(saved.version() == 2 && saved.transitionTargetIndex() == 1 && saved.remainingGapMs() == 1766
                    && saved.presentationBlack(), "v2 save retains exact transition intent and remaining duration");
            FilmProgramState loaded = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
            check(loaded.status() == Status.GAP && loaded.restoring() && loaded.pending() == null
                    && !loaded.gapHaltAcknowledged() && loaded.remainingGapMs() == 1766 && loaded.presentationBlack(),
                    "gap reload stays gated black without re-preparing ended outgoing clip");
            loaded.advanceGap(stopped, 100_000, 1);
            check(loaded.remainingGapMs() == 1766 && loaded.pending() == null, "offline elapsed cannot burn restored gap before lease proof");
            Session rebound = view(loaded, epoch + 10, 0, Playback.STOPPED, 0, true, false);
            loaded.rebindRestored(rebound, 2);
            Halt fresh = halt(loaded);
            check(fresh.expectedEpoch().equals(id(epoch + 10)) && loaded.segmentIndex() == 0
                    && loaded.transitionTargetIndex() == 1, "proved cold-reload lease reasserts Stop without outgoing prepare");
            loaded.advanceGap(rebound, 100_000, 3);
            loaded.acknowledge(saved.operationRevision(), rebound, null, 3);
            check(loaded.remainingGapMs() == 1766 && !loaded.gapHaltAcknowledged(), "elapsed and pre-save callbacks cannot bypass fresh Stop proof");
            loaded.acknowledge(fresh.operation(), rebound, null, 4);
            loaded.advanceGap(rebound, 0, 1000);
            check(loaded.remainingGapMs() == 1766, "fresh clock baseline does not charge unloaded time");
            if (paused) {
                loaded.advanceGap(rebound, 9000, 1001);
                check(loaded.remainingGapMs() == 1766, "paused restore retains remaining gap");
                loaded.pause(false, rebound, 1002);
            }
            loaded.advanceGap(rebound, 1765, 1003);
            check(loaded.status() == Status.GAP && loaded.remainingGapMs() == 1, "restored 1766ms gap honors final millisecond");
            loaded.advanceGap(rebound, 1, 1004);
            Prepare next = prepare(loaded);
            check(next.segmentIndex() == 1 && loaded.presentationBlack(), "restored transition finally prepares only intended successor");

            Saved preparingSave = loaded.save();
            FilmProgramState preparingReload = FilmProgramState.restore(original.snapshot(), preparingSave, 0, TIMEOUT);
            check(preparingReload.status() == Status.PREPARING && preparingReload.presentationBlack()
                    && preparingReload.segmentIndex() == 1 && preparingReload.boundSource().equals(A),
                    "pending successor save separates selected target from old bound source");
            preparingReload.rebindRestored(rebound, 1);
            check(prepare(preparingReload).source().equals(B), "successor cold reload never replays outgoing clip");
            readyPrepared(preparingReload, epoch + 11, 2);
            Saved readySave = preparingReload.save();
            FilmProgramState readyReload = FilmProgramState.restore(original.snapshot(), readySave, 0, TIMEOUT);
            check(readyReload.presentationBlack() && readyReload.pending() == null && readyReload.restoring(),
                    "save between Ready and Resume acknowledgement retains black gate");
            readyReload.rebindRestored(view(readyReload, epoch + 12, 0, Playback.PAUSED, 0, true, true), 1);
            playing(readyReload, epoch + 13, 2);
            check(!readyReload.presentationBlack() && readyReload.segmentIndex() == 1,
                    "fresh Ready and Resume after cold reload release intended successor only");
        }
        FilmProgramState unacknowledged = naturalGap(43_300);
        FilmProgramState restored = FilmProgramState.restore(unacknowledged.snapshot(), unacknowledged.save(), 0, TIMEOUT);
        check(restored.status() == Status.GAP && restored.remainingGapMs() == 3000 && restored.pending() == null,
                "save before first Halt acknowledgement also restores full gated gap");
        restored.rebindRestored(new Session(id(43_302), B, 0, 0, Playback.STOPPED, 0, 2000, false, true, evidence(B), false, ""), 1);
        check(restored.status() == Status.OVERRIDDEN && restored.pending() == null, "restore cannot assume lease from different source");
    }

    private static void gapColdClockOrdering() {
        FilmProgramState original = start(gapSnapshot(A, B, A), 43_400);
        playing(original, 43_401, 1);
        Session playing = view(original, 43_401, 1, Playback.PLAYING, 350, true, true);
        original.selectSegment(1, playing, 3);
        original.previous(playing, 4);
        original.pause(true, playing, 5);
        Saved saved = original.save();
        check(saved.transitionTargetIndex() == 0 && saved.remainingGapMs() == 3000 && saved.desiredPaused(),
                "Previous to first row saves a full paused gap with zero target");
        for (boolean paused : new boolean[]{true, false}) {
            FilmProgramState loaded = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
            Session waiting = new Session(id(43_410), A, 0, -1, Playback.WAITING, 0, 0,
                    false, false, evidence(A), false, "", true);
            loaded.rebindRestored(waiting, 1);
            if (!paused) loaded.pause(false, waiting, 1);
            Halt halt = halt(loaded);
            loaded.acknowledge(halt.operation(), waiting, "clock_initializing", 2);
            loaded.advanceGap(waiting, 86_400_000, 3);
            loaded.acknowledge(halt.operation(), waiting, "clock_initializing", 4);
            check(loaded.status() == Status.GAP && loaded.pending().equals(halt) && !loaded.gapHaltAcknowledged()
                            && loaded.remainingGapMs() == 3000 && loaded.presentationBlack() && loaded.transitionTargetIndex() == 0,
                    "manager-before-TMS initialization preserves exact Halt, black hold, zero target and all offline time");
            Session stopped = new Session(waiting.epoch(), A, 0, 2, Playback.STOPPED, 0, 1000,
                    false, true, evidence(A), false, "", true);
            loaded.acknowledge(halt.operation() - 1, stopped, null, 5);
            check(loaded.pending().equals(halt) && !loaded.gapHaltAcknowledged(), "stale callback cannot end cold clock wait");
            loaded.acknowledge(halt.operation(), stopped, null, 6);
            loaded.advanceGap(stopped, 0, 9000);
            check(loaded.pending() == null && loaded.gapHaltAcknowledged() && loaded.remainingGapMs() == 3000,
                    "only actual current-clock STOPPED ends cold wait with a fresh elapsed baseline");
            if (paused) {
                loaded.advanceGap(stopped, 86_400_000, 9001);
                check(loaded.status() == Status.GAP && loaded.desiredPaused() && loaded.remainingGapMs() == 3000
                                && loaded.presentationBlack() && loaded.pending() == null,
                        "paused cold reentry stays held without autoplay or elapsed countdown");
                loaded.pause(false, stopped, 9002);
            }
            loaded.advanceGap(stopped, 2999, 9003);
            check(loaded.remainingGapMs() == 1 && loaded.transitionTargetIndex() == 0, "cold zero-target gap keeps its final millisecond");
            loaded.advanceGap(stopped, 1, 9004);
            check(prepare(loaded).segmentIndex() == 0 && loaded.presentationBlack(), "explicitly resumed previous target prepares first row only");
        }
        for (String error : new String[]{null, "permission", "grant_revoked", "stale_session"}) {
            FilmProgramState loaded = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
            Session waiting = coldWaiting(43_420);
            loaded.rebindRestored(waiting, 1);
            loaded.acknowledge(halt(loaded).operation(), waiting, error, 2);
            check(loaded.status() == Status.FAILED && loaded.pending() == null && !loaded.gapHaltAcknowledged()
                            && loaded.failure().equals("stop_unconfirmed_source_" + (error == null ? "not_stopped" : error)),
                    "WAITING success and real authority rejections remain final: " + error);
        }
        for (Session invalid : List.of(
                new Session(id(43_425), A, 0, 0, Playback.WAITING, 0, 0, false, false, evidence(A), false, "", true),
                new Session(id(43_425), A, 0, -1, Playback.PAUSED, 0, 0, false, false, evidence(A), false, "", true),
                new Session(id(43_425), A, 0, -1, Playback.WAITING, 0, 0, false, false, evidence(A), false, "", false),
                new Session(id(43_425), A, 0, -1, Playback.WAITING, 0, 0, false, false, evidence(A), false, "permission", true))) {
            FilmProgramState loaded = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
            loaded.rebindRestored(coldWaiting(43_425), 1);
            loaded.acknowledge(halt(loaded).operation(), invalid, "clock_initializing", 2);
            check(loaded.status() == Status.FAILED && loaded.pending() == null && !loaded.gapHaltAcknowledged(),
                    "initialization retry requires absent-clock revision, WAITING, held intent and no source failure");
        }
        FilmProgramState timeout = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        Session waiting = coldWaiting(43_430);
        timeout.rebindRestored(waiting, 1);
        Halt halt = halt(timeout);
        timeout.acknowledge(halt.operation(), waiting, "clock_initializing", 19);
        timeout.acknowledge(halt.operation(), waiting, "clock_initializing", 21);
        check(timeout.status() == Status.FAILED && timeout.failure().equals("stop_unconfirmed_source_timeout")
                        && timeout.pending() == null, "repeated initialization responses cannot extend original Halt deadline");
        FilmProgramState takeover = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        takeover.rebindRestored(waiting, 1);
        takeover.acknowledge(halt(takeover).operation(), coldWaiting(43_431), "clock_initializing", 2);
        check(takeover.status() == Status.OVERRIDDEN && takeover.pending() == null && !takeover.ownsSession(),
                "clock-initializing response from another epoch never permits retry");
        FilmProgramState stopped = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        stopped.rebindRestored(waiting, 1);
        stopped.stop(waiting);
        stopped.acknowledge(halt(stopped).operation(), waiting, "clock_initializing", 2);
        check(stopped.status() == Status.FAILED && stopped.pending() == null && !stopped.gapHaltAcknowledged(),
                "explicit final Stop never becomes an unbounded initializing retry");
    }

    private static void restoredStopColdClockOrdering() {
        FilmProgramState original = start(snapshot(A), 43_500);
        playing(original, 43_501, 1);
        original.observe(view(original, 43_501, 1, Playback.PLAYING, 350, true, true), 3);
        Saved saved = original.save();
        FilmProgramState loaded = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        Session waiting = coldWaiting(43_510);
        loaded.stopRestored(waiting, 1);
        loaded.stopRestored(waiting, 19);
        check(loaded.restoring() && !loaded.finished() && loaded.pending() == null
                        && loaded.globalTimeMs() == 350 && loaded.epoch().equals(waiting.epoch()),
                "cold explicit stop waits without Prepare, Resume, fabricated Stop ACK or lost position");
        FilmProgramState persisted = FilmProgramState.restore(original.snapshot(), loaded.save(), 0, TIMEOUT);
        check(persisted.restoring() && !persisted.finished() && persisted.pending() == null,
                "saving an unacknowledged restored stop cannot create final stopped history");
        Session opened = new Session(waiting.epoch(), A, 0, 0, Playback.PAUSED, 0, 1000,
                false, true, evidence(A), false, "", true);
        loaded.stopRestored(opened, 19);
        Halt halt = halt(loaded);
        check(halt.expectedEpoch().equals(waiting.epoch()) && loaded.globalTimeMs() == 350,
                "opening the exact clock permits only Halt, without requiring viewer Ready");
        loaded.acknowledge(halt.operation() - 1, opened, null, 19);
        check(loaded.pending().equals(halt), "stale operation cannot acknowledge restored stop");
        loaded.acknowledge(halt.operation(), new Session(waiting.epoch(), A, 0, 1, Playback.STOPPED,
                0, 1000, false, true, evidence(A), false, "", true), null, 19);
        check(loaded.status() == Status.STOPPED && loaded.pending() == null && loaded.failure().isEmpty(),
                "matching Halt acknowledgement alone confirms the restored stop");

        FilmProgramState timeout = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        timeout.stopRestored(waiting, 1);
        timeout.stopRestored(waiting, TIMEOUT);
        check(timeout.status() == Status.FAILED && timeout.failure().equals("restore_required_timeout")
                        && timeout.pending() == null,
                "repeated cold stop attempts retain the original bounded restore deadline");
        FilmProgramState takeover = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        takeover.stopRestored(waiting, 1);
        takeover.stopRestored(coldWaiting(43_511), 2);
        check(takeover.status() == Status.OVERRIDDEN && takeover.pending() == null,
                "cold stop binds once and never halts a later replacement epoch");
        FilmProgramState denied = FilmProgramState.restore(original.snapshot(), saved, 0, TIMEOUT);
        denied.stopRestored(waiting, 1);
        denied.reject("grant_revoked", waiting);
        check(denied.status() == Status.FAILED && denied.failure().equals("source_grant_revoked"),
                "authority revocation during cold stop remains a failure, never cancellation");
        FilmProgramState gap = naturalGap(43_520);
        FilmProgramState stoppedGap = FilmProgramState.restore(gap.snapshot(), gap.save(), 0, TIMEOUT);
        stoppedGap.stopRestored(waiting, 1);
        check(stoppedGap.status() == Status.GAP && stoppedGap.restoring() && stoppedGap.pending() == null
                        && stoppedGap.remainingGapMs() == gap.remainingGapMs(),
                "cold explicit stop during a gap neither consumes gap time nor prepares its successor");
        stoppedGap.stopRestored(opened, 2);
        check(stoppedGap.status() == Status.STOPPED && stoppedGap.pending() instanceof Halt,
                "cold gap stop becomes only an exact Halt when the current clock opens");
        stoppedGap.acknowledge(halt(stoppedGap).operation(), opened, null, 3);
        check(stoppedGap.status() == Status.FAILED
                        && stoppedGap.failure().equals("stop_unconfirmed_source_not_stopped"),
                "an opened but merely paused clock cannot be accepted as a Stop acknowledgement");
    }

    private static Session coldWaiting(long epoch) {
        return new Session(id(epoch), A, 0, -1, Playback.WAITING, 0, 0, false, false, evidence(A), false, "", true);
    }

    private static void gapInvalidPersistenceAndLegacy() {
        FilmProgramState gap = naturalGap(44_000);
        Saved s = gap.save();
        for (int target : new int[]{-1, 2}) {
            Saved invalid = new Saved(s.snapshotId(), s.owner(), s.scheduledShowId(), s.segmentIndex(), s.localTimeMs(), s.globalTimeMs(),
                    s.desiredPaused(), s.epoch(), s.boundSource(), s.boundSourceType(), s.ownsSession(), s.operationRevision(), s.status(), s.failure(),
                    2, target, s.remainingGapMs(), true);
            rejects(() -> FilmProgramState.restore(gap.snapshot(), invalid, 0, TIMEOUT), "gap target must exist and be in snapshot");
        }
        Saved unbounded = new Saved(s.snapshotId(), s.owner(), s.scheduledShowId(), s.segmentIndex(), s.localTimeMs(), s.globalTimeMs(),
                s.desiredPaused(), s.epoch(), s.boundSource(), s.boundSourceType(), s.ownsSession(), s.operationRevision(), s.status(), s.failure(),
                2, 1, 3001, true);
        rejects(() -> FilmProgramState.restore(gap.snapshot(), unbounded, 0, TIMEOUT), "save cannot create a longer untrusted gap");
        Saved visibleGap = new Saved(s.snapshotId(), s.owner(), s.scheduledShowId(), s.segmentIndex(), s.localTimeMs(), s.globalTimeMs(),
                s.desiredPaused(), s.epoch(), s.boundSource(), s.boundSourceType(), s.ownsSession(), s.operationRevision(), s.status(), s.failure(),
                2, 1, 3000, false);
        rejects(() -> FilmProgramState.restore(gap.snapshot(), visibleGap, 0, TIMEOUT), "gap must persist black presentation");
        Saved wrongPolicy = new Saved(s.snapshotId(), s.owner(), s.scheduledShowId(), s.segmentIndex(), s.localTimeMs(), s.globalTimeMs(),
                s.desiredPaused(), s.epoch(), s.boundSource(), s.boundSourceType(), s.ownsSession(), s.operationRevision(), Status.PLAYING, "");
        rejects(() -> FilmProgramState.restore(gap.snapshot(), wrongPolicy, 0, TIMEOUT), "legacy save cannot silently acquire new gap policy");
        rejects(() -> gap.advanceGap(view(gap, 44_001, 1, Playback.ENDED, 1000, true, true), -1, 4), "negative elapsed time rejects");

        FilmProgramState legacy = start(snapshot(A, B), 44_100);
        playing(legacy, 44_101, 1);
        legacy.observe(view(legacy, 44_101, 1, Playback.PLAYING, 321, true, true), 3);
        Saved old = legacy.save();
        for (int version : new int[]{0, 1}) {
            Saved oldFields = new Saved(old.snapshotId(), old.owner(), old.scheduledShowId(), old.segmentIndex(), old.localTimeMs(), old.globalTimeMs(),
                    old.desiredPaused(), old.epoch(), old.boundSource(), old.boundSourceType(), old.ownsSession(), old.operationRevision(), old.status(), old.failure(),
                    version, 0, 0, false);
            FilmProgramState loaded = FilmProgramState.restore(legacy.snapshot(), oldFields, 0, TIMEOUT);
            check(loaded.globalTimeMs() == 321 && loaded.transitionTargetIndex() == -1 && loaded.remainingGapMs() == 0
                    && !loaded.presentationBlack() && loaded.restoring() && loaded.pending() == null,
                    "v0/v1 default appended fields migrate without accidental transition or command");
            loaded.rebindRestored(view(loaded, 44_102, 0, Playback.PAUSED, 0, true, true), 1);
            playing(loaded, 44_103, 2);
            loaded.observe(view(loaded, 44_103, 1, Playback.ENDED, 1000, true, true), 4);
            check(loaded.status() == Status.PREPARING && prepare(loaded).segmentIndex() == 1
                    && !loaded.presentationBlack(), "legacy plan retains gapless advancement after reload");
        }
    }

    private static FilmProgramState naturalGap(long beforeEpoch) {
        FilmProgramState state = start(gapSnapshot(A, B), beforeEpoch);
        playing(state, beforeEpoch + 1, 1);
        state.observe(view(state, beforeEpoch + 1, 1, Playback.ENDED, 1000, true, true), 3);
        check(state.status() == Status.GAP, "fixture enters natural gap");
        return state;
    }

    private static Session acknowledgeGapHalt(FilmProgramState state, long epoch, long tick) {
        Halt halt = halt(state);
        Session stopped = view(state, epoch, 2, Playback.STOPPED, 0, true, false);
        state.acknowledge(halt.operation(), stopped, null, tick);
        check(state.gapHaltAcknowledged() && state.status() == Status.GAP, "fixture acknowledges exact gap Halt");
        return stopped;
    }

    private static void sourceBoundReady() {
        FilmProgramState p = start(snapshot(A, B), 10);
        Prepare prepare = prepare(p);
        check(prepare.segmentIndex() == 0 && prepare.source().equals(A), "prepare starts exact ordered first source");
        check(prepare.expectedEpoch().equals(id(10)) && prepare.evidenceId().equals(evidence(A)), "prepare binds old epoch and immutable proof");
        check(p.status() == Status.PREPARING && !p.ownsSession(), "initial pre-existing source is not assumed owned");
        Session waiting = view(p, 11, 0, Playback.PAUSED, 0, false, false);
        p.acknowledge(prepare.operation(), waiting, null, 1);
        check(p.pending() == null && p.status() == Status.PREPARING, "successful prepare alone cannot run");
        p.observe(view(p, 11, 0, Playback.PAUSED, 0, false, true), 2);
        check(p.pending() == null, "Ready without metadata cannot run");
        p.observe(view(p, 11, 0, Playback.PAUSED, 0, true, false), 3);
        check(p.pending() == null, "metadata without viewer Ready cannot run");
        Session ready = view(p, 11, 0, Playback.PAUSED, 0, true, true);
        p.observe(ready, 4);
        Resume resume = resume(p);
        check(p.status() == Status.READY && resume.expectedEpoch().equals(id(11)), "Ready produces one exact-new-epoch resume");
        check(resume.expectedRevision() == 0 && resume.localMs() == 0 && !resume.remainPaused(), "first play targets zero using current WF revision");
        p.observe(ready, 5);
        check(p.pending().equals(resume), "repeated Ready does not emit another command");
        p.acknowledge(resume.operation(), view(p, 11, 1, Playback.PLAYING, 0, true, true), null, 6);
        check(p.status() == Status.PLAYING && p.pending() == null, "play becomes active only after resume acknowledgement");
        p.observe(view(p, 11, 1, Playback.PLAYING, 430, true, true), 7);
        check(p.localTimeMs() == 430 && p.globalTimeMs() == 430, "captures authoritative current position");

        FilmProgramState wrong = start(snapshot(A), 30);
        Prepare command = prepare(wrong);
        Session mismatched = new Session(id(31), B, 0, 0, Playback.PAUSED, 0, 2000, false, true, evidence(B), true, "");
        wrong.acknowledge(command.operation(), mismatched, null, 1);
        check(wrong.status() == Status.OVERRIDDEN && wrong.pending() == null, "Ready belonging to a different source cannot play or halt that source");

        FilmProgramState sticky = start(snapshot(A), 40);
        sticky.pause(true, initial(40), 1);
        check(sticky.desiredPaused(), "pause intent becomes sticky during initial prepare");
        readyPrepared(sticky, 41, 2);
        check(resume(sticky).remainPaused(), "later fresh Ready respects pause during prepare");
    }

    private static void delayedReadyPreservesFullRuntime() {
        List<FilmCatalog.Segment> segments=List.of(A,B,B).stream().map(source->new FilmCatalog.Segment(source,
                new FilmCatalog.Observation(false,6021,proof(source)))).toList();
        FilmCatalog.Snapshot snapshot=new FilmCatalog.Snapshot(id(20_000).toString(),film(segments));
        FilmProgramState state=FilmProgramState.start(snapshot,OWNER,"delayed-show",initial(30_000),0,TIMEOUT);
        long tick=0;
        for(int index=0;index<3;index++){
            long epoch=30_001+index;
            Prepare command=prepare(state);
            state.acknowledge(command.operation(),view(state,epoch,0,Playback.PAUSED,0,true,false),null,++tick);
            tick+=5;state.observe(view(state,epoch,0,Playback.PAUSED,0,true,false),tick);
            check(state.status()==Status.PREPARING&&state.globalTimeMs()==index*6021L,"Ready wait does not consume recorded runtime for segment "+index);
            state.observe(view(state,epoch,0,Playback.PAUSED,0,true,true),++tick);
            Resume resume=resume(state);
            state.acknowledge(resume.operation(),view(state,epoch,1,Playback.PLAYING,0,true,true),null,++tick);
            state.observe(view(state,epoch,1,Playback.ENDED,6021,true,true),++tick);
        }
        check(state.status()==Status.COMPLETED&&state.globalTimeMs()==18063,"three delayed segments complete the full exact 18.063 seconds naturally");
        FilmProgramState stopped=FilmProgramState.start(snapshot,OWNER,"stopped-show",initial(31_000),0,TIMEOUT);
        playing(stopped,31_001,1);stopped.stop(view(stopped,31_001,1,Playback.PLAYING,1600,true,true));
        check(stopped.status()==Status.STOPPED&&stopped.globalTimeMs()==1600,"explicit short stop retains partial position and cannot claim natural completion");
    }

    private static void orderedAndRepeatedSegments() {
        FilmProgramState p = start(snapshot(A, A, B), 100);
        playing(p, 101, 1);
        Session ended = view(p, 101, 1, Playback.ENDED, 1000, true, true);
        p.observe(ended, 3);
        Prepare second = prepare(p);
        check(p.segmentIndex() == 1 && second.segmentIndex() == 1 && second.source().equals(A), "consecutive repeated source is a distinct segment");
        check(p.globalTimeMs() == 1000 && p.localTimeMs() == 0, "first natural end selects next ordered offset");
        p.observe(ended, 4);
        p.observe(ended, 5);
        check(p.pending().equals(second) && p.segmentIndex() == 1, "repeated Ended ticks advance exactly once");
        playing(p, 102, 6);
        check(p.epoch().equals(id(102)), "repeated URL still needs a fresh epoch and Ready");
        p.observe(view(p, 102, 1, Playback.ENDED, 1000, true, true), 8);
        check(prepare(p).source().equals(B) && p.segmentIndex() == 2 && p.globalTimeMs() == 2000, "third segment preserves ordered repeats");
        playing(p, 103, 9);
        p.observe(view(p, 103, 1, Playback.ENDED, 2000, true, true), 11);
        check(p.status() == Status.COMPLETED && p.globalTimeMs() == 4000 && p.pending() == null, "last natural end completes exactly at full repeated timeline");
        p.observe(view(p, 103, 1, Playback.ENDED, 2000, true, true), 12);
        check(p.status() == Status.COMPLETED && p.segmentIndex() == 2, "final Ended never loops to first segment");
    }

    private static void outputFailureCannotAcknowledgeStop() {
        FilmProgramState p=start(snapshot(A,B),250);
        playing(p,251,1);
        p.stop(view(p,251,1,Playback.PLAYING,730,true,true));
        Halt halt=halt(p);
        Session stopped=view(p,251,2,Playback.STOPPED,0,true,true);
        p.acknowledge(halt.operation()-1,stopped,"projection_unavailable",3);
        check(p.pending().equals(halt)&&p.failure().isEmpty(),"stale output failure cannot consume exact pending Halt");
        p.acknowledge(halt.operation(),stopped,"projection_unavailable",4);
        check(p.failure().contains("projection_unavailable")&&p.pending()==null,
                "stopped clock alone cannot acknowledge Stop when pair output shutdown failed");
        check(p.globalTimeMs()==730&&p.segmentIndex()==0,"output denial preserves reported position and never advances");
    }

    private static void naturalEndAndExplicitStop() {
        FilmProgramState stop = start(snapshot(A, B), 200);
        playing(stop, 201, 1);
        Session at = view(stop, 201, 1, Playback.PLAYING, 730, true, true);
        stop.stop(at);
        check(stop.status() == Status.STOPPED && stop.globalTimeMs() == 730, "explicit stop retains reported position and ends run");
        Halt halt = halt(stop);
        check(halt.expectedEpoch().equals(id(201)), "stop halts only owned epoch");
        stop.acknowledge(halt.operation(), view(stop, 201, 2, Playback.STOPPED, 0, true, true), null, 3);
        stop.observe(view(stop, 201, 2, Playback.ENDED, 1000, true, true), 4);
        check(stop.status() == Status.STOPPED && stop.segmentIndex() == 0 && stop.globalTimeMs() == 730, "stop acknowledgement reset and Ended cannot advance or erase saved position");

        FilmProgramState externalStop = start(snapshot(A, B), 210);
        playing(externalStop, 211, 1);
        externalStop.observe(view(externalStop, 211, 2, Playback.STOPPED, 400, true, true), 3);
        check(externalStop.status() == Status.STOPPED && externalStop.segmentIndex() == 0, "reported Stop is not natural Ended");
        check(externalStop.pending() == null && externalStop.failure().equals("source_stopped"), "external stop never starts the next source");

        FilmProgramState pausedEnd = start(snapshot(A, B), 220);
        pausedEnd.pause(true, initial(220), 0);
        playing(pausedEnd, 221, 1);
        pausedEnd.observe(view(pausedEnd, 221, 1, Playback.ENDED, 1000, true, true, true), 3);
        check(pausedEnd.status() == Status.PAUSED && pausedEnd.segmentIndex() == 0 && pausedEnd.pending() == null, "paused end position cannot automatically advance");

        FilmProgramState onlyTimestamp = start(snapshot(A, B), 230);
        playing(onlyTimestamp, 231, 1);
        onlyTimestamp.observe(view(onlyTimestamp, 231, 1, Playback.PLAYING, 1000, true, true), 3);
        check(onlyTimestamp.segmentIndex() == 0 && onlyTimestamp.pending() == null, "timestamp equal to duration is not an Ended event");
    }

    private static void completedDisplayEligibility() {
        FilmProgramState completed = start(snapshot(A), 50_000);
        check(!completed.completedDisplayCandidate(), "preparation is not completed display history");
        playing(completed, 50_001, 1);
        check(!completed.completedDisplayCandidate(), "active playback uses the ordinary exact-run selector");
        completed.observe(view(completed, 50_001, 1, Playback.ENDED, 1000, true, true), 3);
        check(completed.completedDisplayCandidate() && completed.finished() && completed.pending() == null,
                "only clean natural final Ended is eligible for retained display without transport");
        var restored = FilmProgramState.restore(completed.snapshot(), completed.save(), 10, TIMEOUT);
        check(restored.completedDisplayCandidate() && restored.pending() == null && !restored.restoring(),
                "model restoration stays final; adapter must independently revalidate retained display authority");
        FilmProgramState stopped = start(snapshot(A), 50_010);playing(stopped, 50_011, 1);
        stopped.stop(view(stopped, 50_011, 1, Playback.PLAYING, 200, true, true));
        check(!stopped.completedDisplayCandidate(), "explicit STOP never qualifies as natural completion");
        FilmProgramState failed = start(snapshot(A), 50_020);
        failed.reject("grant_revoked", initial(50_020));
        check(!failed.completedDisplayCandidate(), "failed programme cannot retain a completed poster");
        FilmProgramState overridden = start(snapshot(A), 50_030);playing(overridden, 50_031, 1);
        overridden.observe(view(overridden, 50_032, 1, Playback.PLAYING, 100, true, true), 3);
        check(!overridden.completedDisplayCandidate() && overridden.status() == Status.OVERRIDDEN,
                "a replacement epoch cannot inherit completed display eligibility");
    }

    private static void globalSeeks() {
        for (boolean paused : new boolean[]{false, true}) {
            FilmProgramState p = start(snapshot(A, B), paused ? 300 : 310);
            long epoch = paused ? 301 : 311;
            if (paused) p.pause(true, initial(300), 0);
            playing(p, epoch, 1);
            Session at = view(p, epoch, 1, paused ? Playback.PAUSED : Playback.PLAYING, 100, true, true);
            p.seek(500, at, 3);
            Resume same = resume(p);
            check(same.expectedEpoch().equals(id(epoch)) && same.localMs() == 500 && same.remainPaused() == paused, "same segment seek keeps exact session and pause intent");
            p.acknowledge(same.operation(), view(p, epoch, 2, paused ? Playback.PAUSED : Playback.PLAYING, 500, true, true), null, 4);
            p.seek(1500, view(p, epoch, 2, paused ? Playback.PAUSED : Playback.PLAYING, 500, true, true), 5);
            Prepare cross = prepare(p);
            check(cross.segmentIndex() == 1 && cross.source().equals(B) && p.localTimeMs() == 500, "global cross-boundary seek targets second source local time");
            check(p.desiredPaused() == paused && p.status() == Status.PREPARING, "cross-boundary preparation retains playing or paused intent");
            readyPrepared(p, epoch + 1, 6);
            Resume crossResume = resume(p);
            check(crossResume.localMs() == 500 && crossResume.remainPaused() == paused, "fresh Ready restores exact global target and pause intent");
            p.acknowledge(crossResume.operation(), view(p, epoch + 1, 1, paused ? Playback.PAUSED : Playback.PLAYING, 500, true, true), null, 7);
            check(p.globalTimeMs() == 1500 && p.status() == (paused ? Status.PAUSED : Status.PLAYING), "cross seek acknowledgement preserves timeline");
            p.seek(1000, view(p, epoch + 1, 1, paused ? Playback.PAUSED : Playback.PLAYING, 500, true, true), 8);
            check(resume(p).localMs() == 0 && p.segmentIndex() == 1, "exact middle boundary selects next segment start");
            Resume boundary = resume(p);
            p.acknowledge(boundary.operation(), view(p, epoch + 1, 2, paused ? Playback.PAUSED : Playback.PLAYING, 0, true, true), null, 9);
            p.seek(3000, view(p, epoch + 1, 2, paused ? Playback.PAUSED : Playback.PLAYING, 0, true, true), 10);
            check(p.segmentIndex() == 1 && resume(p).localMs() == 2000 && resume(p).remainPaused() == paused, "exact film end is final segment end and retains pause");
            Resume end = resume(p);
            p.acknowledge(end.operation(), view(p, epoch + 1, 3, paused ? Playback.PAUSED : Playback.PLAYING, 2000, true, true), null, 11);
            check(p.globalTimeMs() == 3000 && p.pending() == null, "end seek acknowledgement cannot loop");
            p.observe(view(p, epoch + 1, 3, Playback.ENDED, 2000, true, true, paused), 12);
            check(p.status() == (paused ? Status.PAUSED : Status.COMPLETED) && p.segmentIndex() == 1, "end seek completes only after active natural Ended");
        }

        FilmProgramState pending = start(snapshot(A, B), 320);
        pending.seek(600, initial(320), 1);
        pending.pause(true, initial(320), 2);
        readyPrepared(pending, 321, 3);
        check(resume(pending).localMs() == 600 && resume(pending).remainPaused(), "seek and pause during preparation survive fresh Ready");
    }

    private static void externalTransport() {
        FilmProgramState p = start(snapshot(A, B), 970);
        playing(p, 971, 1);
        Session paused = view(p, 971, 2, Playback.PAUSED, 490, true, true, true);
        p.observe(paused, 3);
        check(p.status() == Status.PAUSED && p.desiredPaused() && p.localTimeMs() == 490 && p.pending() == null,
                "external same-session pause becomes authoritative after preparation");
        p.seek(600, paused, 4);
        Resume seek = resume(p);
        check(seek.remainPaused(), "later program seek preserves externally paused transport");
        p.acknowledge(seek.operation(), view(p, 971, 3, Playback.PAUSED, 600, true, true), null, 5);
        Session resumed = view(p, 971, 4, Playback.PLAYING, 650, true, true);
        p.observe(resumed, 6);
        check(p.status() == Status.PLAYING && !p.desiredPaused() && p.localTimeMs() == 650 && p.pending() == null,
                "external same-session play becomes authoritative without another program command");
        p.seek(1500, resumed, 7);
        readyPrepared(p, 972, 8);
        check(!resume(p).remainPaused(), "cross-segment seek preserves externally resumed transport");
        Resume pending = resume(p);
        p.observe(view(p, 972, 0, Playback.PAUSED, 0, true, true), 9);
        check(!p.desiredPaused() && p.pending().equals(pending), "paused pre-command source cannot overwrite pending resume intent");
    }

    private static void viewerHoldPreservesIntent() {
        FilmCatalog.Snapshot snapshot = snapshot(A, B);
        FilmProgramState p = start(snapshot, 980);
        playing(p, 981, 1);
        Session held = view(p, 981, 2, Playback.PAUSED, 460, true, false, false);
        p.observe(held, 3);
        check(p.status() == Status.PAUSED && !p.desiredPaused() && p.pending() == null,
                "viewer Ready loss visibly holds playback without inventing user Pause intent");
        Saved heldSave = p.save();
        check(!heldSave.desiredPaused() && heldSave.globalTimeMs() == 460 && heldSave.status() == Status.PAUSED,
                "saving a held clock preserves its position and requested Play intent");
        p.observe(view(p, 981, 2, Playback.PAUSED, 460, true, true, false), 4);
        check(p.status() == Status.PAUSED && !p.desiredPaused() && p.pending() == null,
                "Ready returning before clock release cannot falsely display Playing or issue duplicate resume");
        p.observe(view(p, 981, 3, Playback.PLAYING, 470, true, true, false), 5);
        check(p.status() == Status.PLAYING && !p.desiredPaused(), "actual clock release restores visible Playing");

        FilmProgramState loaded = FilmProgramState.restore(snapshot, heldSave, 0, TIMEOUT);
        check(loaded.restoring() && !loaded.desiredPaused() && loaded.localTimeMs() == 460,
                "restore retains pre-hold Play intent rather than deriving Pause from saved display state");
        loaded.rebindRestored(view(loaded, 982, 0, Playback.PAUSED, 0, true, false, false), 1);
        Prepare fresh = prepare(loaded);
        Session freshWaiting = view(loaded, 983, 0, Playback.PAUSED, 0, true, false, true);
        loaded.acknowledge(fresh.operation(), freshWaiting, null, 2);
        check(loaded.status() == Status.PREPARING && !loaded.desiredPaused() && loaded.pending() == null,
                "fresh paused replacement still waits for viewer Ready without overwriting saved intent");
        loaded.observe(view(loaded, 983, 0, Playback.PAUSED, 0, true, true, true), 3);
        Resume resumed = resume(loaded);
        check(!resumed.remainPaused() && resumed.localMs() == 460,
                "fresh restored Ready resumes the saved position using original Play intent");
        loaded.acknowledge(resumed.operation(), view(loaded, 983, 1, Playback.PLAYING, 460, true, true, false), null, 4);
        check(loaded.status() == Status.PLAYING && !loaded.desiredPaused() && loaded.localTimeMs() == 460,
                "restored held session reaches acknowledged Playing");

        Session userPaused = view(loaded, 983, 2, Playback.PAUSED, 480, true, true, true);
        loaded.observe(userPaused, 5);
        loaded.seek(1500, userPaused, 6);
        Prepare cross = prepare(loaded);
        loaded.acknowledge(cross.operation(), view(loaded, 984, 0, Playback.PAUSED, 0, true, true, true), null, 7);
        Resume pausedCross = resume(loaded);
        check(loaded.desiredPaused() && pausedCross.remainPaused() && pausedCross.localMs() == 500,
                "genuine TMS Pause intent remains paused across source-changing seek");

        check(view(p, 981, 3, Playback.PAUSED, 470, true, true).pausedIntent(),
                "compatibility Session constructor defaults paused clock to Pause intent");
        check(!view(p, 981, 3, Playback.PLAYING, 470, true, true).pausedIntent(),
                "compatibility Session constructor defaults playing clock to Play intent");
    }

    private static void staleOperationsAndTakeover() {
        FilmProgramState p = start(snapshot(A, B), 400);
        Prepare first = prepare(p);
        p.seek(1500, initial(400), 1);
        Prepare replacement = prepare(p);
        check(replacement.operation() > first.operation(), "replacement increments operation identity");
        p.acknowledge(first.operation(), new Session(id(401), A, 0, 0, Playback.PAUSED, 0, 1000, false, true, evidence(A), true, ""), null, 2);
        check(p.pending().equals(replacement) && p.epoch().equals(id(400)), "stale prepare reply cannot bind its epoch");
        readyPrepared(p, 402, 3);
        Resume oldResume = resume(p);
        Session current = view(p, 402, 0, Playback.PAUSED, 0, true, true);
        p.pause(true, current, 4);
        Resume newResume = resume(p);
        check(newResume.operation() > oldResume.operation() && newResume.remainPaused(), "pause supersedes queued play with new operation");
        p.acknowledge(oldResume.operation(), view(p, 402, 1, Playback.PLAYING, 500, true, true), null, 5);
        check(p.pending().equals(newResume) && p.status() == Status.READY, "late play acknowledgement cannot undo pause");
        p.acknowledge(newResume.operation(), view(p, 402, 1, Playback.PAUSED, 500, true, true), null, 6);
        p.observe(view(p, 403, 1, Playback.PLAYING, 900, true, true), 7);
        check(p.status() == Status.OVERRIDDEN && p.pending() == null && !p.ownsSession(), "authoritative new epoch is takeover without halting new session");
        check(p.globalTimeMs() == 1500, "takeover retains last known program position");
        p.acknowledge(newResume.operation(), view(p, 402, 1, Playback.PAUSED, 500, true, true), null, 8);
        check(p.status() == Status.OVERRIDDEN, "old acknowledgement cannot resurrect overridden program");

        FilmProgramState rejected = start(snapshot(A), 410);
        Prepare command = prepare(rejected);
        rejected.acknowledge(command.operation(), new Session(id(411), B, 0, 9, Playback.PLAYING, 10, 2000, false, true, evidence(B), true, ""), "stale_session", 1);
        check(rejected.status() == Status.OVERRIDDEN && rejected.pending() == null, "stale-session rejection preserves newer media");

        FilmProgramState channel = start(snapshot(A), 420);
        Prepare channelCommand = prepare(channel);
        channel.acknowledge(channelCommand.operation(), new Session(id(421), A, 1, 0, Playback.PAUSED, 0, 1000, false, true, evidence(A), true, ""), null, 1);
        check(channel.status() == Status.OVERRIDDEN && channel.pending() == null, "matching URL on wrong source type cannot claim current session");

        FilmProgramState stoppedNewer = start(snapshot(A), 430);
        playing(stoppedNewer, 431, 1);
        stoppedNewer.stop(view(stoppedNewer, 432, 4, Playback.PLAYING, 400, true, true));
        check(stoppedNewer.status() == Status.OVERRIDDEN && stoppedNewer.pending() == null, "explicit Stop cannot stop a newer external session");
    }

    private static void failuresAndTimeouts() {
        FilmProgramState initialFailure = start(snapshot(A), 500);
        initialFailure.acknowledge(prepare(initialFailure).operation(), initial(500), "media_permission", 1);
        check(initialFailure.status() == Status.FAILED && initialFailure.failure().equals("source_media_permission"), "initial preparation denial gives a clear failure");
        check(initialFailure.pending() == null, "initial denied prepare cannot halt unrelated old source");

        FilmProgramState nextFailure = start(snapshot(A, B), 510);
        playing(nextFailure, 511, 1);
        Session old = view(nextFailure, 511, 1, Playback.ENDED, 1000, true, true);
        nextFailure.observe(old, 3);
        nextFailure.acknowledge(prepare(nextFailure).operation(), old, "evidence_unavailable", 4);
        check(nextFailure.status() == Status.FAILED && nextFailure.segmentIndex() == 1, "next source failure stays failed at requested segment without skipping");
        check(halt(nextFailure).expectedEpoch().equals(id(511)), "failed replacement may halt only old owned epoch");
        Halt cleanup = halt(nextFailure);
        Session stoppedOld = new Session(old.epoch(), old.source(), old.sourceType(), old.revision() + 1,
                Playback.STOPPED, 0, old.durationMs(), old.live(), old.metadataReady(), old.evidenceId(), old.ready(), "");
        nextFailure.acknowledge(cleanup.operation(), stoppedOld, null, 5);
        nextFailure.observe(old, 6);
        check(nextFailure.status() == Status.FAILED && nextFailure.pending() == null, "failed next source cannot automatically resume or repeat old source");

        FilmProgramState noPrepare = start(snapshot(A), 520);
        noPrepare.observe(initial(520), 19);
        check(noPrepare.status() == Status.PREPARING, "prepare waits strictly within bounded timeout");
        noPrepare.observe(initial(520), 20);
        check(noPrepare.status() == Status.FAILED && noPrepare.failure().equals("prepare_timeout"), "prepare acknowledgement timeout is bounded");

        FilmProgramState noMetadata = start(snapshot(A), 530);
        Session waiting = view(noMetadata, 531, 0, Playback.PAUSED, 0, false, false);
        noMetadata.acknowledge(prepare(noMetadata).operation(), waiting, null, 1);
        noMetadata.observe(waiting, 20);
        check(noMetadata.failure().equals("metadata_timeout") && halt(noMetadata).expectedEpoch().equals(id(531)), "metadata timeout fails closed on prepared epoch");

        FilmProgramState noViewer = start(snapshot(A), 540);
        Session metadata = view(noViewer, 541, 0, Playback.PAUSED, 0, true, false);
        noViewer.acknowledge(prepare(noViewer).operation(), metadata, null, 1);
        noViewer.observe(metadata, 20);
        check(noViewer.failure().equals("awaiting_viewer_timeout"), "no Ready fails with explicit viewer timeout");

        FilmProgramState noResume = start(snapshot(A), 550);
        readyPrepared(noResume, 551, 1);
        noResume.observe(view(noResume, 551, 0, Playback.PAUSED, 0, true, true), 21);
        check(noResume.status() == Status.FAILED && noResume.failure().equals("resume_timeout"), "unacknowledged resume is bounded");

        for (String reason : List.of("duration_mismatch", "live_unschedulable", "unverified_duration", "evidence_unavailable", "source_decoder_failed")) {
            FilmProgramState bad = start(snapshot(A), 560);
            Session facts = new Session(id(561), A, 0, 0, Playback.PAUSED, 0,
                    reason.equals("duration_mismatch") ? 999 : reason.equals("unverified_duration") ? 0 : 1000,
                    reason.equals("live_unschedulable"), true,
                    reason.equals("evidence_unavailable") ? null : evidence(A), true,
                    reason.equals("source_decoder_failed") ? "decoder_failed" : "");
            bad.acknowledge(prepare(bad).operation(), facts, null, 1);
            check(bad.status() == Status.FAILED && bad.failure().equals(reason), "unsafe prepared facts reject: " + reason);
            check(bad.pending() instanceof Halt && !(bad.pending() instanceof Resume), "unsafe facts never create a resume: " + reason);
        }

        FilmProgramState evidenceMismatch = start(snapshot(A), 570);
        evidenceMismatch.acknowledge(prepare(evidenceMismatch).operation(), new Session(id(571), A, 0, 0, Playback.PAUSED, 0, 1000, false, true, evidence(B), true, ""), null, 1);
        check(evidenceMismatch.failure().equals("evidence_unavailable"), "another source's metadata evidence cannot authorize playback");

        FilmProgramState renewedProof = start(snapshot(A), 572);
        renewedProof.acknowledge(prepare(renewedProof).operation(), new Session(id(573), A, 0, 0, Playback.PAUSED,
                0, 1000, false, true, id(99_999), true, ""), null, 1);
        check(renewedProof.failure().equals("evidence_unavailable") && renewedProof.pending() instanceof Halt,
                "same source and duration with replaced evidence cannot authorize an old snapshot");

        Session existingSameSource = new Session(id(580), A, 0, 0, Playback.PAUSED, 0, 1000, false, true, evidence(A), true, "");
        FilmProgramState unchangedEpoch = FilmProgramState.start(snapshot(A), OWNER, null, existingSameSource, 0, TIMEOUT);
        unchangedEpoch.acknowledge(prepare(unchangedEpoch).operation(), existingSameSource, null, 1);
        check(unchangedEpoch.failure().equals("prepare_epoch_unchanged") && unchangedEpoch.pending() == null, "prepare requires genuinely new epoch even when target source matches");

        FilmProgramState unpausedPrepare = start(snapshot(A), 590);
        unpausedPrepare.acknowledge(prepare(unpausedPrepare).operation(), view(unpausedPrepare, 591, 0, Playback.PLAYING, 0, true, true), null, 1);
        check(unpausedPrepare.status() == Status.FAILED && unpausedPrepare.failure().equals("prepare_not_paused"), "prepare must initially pause new source before controlled resume");

        FilmProgramState absent = FilmProgramState.start(snapshot(A), OWNER, null, null, 0, TIMEOUT);
        check(absent.status() == Status.FAILED && absent.failure().equals("source_unavailable") && absent.pending() == null, "unavailable initial session fails without commands");

        rejects(() -> new FilmCatalog.Snapshot(id(9000).toString(), film(List.of(new FilmCatalog.Segment(A, null)))), "unknown duration cannot form runnable snapshot");
        rejects(() -> new FilmCatalog.Snapshot(id(9001).toString(), film(List.of(new FilmCatalog.Segment(A, new FilmCatalog.Observation(true, -1, proof(A)))))), "live source cannot form runnable snapshot");
        var legacy = new FilmCatalog.Provenance(OWNER.toString(), id(2).toString(), id(3).toString(), A);
        rejects(() -> new FilmCatalog.Snapshot(id(9002).toString(), film(List.of(new FilmCatalog.Segment(A, new FilmCatalog.Observation(false, 1000, legacy))))), "handwritten duration without server evidence cannot form runnable snapshot");
    }

    private static void adapterRejections() {
        FilmProgramState initial = start(snapshot(A), 900);
        Prepare deniedPrepare = prepare(initial);
        initial.reject("permission", initial(900));
        check(initial.status() == Status.FAILED && initial.failure().equals("source_permission") && initial.pending() == null,
                "adapter permission denial cannot halt an unowned initial source");
        initial.acknowledge(deniedPrepare.operation(), view(initial, 901, 0, Playback.PAUSED, 0, true, true), null, 1);
        check(initial.status() == Status.FAILED && initial.epoch().equals(id(900)), "rejection invalidates pending prepare replies");

        FilmProgramState missing = start(snapshot(A), 910);
        playing(missing, 911, 1);
        missing.observe(view(missing, 911, 1, Playback.PLAYING, 420, true, true), 3);
        missing.reject("terminal_unavailable", null);
        check(missing.status() == Status.FAILED && missing.failure().equals("source_terminal_unavailable") && missing.pending() == null,
                "missing terminal becomes a visible failure without claiming a successful stop");
        check(missing.ownsSession() && missing.globalTimeMs() == 420, "missing terminal retains ownership and last confirmed position for inspection");

        FilmProgramState deniedHalt = start(snapshot(A), 920);
        playing(deniedHalt, 921, 1);
        Session playing = view(deniedHalt, 921, 1, Playback.PLAYING, 430, true, true);
        deniedHalt.observe(playing, 3);
        deniedHalt.reject("permission", playing);
        Halt cleanup = halt(deniedHalt);
        check(deniedHalt.failure().equals("source_permission") && cleanup.expectedEpoch().equals(id(921)),
                "active permission denial permits one exact-owned-session cleanup");
        deniedHalt.reject("permission", playing);
        check(deniedHalt.status() == Status.FAILED && deniedHalt.failure().equals("stop_unconfirmed_source_permission")
                && deniedHalt.pending() == null && deniedHalt.ownsSession(), "rejected cleanup records unconfirmed stop without retry loop");
        Saved rejected = deniedHalt.save();
        deniedHalt.reject("another_failure", playing);
        deniedHalt.acknowledge(cleanup.operation(), view(deniedHalt, 921, 2, Playback.STOPPED, 0, true, true), null, 4);
        check(deniedHalt.save().equals(rejected), "repeated rejection and late cleanup acknowledgement cannot rewrite final failure");
        FilmProgramState restored = FilmProgramState.restore(deniedHalt.snapshot(), rejected, 0, TIMEOUT);
        check(restored.status() == Status.FAILED && restored.failure().equals(rejected.failure()) && restored.pending() == null,
                "unconfirmed stop persists visibly without automatic cleanup retry");

        for (String reason : new String[]{"permission", null, "not a safe key", "x".repeat(1000)}) {
            FilmProgramState stopped = start(snapshot(A), 930);
            playing(stopped, 931, 1);
            Session current = view(stopped, 931, 1, Playback.PLAYING, 440, true, true);
            stopped.stop(current);
            stopped.reject(reason, current);
            String suffix = "permission".equals(reason) ? "permission" : "unavailable";
            check(stopped.status() == Status.FAILED && stopped.failure().equals("stop_unconfirmed_source_" + suffix)
                    && stopped.failure().length() <= 160 && stopped.globalTimeMs() == 440 && stopped.pending() == null,
                    "explicit Stop rejection is bounded, preserves position, and never reports stopped");
        }

        for (int outcome = 0; outcome < 3; outcome++) {
            FilmProgramState stopped = start(snapshot(A), 940);
            playing(stopped, 941, 1);
            Session current = view(stopped, 941, 1, Playback.PLAYING, 450, true, true);
            stopped.stop(current);
            Halt halt = halt(stopped);
            stopped.acknowledge(halt.operation(), outcome == 0 ? null : current, outcome == 2 ? "permission" : null, 3);
            String suffix = outcome == 0 ? "unavailable" : outcome == 1 ? "not_stopped" : "permission";
            check(stopped.status() == Status.FAILED && stopped.failure().equals("stop_unconfirmed_source_" + suffix)
                    && stopped.pending() == null && stopped.globalTimeMs() == 450,
                    "halt acknowledgement must prove matching stopped session or expose bounded failure");
        }

        FilmProgramState takeover = start(snapshot(A), 950);
        playing(takeover, 951, 1);
        takeover.reject("permission", view(takeover, 952, 1, Playback.PLAYING, 460, true, true));
        check(takeover.status() == Status.OVERRIDDEN && !takeover.ownsSession() && takeover.pending() == null,
                "rejection cannot halt or retain ownership of a newer external epoch");

        FilmProgramState completed = start(snapshot(A), 960);
        playing(completed, 961, 1);
        completed.observe(view(completed, 961, 1, Playback.ENDED, 1000, true, true), 3);
        Saved finalSave = completed.save();
        completed.reject("terminal_unavailable", null);
        check(completed.save().equals(finalSave), "completed runs are immutable under later adapter rejection");
    }

    private static void saveAndRestore() {
        for (boolean paused : new boolean[]{false, true}) {
            FilmCatalog.Snapshot snapshot = snapshot(A, A, B);
            FilmProgramState original = FilmProgramState.start(snapshot, OWNER, "scheduled-show", initial(600), 10000, TIMEOUT);
            if (paused) original.pause(true, initial(600), 10000);
            playing(original, 601, 10001);
            original.observe(view(original, 601, 1, paused ? Playback.PAUSED : Playback.PLAYING, 420, true, true), 10003);
            Saved saved = original.save();
            FilmProgramState loaded = FilmProgramState.restore(snapshot, saved, 0, TIMEOUT);
            check(loaded.status() == Status.PREPARING && loaded.restoring() && loaded.pending() == null, "active save restores into gated paused preparation");
            check(loaded.desiredPaused() == paused && loaded.globalTimeMs() == 420 && loaded.owner().equals(OWNER), "restore retains intent, position and owner");
            check(loaded.scheduledShowId().equals("scheduled-show") && loaded.snapshot() == snapshot, "restore retains show and exact supplied immutable snapshot");
            check(loaded.operationRevision() > saved.operationRevision(), "restore invalidates all pre-save operation identities");
            Session rebound = view(loaded, 602, 0, Playback.PAUSED, 0, true, true);
            loaded.rebindRestored(rebound, 1); // test caller represents explicit verified controlledBy lease
            Prepare fresh = prepare(loaded);
            check(fresh.expectedEpoch().equals(id(602)) && fresh.source().equals(A), "explicit verified lease rebind may adopt restart epoch before fresh preparation");
            check(loaded.localTimeMs() == 420 && !loaded.restoring(), "rebind does not replace saved position with reloaded clock zero");
            loaded.acknowledge(saved.operationRevision(), view(loaded, 601, 1, Playback.PLAYING, 999, true, true), null, 2);
            check(loaded.pending().equals(fresh), "pre-save stale operation cannot resume restored session");
            Session freshNotReady = view(loaded, 603, 0, Playback.PAUSED, 0, true, false);
            loaded.acknowledge(fresh.operation(), freshNotReady, null, 3);
            check(loaded.pending() == null && loaded.status() == Status.PREPARING, "old Ready cannot satisfy fresh restored epoch");
            loaded.observe(freshNotReady, 10);
            check(loaded.status() == Status.PREPARING, "restart timeout origin uses new ticks rather than saved server age");
            loaded.observe(view(loaded, 603, 0, Playback.PAUSED, 0, true, true), 11);
            check(resume(loaded).localMs() == 420 && resume(loaded).remainPaused() == paused, "only fresh viewer Ready restores saved position and intent");
            Resume restoreResume = resume(loaded);
            loaded.acknowledge(restoreResume.operation(), view(loaded, 603, 1, paused ? Playback.PAUSED : Playback.PLAYING, 420, true, true), null, 12);
            check(loaded.status() == (paused ? Status.PAUSED : Status.PLAYING), "restore reaches the correct acknowledged playback state");
        }

        FilmCatalog.Snapshot snapshot = snapshot(A, B);
        FilmProgramState atEnd = start(snapshot, 610);
        playing(atEnd, 611, 1);
        atEnd.observe(view(atEnd, 611, 1, Playback.PLAYING, 1000, true, true), 3);
        FilmProgramState loadedEnd = FilmProgramState.restore(snapshot, atEnd.save(), 0, TIMEOUT);
        check(loadedEnd.segmentIndex() == 0 && loadedEnd.localTimeMs() == 1000, "reload preserves exact saved segment even at its duration");
        loadedEnd.rebindRestored(view(loadedEnd, 612, 0, Playback.PAUSED, 0, true, true), 1);
        check(prepare(loadedEnd).segmentIndex() == 0 && loadedEnd.globalTimeMs() == 1000, "restore never blindly resolves saved boundary into next segment");

        FilmProgramState noLease = FilmProgramState.restore(snapshot, atEnd.save(), 0, TIMEOUT);
        noLease.observe(view(noLease, 613, 0, Playback.PAUSED, 0, true, true), 1);
        check(noLease.status() == Status.OVERRIDDEN && noLease.pending() == null, "epoch mismatch without explicit lease rebind is external takeover");

        FilmProgramState wrongSource = FilmProgramState.restore(snapshot, atEnd.save(), 0, TIMEOUT);
        wrongSource.rebindRestored(new Session(id(614), B, 0, 0, Playback.PAUSED, 0, 2000, false, true, evidence(B), true, ""), 1);
        check(wrongSource.status() == Status.OVERRIDDEN && wrongSource.pending() == null, "restore hook still rejects source mismatch without halting it");

        FilmProgramState cross = start(snapshot, 620);
        playing(cross, 621, 1);
        Session old = view(cross, 621, 1, Playback.PLAYING, 500, true, true);
        cross.seek(1400, old, 3);
        FilmProgramState loadedCross = FilmProgramState.restore(snapshot, cross.save(), 0, TIMEOUT);
        check(loadedCross.segmentIndex() == 1 && loadedCross.boundSource().equals(A), "pending cross seek persists separate target and bound source");
        loadedCross.rebindRestored(old, 1);
        check(prepare(loadedCross).source().equals(B) && loadedCross.localTimeMs() == 400, "verified prior lease resumes intended cross-source preparation");

        FilmProgramState unowned = start(snapshot, 630);
        FilmProgramState loadedUnowned = FilmProgramState.restore(snapshot, unowned.save(), 0, TIMEOUT);
        loadedUnowned.rebindRestored(initial(630), 1);
        check(loadedUnowned.status() == Status.OVERRIDDEN && loadedUnowned.pending() == null, "unexecuted initial prepare cannot infer saved ownership");

        FilmProgramState noRebind = FilmProgramState.restore(snapshot, atEnd.save(), 0, TIMEOUT);
        noRebind.observe(view(noRebind, 611, 1, Playback.PAUSED, 1000, true, true), 20);
        check(noRebind.status() == Status.FAILED && noRebind.failure().equals("restore_required_timeout") && noRebind.pending() == null, "no restore lease confirmation eventually fails without touching current source");
    }

    private static void finalStateRestore() {
        FilmCatalog.Snapshot snapshot = snapshot(A);
        for (Status status : List.of(Status.FAILED, Status.COMPLETED, Status.STOPPED, Status.OVERRIDDEN)) {
            FilmProgramState p = start(snapshot, 700);
            playing(p, 701, 1);
            Session current = view(p, 701, 1, Playback.PLAYING, 300, true, true);
            p.observe(current, 3);
            switch (status) {
                case FAILED -> p.observe(new Session(id(701), A, 0, 1, Playback.PAUSED, 300, 1000, false, true, evidence(A), true, "decoder_failed"), 4);
                case COMPLETED -> p.observe(view(p, 701, 1, Playback.ENDED, 1000, true, true), 4);
                case STOPPED -> p.stop(current);
                case OVERRIDDEN -> p.observe(view(p, 702, 1, Playback.PLAYING, 300, true, true), 4);
                default -> throw new AssertionError(status);
            }
            check(p.status() == status, "fixture produces final state " + status);
            Saved saved = p.save();
            FilmProgramState restored = FilmProgramState.restore(snapshot, saved, 0, TIMEOUT);
            check(restored.status() == status && restored.pending() == null && !restored.restoring(), "final-state restore issues no playback command: " + status);
            check(restored.globalTimeMs() == saved.globalTimeMs() && restored.failure().equals(saved.failure()), "final-state restore retains timeline and reason: " + status);
            restored.observe(view(restored, 999, 1, Playback.ENDED, 1000, true, true), 100);
            check(restored.status() == status && restored.pending() == null, "later observation cannot resurrect final save: " + status);
        }
    }

    private static void invalidPersistence() {
        FilmCatalog.Snapshot snapshot = snapshot(A);
        FilmProgramState p = start(snapshot, 800);
        Saved s = p.save();
        Saved badPosition = new Saved(s.snapshotId(), s.owner(), s.scheduledShowId(), s.segmentIndex(), s.localTimeMs(), 99,
                s.desiredPaused(), s.epoch(), s.boundSource(), s.boundSourceType(), s.ownsSession(), s.operationRevision(), s.status(), s.failure());
        rejects(() -> FilmProgramState.restore(snapshot, badPosition, 0, TIMEOUT), "inconsistent local and global saved position rejects");
        FilmCatalog.Snapshot another = new FilmCatalog.Snapshot(id(888).toString(), snapshot.film());
        rejects(() -> FilmProgramState.restore(another, s, 0, TIMEOUT), "wrong snapshot ID cannot substitute another snapshot");
        Saved impossibleCompleted = new Saved(s.snapshotId(), s.owner(), s.scheduledShowId(), 0, 0, 0,
                s.desiredPaused(), s.epoch(), s.boundSource(), s.boundSourceType(), s.ownsSession(), s.operationRevision(), Status.COMPLETED, "");
        rejects(() -> FilmProgramState.restore(snapshot, impossibleCompleted, 0, TIMEOUT), "completed state must retain the final segment's actual end");
        rejects(() -> FilmProgramState.start(snapshot, OWNER, null, initial(800), 0, 0), "unbounded or zero timeout rejects");
        rejects(() -> p.seek(-1, initial(800), 0), "negative global seek rejects");
        rejects(() -> p.seek(1001, initial(800), 0), "global seek beyond trusted total rejects");
        p.stop(initial(800));
        rejects(() -> p.pause(false, initial(800), 0), "finished program cannot implicitly restart");
    }

    private static FilmProgramState start(FilmCatalog.Snapshot snapshot, long beforeEpoch) {
        return FilmProgramState.start(snapshot, OWNER, null, initial(beforeEpoch), 0, TIMEOUT);
    }
    private static Session initial(long epoch) {
        return new Session(id(epoch), "water://previous", 0, -1, Playback.WAITING, 0, 0, false, false, null, false, "");
    }
    private static Session view(FilmProgramState state, long epoch, int revision, Playback playback,
                                long timeMs, boolean metadata, boolean ready) {
        FilmCatalog.Segment segment = state.snapshot().film().segments().get(state.segmentIndex());
        return new Session(id(epoch), segment.sourceKey(), 0, revision, playback, timeMs,
                segment.durationMs().orElseThrow(), false, metadata, evidence(segment.sourceKey()), ready, "");
    }
    private static Session view(FilmProgramState state, long epoch, int revision, Playback playback,
                                long timeMs, boolean metadata, boolean ready, boolean pausedIntent) {
        FilmCatalog.Segment segment = state.snapshot().film().segments().get(state.segmentIndex());
        return new Session(id(epoch), segment.sourceKey(), 0, revision, playback, timeMs,
                segment.durationMs().orElseThrow(), false, metadata, evidence(segment.sourceKey()), ready, "", pausedIntent);
    }
    private static void readyPrepared(FilmProgramState state, long epoch, long tick) {
        Prepare prepare = prepare(state);
        state.acknowledge(prepare.operation(), view(state, epoch, 0, Playback.PAUSED, 0, true, true), null, tick);
        check(state.pending() instanceof Resume, "fresh prepared source emits one ready resume");
    }
    private static void playing(FilmProgramState state, long epoch, long tick) {
        readyPrepared(state, epoch, tick);
        Resume resume = resume(state);
        state.acknowledge(resume.operation(), view(state, epoch, 1,
                resume.remainPaused() ? Playback.PAUSED : Playback.PLAYING, resume.localMs(), true, true), null, tick + 1);
        check(state.status() == (state.desiredPaused() ? Status.PAUSED : Status.PLAYING), "resume acknowledgement establishes intended transport");
    }
    private static Prepare prepare(FilmProgramState state) {
        check(state.pending() instanceof Prepare, "expected Prepare, got " + state.pending() + " / " + state.status() + " / " + state.failure());
        return (Prepare) state.pending();
    }
    private static Resume resume(FilmProgramState state) {
        check(state.pending() instanceof Resume, "expected Resume, got " + state.pending() + " / " + state.status() + " / " + state.failure());
        return (Resume) state.pending();
    }
    private static Halt halt(FilmProgramState state) {
        check(state.pending() instanceof Halt, "expected Halt, got " + state.pending() + " / " + state.status() + " / " + state.failure());
        return (Halt) state.pending();
    }
    private static FilmCatalog.Snapshot snapshot(String... sources) {
        List<FilmCatalog.Segment> segments = new ArrayList<>();
        for (String source : sources) segments.add(new FilmCatalog.Segment(source,
                new FilmCatalog.Observation(false, source.equals(A) ? 1000 : 2000, proof(source))));
        return new FilmCatalog.Snapshot(id(20_000).toString(), film(segments));
    }
    private static FilmCatalog.Snapshot gapSnapshot(String... sources) {
        FilmCatalog.Snapshot legacy = snapshot(sources);
        return new FilmCatalog.Snapshot(legacy.id(), legacy.film(), 3000);
    }
    private static FilmCatalog.Film film(List<FilmCatalog.Segment> segments) {
        return new FilmCatalog.Film(id(20_001).toString(), 1, "Ordered program", "", segments);
    }
    private static FilmCatalog.Provenance proof(String source) {
        return new FilmCatalog.Provenance(OWNER.toString(), id(2).toString(), id(3).toString(), source, evidence(source).toString());
    }
    private static UUID evidence(String source) { return id(source.equals(A) ? 21_001 : 21_002); }
    private static UUID id(long value) { return new UUID(0, value); }
    private static void check(boolean result, String label) {
        checks++;
        if (!result) throw new AssertionError(label);
    }
    private static void rejects(Runnable action, String label) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }
}
