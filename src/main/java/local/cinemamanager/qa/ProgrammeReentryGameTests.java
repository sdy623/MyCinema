package local.cinemamanager.qa;

import local.cinemamanager.EnrollmentRegistry;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.theater.CinemaProgramme;
import local.cinemamanager.media.CinemaSources;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Collections;
import java.util.UUID;

/**
 * Real server BE removal/replacement, disk NBT, clocks, enrollment and authority checks.
 * These are explicitly NOT whole-chunk unload tests: the GameTest structure keeps its chunk
 * loaded. Evidence and Ready use FilmPlaybackGameTests' synthetic fixture; these tests do not
 * authenticate a viewer or prove decoding, rendered black frames, audio or client reentry.
 */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ProgrammeReentryGameTests {
    private static final class Reentry {
        final FilmPlaybackGameTests.Fixture f;
        FilmPlaybackGameTests.SimulatedViewerTerminal terminal;
        Reentry(GameTestHelper h,int count)throws Exception {
            this(new FilmPlaybackGameTests.Fixture(h,true,6000,count));
        }
        Reentry(FilmPlaybackGameTests.Fixture fixture) {
            f=fixture;terminal=(FilmPlaybackGameTests.SimulatedViewerTerminal)f.terminal;
        }
        void start(){f.authorize();terminal.ready=true;f.load();}
        CinemaProgramme.View view(){
            var view=f.manager.playback.terminalView(terminal);
            f.h.assertTrue(view!=null,"current exact replacement lease exposes programme view");return view;
        }
        void control(CinemaProgramme.Action action){control(action,0);}
        void control(CinemaProgramme.Action action,long value){
            var view=view();String error=f.manager.playback.terminalControl(f.actor,terminal,
                    new CinemaProgramme.Request(action,view.run(),view.operationRevision(),view.currentIndex(),value));
            f.h.assertTrue(error==null,"guarded "+action+" accepted: "+error);
        }
        CompoundTag save(){return terminal.saveWithoutMetadata(f.h.getLevel().registryAccess());}
        void replace(){replace(save());}
        void replace(CompoundTag disk){
            var old=terminal;UUID oldEpoch=old.mediaEpoch();var pos=old.getBlockPos();old.ready=false;
            f.h.assertTrue(f.h.getLevel().hasChunkAt(pos),"fixture is loaded BE replacement, not whole-chunk unload");
            f.h.getLevel().removeBlockEntity(pos);
            f.h.assertTrue(old.isRemoved(),"actual removeBlockEntity marks the prior instance removed");
            terminal=new FilmPlaybackGameTests.SimulatedViewerTerminal(pos,f.h.getLevel().getBlockState(pos));
            f.h.getLevel().setBlockEntity(terminal);
            terminal.loadWithComponents(disk,f.h.getLevel().registryAccess());
            f.h.assertTrue(terminal!=old&&f.h.getLevel().getBlockEntity(pos)==terminal&&!terminal.isRemoved()
                    &&terminal.clock()==null&&!terminal.ready&&!oldEpoch.equals(terminal.mediaEpoch()),
                    "different loaded instance acquires a fresh disk epoch without a clock or inherited Ready");
        }
        void pair(){f.h.assertTrue(f.first.terminal()==terminal&&f.second.terminal()==terminal
                &&f.first.clock()==null&&f.second.clock()==null,"both reciprocal projectors use only the replacement TMS clock");}
        void held(){f.h.assertTrue(terminal.clock()!=null&&terminal.clock().paused()
                &&terminal.programmePresentationHeld()&&PlaylistTransitionGameTests.presentationHeld(f.first)
                &&PlaylistTransitionGameTests.presentationHeld(f.second)&&f.view().seekToken.isBlank(),
                "fresh epoch remains paused and both presentations held without current Ready or a seek ticket");}
    }
    private static void onManagerTick(GameTestHelper h,Runnable task){
        long remainder=h.getLevel().getGameTime()%5;
        if(remainder==0)task.run();else h.runAfterDelay(5-remainder,task);
    }

    @GameTest(template="empty",timeoutTicks=1000000)
    public static void reentry_hot_manager_preserves_duplicate_occurrence_and_immutable_snapshot(GameTestHelper h)throws Exception {
        var r=new Reentry(h,3);r.start();
        h.runAfterDelay(16,()->{
            r.control(CinemaProgramme.Action.CHOOSE,2);r.control(CinemaProgramme.Action.PLAY);
            PlaylistTransitionGameTests.await(h,System.nanoTime()+15_000_000_000L,
                    ()->r.view().currentIndex()==2&&r.view().status().equals("PLAYING"),()->{
                r.control(CinemaProgramme.Action.PAUSE);r.control(CinemaProgramme.Action.SEEK_CURRENT,1234);
                var before=r.view();var publicBefore=r.f.manager.playback.publicProgramme(r.f.hall.id);
                h.assertTrue(publicBefore!=null&&before.clips().size()==3,"third identical-source occurrence is actually active");
                r.f.manager.library.update(r.f.actor,r.f.film.id(),r.f.film.revision(),
                        new FilmCatalog.Draft("Changed registry film","",Collections.nCopies(2,r.terminal.data.getUrl())));
                r.replace();
                h.runAfterDelay(12,()->{
                    r.held();h.assertTrue(r.f.manager.playback.active(r.f.hall.id)&&r.view().run().equals(before.run())
                            &&r.view().currentIndex()==2&&r.view().clips().equals(before.clips()),
                            "hot manager keeps run identity, immutable order and duplicate index across actual instance replacement");
                    r.terminal.ready=true;
                    h.runAfterDelay(12,()->{
                        var after=r.view();var actual=r.f.manager.playback.publicProgramme(r.f.hall.id);
                        h.assertTrue(after.status().equals("PAUSED")&&after.desiredPaused()&&after.clipPositionMs()==1234
                                &&r.terminal.clock().time()==1234&&r.terminal.clock().paused()&&!r.terminal.programmePresentationHeld(),
                                "fresh Ready restores exact paused local time in the third occurrence");
                        h.assertTrue(actual!=null&&actual.publicId().equals(publicBefore.publicId())
                                &&actual.snapshot().equals(publicBefore.snapshot())&&after.title().equals(before.title()),
                                "same live run retains its public identity and original snapshot despite catalogue edits");
                        r.pair();h.succeed();
                    });
                });
            },"third occurrence did not finish its bounded real-time transition");
        });
    }

    @GameTest(template="empty",timeoutTicks=90)
    public static void reentry_play_intent_waits_for_fresh_ready(GameTestHelper h)throws Exception {
        var r=new Reentry(h,2);r.start();
        h.runAfterDelay(16,()->{
            r.control(CinemaProgramme.Action.SEEK_CURRENT,2345);r.control(CinemaProgramme.Action.PLAY);
            var before=r.view();h.assertTrue(r.terminal.clock().playing()&&!before.desiredPaused(),"source is playing before removal");
            r.replace();
            h.runAfterDelay(12,()->{
                r.held();h.assertTrue(r.view().run().equals(before.run())&&!r.view().desiredPaused()
                        &&r.view().currentIndex()==before.currentIndex(),"play intent survives without consuming a new occurrence");
                UUID heldEpoch=r.terminal.mediaEpoch();
                h.runAfterDelay(12,()->{
                    r.held();h.assertTrue(heldEpoch.equals(r.terminal.mediaEpoch()),"unready retries never repeatedly prepare a source");
                    r.terminal.ready=true;
                    h.runAfterDelay(12,()->{
                        h.assertTrue(r.view().status().equals("PLAYING")&&r.terminal.clock().playing()
                                &&r.terminal.clock().time()>=2345&&r.terminal.clock().time()<3345
                                &&heldEpoch.equals(r.terminal.mediaEpoch()),"fresh Ready resumes the saved local offset and prior play intent");
                        r.pair();h.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void reentry_cold_controller_runs_before_terminal_ready(GameTestHelper h)throws Exception {
        var r=new Reentry(h,2);r.start();
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            r.control(CinemaProgramme.Action.SEEK_CURRENT,1456);var before=r.view();
            var managerDisk=r.f.manager.saveWithoutMetadata(h.getLevel().registryAccess());r.replace();
            r.f.manager.loadWithComponents(managerDisk,h.getLevel().registryAccess());
            r.f.manager.playback.tick();
            h.assertTrue(r.f.manager.playback.active(r.f.hall.id)&&r.terminal.programmePresentationHeld()
                    &&(r.terminal.clock()==null||r.terminal.clock().paused())&&r.f.view().seekToken.isBlank(),
                    "controller's first restore tick before TMS initialization cannot reveal or play the source");
            h.runAfterDelay(12,()->{
                r.held();r.terminal.ready=true;
                h.runAfterDelay(12,()->{
                    h.assertTrue(r.view().run().equals(before.run())&&r.view().status().equals("PAUSED")
                            &&r.view().clipPositionMs()==1456&&r.terminal.clock().time()==1456,
                            "controller-first disk restoration preserves run identity and paused position after Ready");
                    r.pair();h.succeed();
                });
            });
        }));
    }

    @GameTest(template="empty",timeoutTicks=75)
    public static void reentry_paused_gap_keeps_target_zero_and_full_remaining_time(GameTestHelper h)throws Exception {
        var r=new Reentry(h,3);r.start();
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            // During GAP, PREVIOUS is relative to the pending target (2 -> 1).
            // Explicitly select zero while already in GAP to retain a full transition to zero.
            r.control(CinemaProgramme.Action.CHOOSE,2);r.control(CinemaProgramme.Action.CHOOSE,0);
            var before=r.view();h.assertTrue(before.status().equals("GAP")&&before.pendingIndex()==0
                    &&before.gapRemainingMs()==3000&&before.desiredPaused(),"outgoing paused gap targets occurrence zero");
            r.replace();UUID coldEpoch=r.terminal.mediaEpoch();r.f.manager.playback.tick();
            h.assertTrue(r.terminal.clock()==null&&coldEpoch.equals(r.terminal.mediaEpoch())&&r.view().status().equals("GAP"),
                    "cold gap reasserts its old-source Halt without preparing the next occurrence");
            r.terminal.tick(r.terminal.getBlockState());r.f.manager.playback.tick();
            h.runAfterDelay(12,()->{
                var after=r.view();h.assertTrue(after.run().equals(before.run())&&after.status().equals("GAP")
                        &&after.pendingIndex()==0&&after.gapRemainingMs()==3000&&after.desiredPaused()
                        &&r.terminal.clock().stopped()&&coldEpoch.equals(r.terminal.mediaEpoch())
                        &&PlaylistTransitionGameTests.presentationHeld(r.f.first)&&PlaylistTransitionGameTests.presentationHeld(r.f.second),
                        "reentry retains target zero and every remaining paused gap millisecond without source advancement");
                r.pair();h.succeed();
            });
        }));
    }

    @GameTest(template="empty",timeoutTicks=1000000)
    public static void reentry_playing_gap_reuses_unconsumed_time_and_play_intent(GameTestHelper h)throws Exception {
        var r=new Reentry(h,3);r.start();
        h.runAfterDelay(16,()->{
            r.control(CinemaProgramme.Action.CHOOSE,2);r.control(CinemaProgramme.Action.PLAY);
            PlaylistTransitionGameTests.await(h,System.nanoTime()+15_000_000_000L,
                    ()->r.view().status().equals("GAP")&&r.view().gapRemainingMs()<2400,()->onManagerTick(h,()->{
                var before=r.view();h.assertTrue(before.gapRemainingMs()>0&&!before.desiredPaused(),"a real partially consumed playing gap exists");
                r.replace();UUID coldEpoch=r.terminal.mediaEpoch();r.f.manager.playback.tick();
                h.assertTrue(r.view().status().equals("GAP")&&r.view().gapRemainingMs()==before.gapRemainingMs()
                        &&r.view().pendingIndex()==2&&!r.view().desiredPaused()&&coldEpoch.equals(r.terminal.mediaEpoch()),
                        "first replacement tick neither resets nor burns the saved gap and preserves its play intent");
                r.terminal.tick(r.terminal.getBlockState());r.f.manager.playback.tick();
                h.assertTrue(r.terminal.clock().stopped()&&r.view().gapRemainingMs()==before.gapRemainingMs(),
                        "matching cold Halt acknowledges Stop before gap time can advance");
                PlaylistTransitionGameTests.await(h,System.nanoTime()+15_000_000_000L,()->r.view().currentIndex()==2&&r.terminal.clock()!=null,()->{
                    r.held();h.assertTrue(!r.view().desiredPaused()&&!coldEpoch.equals(r.terminal.mediaEpoch()),
                            "only completion of the surviving gap prepares the chosen new occurrence, still awaiting Ready");
                    r.terminal.ready=true;
                    h.runAfterDelay(12,()->{
                        h.assertTrue(r.view().run().equals(before.run())&&r.view().status().equals("PLAYING")
                                &&r.terminal.clock().playing(),"chosen occurrence resumes with the original run and play intent");
                        r.pair();h.succeed();
                    });
                },"remaining gap did not complete within the bounded wall-time deadline");
            }),"playing gap did not consume its first six hundred milliseconds");
        });
    }

    private enum Guard { IDENTITY, SOURCE, GRANT, CONTROLLER, LEASE, EVIDENCE, OWNER, ENROLLMENT }
    private static void rejectedReplacement(GameTestHelper h,Guard guard)throws Exception {
        var r=new Reentry(h,2);r.start();
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            var disk=r.save();
            switch(guard){
                case IDENTITY->disk.putUUID("terminal_id",UUID.randomUUID());
                case SOURCE->{String source="water://reentry-replacement-"+UUID.randomUUID();disk.putString("url",source);
                    disk.getCompound("library_source").putString("source",source+"#0");disk.putString("theater_media_key",source+"#0");}
                case GRANT->disk.getCompound("theater_automation").putUUID("controller",UUID.randomUUID());
                case CONTROLLER->disk.getCompound("library_source").putUUID("controller",UUID.randomUUID());
                case LEASE->disk.remove("library_source");
                case EVIDENCE->disk.getCompound("library_source").putUUID("evidence",UUID.randomUUID());
                default->{}
            }
            r.replace(disk);
            if(guard==Guard.OWNER)r.terminal.owner(UUID.randomUUID());
            if(guard==Guard.ENROLLMENT)EnrollmentRegistry.get(h.getLevel()).release(r.terminal.identity(),r.f.manager.identity);
            assertRejectedWithoutCommands(r,guard.name());
        }));
    }
    private static void assertRejectedWithoutCommands(Reentry r,String guard){
        var h=r.f.h;UUID epoch=r.terminal.mediaEpoch();String source=r.terminal.data.getUrl();
        UUID evidence=r.terminal.sourceEvidenceId();var grant=r.terminal.automationGrant();var links=r.terminal.projectors();
        var controller=new CinemaSources.ControllerRef(r.f.manager.getBlockPos(),r.f.manager.identity);
        boolean managed=r.terminal.libraryManaged(),controlled=CinemaSources.controlledBy(r.terminal,controller);
        boolean first=r.f.first.data.active,second=r.f.second.data.active,paused=r.terminal.data.paused;
        r.f.manager.playback.tick();r.f.manager.playback.tick();
        h.assertTrue(!r.f.manager.playback.active(r.f.hall.id)&&r.f.manager.playback.publicProgramme(r.f.hall.id)==null,
                guard+" mismatch rejects recovery and actual-programme publication");
        h.assertTrue(epoch.equals(r.terminal.mediaEpoch())&&java.util.Objects.equals(source,r.terminal.data.getUrl())
                &&java.util.Objects.equals(evidence,r.terminal.sourceEvidenceId())&&java.util.Objects.equals(grant,r.terminal.automationGrant())
                &&links.equals(r.terminal.projectors())&&managed==r.terminal.libraryManaged()
                &&controlled==CinemaSources.controlledBy(r.terminal,controller)&&paused==r.terminal.data.paused
                &&r.terminal.clock()==null&&r.f.first.data.active==first&&r.f.second.data.active==second,
                guard+" rejection before any terminal tick does not prepare, resume, halt, rebind or power off replacement state");
        h.runAfterDelay(12,()->{
            h.assertTrue(!r.f.manager.playback.active(r.f.hall.id)&&epoch.equals(r.terminal.mediaEpoch())
                    &&r.f.first.data.active==first&&r.f.second.data.active==second,
                    guard+" remains rejected on subsequent server ticks without acquiring replacement authority");h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_changed_terminal_uuid_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.IDENTITY);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_changed_source_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.SOURCE);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_changed_grant_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.GRANT);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_changed_controller_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.CONTROLLER);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_lost_source_lease_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.LEASE);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_changed_evidence_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.EVIDENCE);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_changed_owner_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.OWNER);}
    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_lost_enrollment_cannot_be_adopted(GameTestHelper h)throws Exception {rejectedReplacement(h,Guard.ENROLLMENT);}

    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_revoked_evidence_record_cannot_be_adopted(GameTestHelper h)throws Exception {
        var r=new Reentry(h,2);r.start();
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            r.replace();UUID evidence=r.terminal.sourceEvidenceId();
            CinemaSourceEvidence.get(h.getLevel()).forget(evidence);
            h.assertTrue(CinemaSourceEvidence.get(h.getLevel()).find(evidence)==null,"server evidence record is really revoked while the saved ID is unchanged");
            assertRejectedWithoutCommands(r,"REVOKED_EVIDENCE_RECORD");
        }));
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void reentry_revoked_local_consent_cannot_be_adopted(GameTestHelper h)throws Exception {
        var r=new Reentry(LocalProgrammeGameTests.fixture(h));LocalProgrammeGameTests.consent(r.f);r.f.load();
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            r.replace();r.f.manager.revokeLocalProgramme(r.f.actor,r.f.hall.id,true);
            assertRejectedWithoutCommands(r,"LOCAL_CONSENT");
        }));
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void reentry_manual_stop_before_first_tick_survives_another_disk_load(GameTestHelper h)throws Exception {
        var r=new Reentry(h,2);r.start();
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            r.control(CinemaProgramme.Action.PLAY);r.replace();UUID firstCold=r.terminal.mediaEpoch();
            h.assertTrue("mycinema.show.stop_pending".equals(r.f.manager.playback.stop(r.f.hall.id,r.f.actor))
                    &&r.f.manager.playback.active(r.f.hall.id)&&r.terminal.clock()==null,
                    "explicit Stop on a returned but clockless instance stays pending until an actual Halt ACK");
            r.f.manager.playback.tick();
            h.assertTrue(firstCold.equals(r.terminal.mediaEpoch())&&r.terminal.clock()==null,"queued Stop precedes Prepare and Resume");
            var managerDisk=r.f.manager.saveWithoutMetadata(h.getLevel().registryAccess());r.replace();
            r.f.manager.loadWithComponents(managerDisk,h.getLevel().registryAccess());UUID secondCold=r.terminal.mediaEpoch();
            r.f.manager.playback.tick();
            h.assertTrue(r.f.manager.playback.active(r.f.hall.id)&&r.terminal.clock()==null&&secondCold.equals(r.terminal.mediaEpoch()),
                    "persisted manual stop intent survives a second replacement without preparing media");
            h.runAfterDelay(12,()->{
                h.assertTrue(r.view().status().equals("STOPPED")&&!r.f.manager.playback.active(r.f.hall.id)
                        &&r.terminal.clock().stopped()&&secondCold.equals(r.terminal.mediaEpoch())
                        &&!r.f.first.data.active&&!r.f.second.data.active&&!r.terminal.ready,
                        "only matching Halt ACK completes pending Stop, powers off both outputs and never needs Ready");
                r.pair();h.succeed();
            });
        }));
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void reentry_schedule_cancel_before_first_tick_precedes_prepare(GameTestHelper h)throws Exception {
        var r=new Reentry(h,2);r.f.authorize();r.terminal.ready=true;
        var show=r.f.manager.library.schedule(r.f.actor,r.f.hall.id,r.f.film.id(),r.f.film.revision(),
                r.f.manager.state.revision,r.f.manager.state.inputTime(System.currentTimeMillis()+180_000),"",true);
        show.start=System.currentTimeMillis()-1;show.end=show.start+show.filmDurationMs;show.status=ManagerState.Status.PREPARING;
        h.assertTrue(r.f.manager.playback.startScheduled(r.f.hall,show)==null,"scheduled run starts with its exact immutable snapshot");
        h.runAfterDelay(16,()->onManagerTick(h,()->{
            long end=show.end;r.replace();UUID cold=r.terminal.mediaEpoch();
            String pending="";
            try{r.f.manager.cancelShow(r.f.actor,show.id,r.f.manager.state.revision,true);}
            catch(IllegalArgumentException result){pending=result.getMessage();}
            h.assertTrue("mycinema.show.stop_pending".equals(pending),"Cancel reports pending rather than successful completion");
            h.assertTrue(show.status==ManagerState.Status.STOP_PENDING&&r.terminal.clock()==null&&cold.equals(r.terminal.mediaEpoch()),
                    "explicit cancellation of a clockless returned TMS remains Stop pending, never false success");
            r.f.manager.playback.tick();
            h.assertTrue(show.status==ManagerState.Status.STOP_PENDING&&r.terminal.clock()==null&&cold.equals(r.terminal.mediaEpoch()),
                    "scheduled cancellation wins before any source preparation");
            h.runAfterDelay(12,()->{
                h.assertTrue(show.status==ManagerState.Status.CANCELLED&&show.end==end&&!r.f.manager.playback.active(r.f.hall.id)
                        &&r.terminal.clock().stopped()&&cold.equals(r.terminal.mediaEpoch())&&!r.f.first.data.active&&!r.f.second.data.active,
                        "matching Stop ACK records cancellation with unchanged nominal end and no new media epoch");
                r.pair();h.succeed();
            });
        }));
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void reentry_stopped_history_never_resurrects_or_enables_outputs(GameTestHelper h)throws Exception {
        var r=new Reentry(h,2);r.start();
        h.runAfterDelay(16,()->{
            r.control(CinemaProgramme.Action.SEEK_CURRENT,1234);r.control(CinemaProgramme.Action.STOP);
            var before=r.view();r.replace();UUID firstCold=r.terminal.mediaEpoch();
            h.runAfterDelay(12,()->{
                assertStoppedHistory(r,before,firstCold);
                var managerDisk=r.f.manager.saveWithoutMetadata(h.getLevel().registryAccess());r.replace();
                r.f.manager.loadWithComponents(managerDisk,h.getLevel().registryAccess());UUID secondCold=r.terminal.mediaEpoch();
                h.runAfterDelay(12,()->{
                    assertStoppedHistory(r,before,secondCold);r.terminal.ready=true;
                    h.runAfterDelay(12,()->{
                        h.assertTrue(r.view().status().equals("STOPPED")&&!r.f.manager.playback.active(r.f.hall.id)
                                &&secondCold.equals(r.terminal.mediaEpoch())&&!r.terminal.clock().playing()
                                &&!r.f.first.data.active&&!r.f.second.data.active,
                                "even a fresh synthetic Ready cannot revive finished history");r.pair();h.succeed();
                    });
                });
            });
        });
    }
    private static void assertStoppedHistory(Reentry r,CinemaProgramme.View before,UUID epoch){
        r.f.h.assertTrue(r.view().status().equals("STOPPED")&&r.view().run().equals(before.run())&&r.view().clipPositionMs()==1234
                &&!r.f.manager.playback.active(r.f.hall.id)&&!r.f.manager.playback.enabled(r.terminal.identity())
                &&r.f.manager.playback.publicProgramme(r.f.hall.id)==null&&epoch.equals(r.terminal.mediaEpoch())&&r.terminal.clock().paused()
                &&!r.f.first.data.active&&!r.f.second.data.active&&r.f.view().seekToken.isBlank(),
                "hot and cold controller history retains stopped position without preparation, playback, seeking or output enable");
    }
}
