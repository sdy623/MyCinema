package local.cinemamanager.qa;

import local.cinemamanager.model.ManagerState;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Real tiles/clocks/persistence with explicitly synthetic duration evidence and viewer Ready. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FilmCompletionGameTests {
    private static ManagerState.Show plan(FilmPlaybackGameTests.Fixture f){
        long future=System.currentTimeMillis()+120_000;
        return f.manager.library.schedule(f.actor,f.hall.id,f.film.id(),f.film.revision(),f.manager.state.revision,
                f.manager.state.inputTime(future),"",true);
    }
    private static ManagerState.Show startPastNominalEnd(FilmPlaybackGameTests.Fixture f){
        var show=plan(f);
        show.start=System.currentTimeMillis()-show.filmDurationMs-1;
        show.end=show.start+show.filmDurationMs;
        show.status=ManagerState.Status.PREPARING;
        f.manager.state.enable(true);f.hall.override=false;
        f.h.assertTrue(f.manager.playback.startScheduled(f.hall,show)==null,"exact saved scheduled programme is prepared once");
        return show;
    }

    @GameTest(template="empty",timeoutTicks=1000000)
    public static void film_delayed_ready_completes_18063ms_media_and_6000ms_gaps_without_takeover(GameTestHelper h)throws Exception{
        var f=new FilmPlaybackGameTests.Fixture(h,true,6021,3);f.authorize();f.ready(false);
        var show=startPastNominalEnd(f);String showId=show.id;long start=show.start,end=show.end;
        Set<UUID> epochs=new HashSet<>();epochs.add(f.terminal.mediaEpoch());
        h.runAfterDelay(12,()->{
            h.assertTrue(show.status==ManagerState.Status.PREPARING&&f.manager.playback.active(f.hall.id),"past nominal end cannot complete or halt a programme waiting for Ready");
            h.assertTrue(f.view().playheadMs==0&&f.terminal.clock().paused(),"delayed Ready consumes no film time");
            var next=plan(f);next.start=System.currentTimeMillis()-1;next.end=next.start+next.filmDurationMs;
            f.ready(true);
            final long deadline=System.nanoTime()+45_000_000_000L;
            h.runAfterDelay(5,new Runnable(){long firstPlaying;public void run(){
                // Saving the successor atomically replaces manager.state; retained Show objects
                // belong to the previous snapshot and must never be used as live observations.
                var currentShow=f.manager.state.show(showId);var currentNext=f.manager.state.show(next.id);
                epochs.add(f.terminal.mediaEpoch());
                if(firstPlaying==0&&currentShow.startedAt>0)firstPlaying=currentShow.startedAt;
                if(f.manager.playback.active(f.hall.id)&&System.nanoTime()<deadline){h.runAfterDelay(5,()->this.run());return;}
                var currentView=f.view();
                String observed="show="+currentShow.status+", outcome="+currentShow.outcome+", playhead="+currentView.playheadMs+", clock="+f.terminal.clock().snapshot()+", epochs="+epochs.size();
                h.assertTrue(currentShow.status==ManagerState.Status.COMPLETED&&currentView.playheadMs==24063&&f.terminal.clock().ended(),"all 18.063 seconds of media plus two full three-second gaps finish by a real natural final clock Ended: "+observed);
                h.assertTrue(currentShow.start==start&&currentShow.end==end&&currentShow.end-currentShow.start==24063,"nominal timing is never extended or rewritten");
                h.assertTrue(currentShow.outcome.equals("mycinema.catalog.programme_completed_late"),"natural late completion is explicitly reported");
                h.assertTrue(currentNext.status==ManagerState.Status.MISSED&&currentNext.outcome.equals("mycinema.schedule.programme_conflict"),"due successor reports conflict instead of replacing the active programme");
                h.assertTrue(epochs.size()==3,"exactly three repeated segments own distinct epochs without duplicate starts");
                h.assertTrue(firstPlaying>0&&currentShow.startedAt==firstPlaying,"actual first playing time is not overwritten by later segment changes");
                verifyCompletedColdRestore(f,showId,end);
            }});
        });
    }

    private static void verifyCompletedColdRestore(FilmPlaybackGameTests.Fixture f,String showId,long end){
        var h=f.h;long remainder=h.getLevel().getGameTime()%5;
        if(remainder!=0){h.runAfterDelay(5-remainder,()->verifyCompletedColdRestore(f,showId,end));return;}
        UUID completedEpoch=f.terminal.mediaEpoch();
        var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
        var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
        f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());
        f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
        UUID loadedEpoch=f.terminal.mediaEpoch();
        h.assertTrue(!completedEpoch.equals(loadedEpoch)&&f.terminal.clock()==null,"full TMS disk reload rotates the epoch and initially has no clock");
        // Exercise manager-before-terminal startup order without manufacturing Ready.
        f.manager.playback.tick();
        h.assertTrue(f.terminal.clock()==null&&loadedEpoch.equals(f.terminal.mediaEpoch())
                &&f.manager.playback.present(f.hall.id),"completed history is matched before clock creation without executing a media command");
        h.runAfterDelay(12,()->{
            var restored=f.view();var restoredShow=f.manager.state.show(showId);
            h.assertTrue(f.manager.playback.present(f.hall.id)&&!f.manager.playback.active(f.hall.id)
                    &&restored.deviceState.equals("mycinema.catalog.programme_completed")
                    &&restored.playheadMs==24063&&restored.durationMs==24063,
                    "cold completed summary retains the full programme total without a fresh Ready");
            h.assertTrue(loadedEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().paused()
                    &&f.terminal.clock().time()==6021&&f.terminal.data.paused
                    &&!f.manager.playback.enabled(f.terminal.identity())&&restored.seekToken.isBlank(),
                    "historical completion issues no prepare, play or seek and grants no playback authority");
            h.assertTrue(restoredShow.status==ManagerState.Status.COMPLETED&&restoredShow.end==end
                    &&restoredShow.outcome.equals("mycinema.catalog.programme_completed_late"),
                    "cold summary preserves the scheduled final outcome and nominal end");
            f.terminal.data.url="water://replacement-after-completion-"+UUID.randomUUID();
            f.terminal.sourceReplaced();f.terminal.data.active=true;f.terminal.data.paused=true;f.terminal.setDirty();
            UUID replacementEpoch=f.terminal.mediaEpoch();
            h.runAfterDelay(12,()->{
                h.assertTrue(!f.manager.playback.present(f.hall.id)&&!f.manager.playback.active(f.hall.id)
                        &&replacementEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().paused(),
                        "an actual replacement retires the completed summary without touching its new session");
                h.assertTrue(f.manager.state.show(showId).status==ManagerState.Status.COMPLETED,
                        "source replacement does not erase the historical completed schedule record");
                h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=160)
    public static void film_past_end_reload_preserves_position_and_manual_stop_is_not_completed(GameTestHelper h)throws Exception{
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);
        var show=startPastNominalEnd(f);String showId=show.id;long end=show.end;
        h.runAfterDelay(16,()->{
            h.assertTrue(f.manager.playback.transport(f.hall.id,true,f.actor)==null,"pause saved programme without manual takeover");
            // Fixture-only clock position: a user seek would correctly take manual ownership.
            h.assertTrue(f.terminal.clock().seek(3000),"synthetic paused position is accepted by the real clock");
            h.runAfterDelay(12,()->{
                h.assertTrue(f.view().playheadMs==3000&&f.terminal.clock().paused(),"paused scheduled programme records its exact position before reload");
                var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
                var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
                f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
                h.runAfterDelay(12,()->{
                    h.assertTrue(f.manager.playback.active(f.hall.id)&&f.terminal.clock().paused()&&f.view().seekToken.isBlank(),"past-end restore still verifies its lease and waits paused for new Ready");
                    f.ready(true);
                    h.runAfterDelay(12,()->{
                        var restored=f.manager.state.show(showId);
                        h.assertTrue(f.view().playheadMs==3000&&f.terminal.clock().time()==3000&&f.terminal.clock().paused()&&restored.end==end,"restore keeps saved offset, pause intent and original nominal end");
                        UUID epoch=f.terminal.mediaEpoch();
                        h.assertTrue(f.manager.playback.stopScheduled(f.hall.id,UUID.randomUUID().toString())==null&&epoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().paused()&&f.manager.playback.active(f.hall.id),"retired show claim resolves without stopping the current programme");
                        PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.STOP);
                        h.assertTrue(restored.status==ManagerState.Status.CANCELLED&&f.view().playheadMs==3000&&restored.end==end,"manual partial stop is cancelled, never a completed full film");
                        f.load();
                        h.runAfterDelay(12,()->{
                            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PLAY);
                            var hall=f.manager.state.hall(f.hall.id);String title=hall.current;int[] lights=hall.activeLights.clone();
                            UUID replacementEpoch=f.terminal.mediaEpoch();long position=f.terminal.clock().time();
                            restored.title="Retired programme claim";restored.status=ManagerState.Status.STOP_PENDING;
                            f.manager.state.tick(System.currentTimeMillis(),new ManagerState.Executor(){
                                public String start(ManagerState.Hall ignored,ManagerState.Show selected){throw new AssertionError("no new start is due");}
                                public String stop(ManagerState.Hall ignored){throw new AssertionError("film cleanup cannot use broad hall stop");}
                                public String stop(ManagerState.Hall selected,ManagerState.Show old){return f.manager.playback.stopScheduled(selected.id,old.id);}
                            });
                            h.assertTrue(restored.status==ManagerState.Status.CANCELLED&&f.manager.playback.active(hall.id)&&f.terminal.clock().playing(),"retired STOP_PENDING resolves without stopping newer manual programme");
                            h.assertTrue(replacementEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().time()>=position&&hall.current.equals(title)&&Arrays.equals(hall.activeLights,lights),"old stop preserves replacement clock, title and lighting");
                            h.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=700)
    public static void film_preparing_timeout_after_nominal_end_is_failed_not_completed(GameTestHelper h)throws Exception{
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(false);
        var show=startPastNominalEnd(f);long end=show.end;
        h.runAfterDelay(630,()->{
            h.assertTrue(show.status==ManagerState.Status.FAILED&&!f.manager.playback.active(f.hall.id),"bounded Ready timeout fails even after nominal end; it never claims completion");
            h.assertTrue(f.view().playheadMs==0&&show.end==end&&show.outcome.contains("timeout"),"failure preserves zero consumed film time and nominal timing with explicit reason");
            h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void film_stop_requested_before_first_restore_tick_never_replays(GameTestHelper h)throws Exception{
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);
        var show=startPastNominalEnd(f);String id=show.id;long end=show.end;
        h.runAfterDelay(16,()->stopBeforeFirstRestoreTick(f,id,end));
    }

    private static void stopBeforeFirstRestoreTick(FilmPlaybackGameTests.Fixture f,String id,long end){
            var h=f.h;long remainder=h.getLevel().getGameTime()%5;
            if(remainder!=0){h.runAfterDelay(5-remainder,()->stopBeforeFirstRestoreTick(f,id,end));return;}
            h.assertTrue(f.manager.playback.transport(f.hall.id,true,f.actor)==null,"fixture is paused before saving the active claim");
            var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
            var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
            f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
            UUID epoch=f.terminal.mediaEpoch();
            f.manager.suspend("mycinema.catalog.programme_cancelled");
            h.assertTrue(f.manager.state.show(id).status==ManagerState.Status.STOP_PENDING,"stop before the first restore tick is retained rather than treated as a completed no-op");
            // Force manager-before-terminal ordering at an eligible playback tick.
            f.manager.playback.tick();
            h.assertTrue(f.terminal.clock()==null&&epoch.equals(f.terminal.mediaEpoch())
                    &&f.manager.state.show(id).status==ManagerState.Status.STOP_PENDING&&f.manager.playback.active(f.hall.id),
                    "cold exact stop remains pending without preparing or falsely acknowledging cancellation: "+stopDiagnostics(f,id));
            h.assertTrue(f.manager.playback.stopScheduled(f.hall.id,id)!=null
                    &&f.manager.state.show(id).status==ManagerState.Status.STOP_PENDING&&f.terminal.clock()==null,
                    "scheduler retry also waits for the exact cold clock: "+stopDiagnostics(f,id));
            h.runAfterDelay(12,()->{
                var stopped=f.manager.state.show(id);
                h.assertTrue(stopped.status==ManagerState.Status.CANCELLED&&stopped.end==end&&!f.manager.playback.active(f.hall.id),"restored exact stop resolves as cancellation without changing the nominal end: "+stopDiagnostics(f,id));
                h.assertTrue(f.terminal.clock()!=null&&f.terminal.clock().stopped()&&epoch.equals(f.terminal.mediaEpoch()),"pending stop never prepares a new source or waits for Ready to replay it: "+stopDiagnostics(f,id));
                h.succeed();
            });
    }

    private static String stopDiagnostics(FilmPlaybackGameTests.Fixture f,String id){
        var show=f.manager.state.show(id);
        return "show="+show.status+", outcome="+show.outcome+", end="+show.end
                +", active="+f.manager.playback.active(f.hall.id)+", epoch="+f.terminal.mediaEpoch()
                +", clock="+(f.terminal.clock()==null?"absent":f.terminal.clock().snapshot());
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void film_manual_cold_stop_survives_pending_save_reload_without_replay(GameTestHelper h)throws Exception{
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->manualColdStopAcrossReload(f));
    }

    private static void manualColdStopAcrossReload(FilmPlaybackGameTests.Fixture f){
        var h=f.h;long remainder=h.getLevel().getGameTime()%5;
        if(remainder!=0){h.runAfterDelay(5-remainder,()->manualColdStopAcrossReload(f));return;}
        var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
        var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
        f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());
        f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
        UUID firstColdEpoch=f.terminal.mediaEpoch();
        h.assertTrue(f.manager.state.shows.isEmpty(),"manual cold-stop fixture has no scheduled stop intent");
        h.assertTrue(f.manager.playback.stop(f.hall.id,f.actor)!=null&&f.terminal.clock()==null
                &&f.manager.playback.active(f.hall.id),"manual stop before the first restore tick waits for actual Halt acknowledgement");
        f.manager.playback.tick();
        h.assertTrue(firstColdEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock()==null,
                "a later tick retains manual stop intent without preparing a source");
        // Persist while no clock exists and the stop has not been acknowledged.
        var pendingManager=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
        var pendingTerminal=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
        f.terminal.loadWithComponents(pendingTerminal,h.getLevel().registryAccess());
        f.manager.loadWithComponents(pendingManager,h.getLevel().registryAccess());
        UUID secondColdEpoch=f.terminal.mediaEpoch();
        h.assertTrue(!firstColdEpoch.equals(secondColdEpoch),"second real disk reload creates a distinct cold epoch");
        f.manager.playback.tick();
        h.assertTrue(secondColdEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock()==null
                &&f.manager.playback.active(f.hall.id),"saved manual stop stays pending after reload without Prepare or Resume");
        h.runAfterDelay(12,()->{
            var view=f.view();
            String observed="device="+view.deviceState+", issue="+view.issue+", epoch="+f.terminal.mediaEpoch()
                    +", clock="+(f.terminal.clock()==null?"absent":f.terminal.clock().snapshot());
            h.assertTrue(!f.manager.playback.active(f.hall.id)&&view.deviceState.equals("mycinema.catalog.programme_stopped")
                    &&f.terminal.clock()!=null&&f.terminal.clock().stopped()&&secondColdEpoch.equals(f.terminal.mediaEpoch()),
                    "persisted manual cold stop ends only through an exact Stop ACK, without Ready or replay: "+observed);
            h.succeed();
        });
    }
}
