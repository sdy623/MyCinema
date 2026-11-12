package local.cinemamanager.qa;

import local.cinemamanager.EnrollmentRegistry;
import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.theater.CinemaProgramme;
import local.cinemamanager.theater.CinemaProgrammeNbt;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.network.TheaterPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Actual loaded tiles, programme bridge, packet handler, WF clocks, grants and disk NBT.
 * Duration evidence and viewer Ready are explicitly synthetic fixture inputs, not decoder,
 * authenticated network-report, rendered-black-frame or audible-silence evidence.
 */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class PlaylistTransitionGameTests {
    static CinemaProgramme.View view(FilmPlaybackGameTests.Fixture f) {
        var view=f.manager.playback.terminalView(f.terminal);
        f.h.assertTrue(view!=null,"the exact loaded lease exposes its source-free programme view");
        return view;
    }
    static CinemaProgramme.Request request(FilmPlaybackGameTests.Fixture f,CinemaProgramme.Action action,long value) {
        var view=view(f);
        return new CinemaProgramme.Request(action,view.run(),view.operationRevision(),view.currentIndex(),value);
    }
    static void control(FilmPlaybackGameTests.Fixture f,CinemaProgramme.Action action,long value) {
        String error=terminalControl(f,f.actor,request(f,action,value));
        f.h.assertTrue(error==null,"guarded "+action+" accepted: "+error);
    }
    static void control(FilmPlaybackGameTests.Fixture f,CinemaProgramme.Action action) { control(f,action,0); }
    static String terminalControl(FilmPlaybackGameTests.Fixture f,ServerPlayer actor,CinemaProgramme.Request request) {
        return f.manager.playback.terminalControl(actor,f.terminal,request);
    }
    static boolean presentationHeld(CinemaProjectorTile projector) {
        var terminal=projector.terminal();
        return terminal!=null&&terminal.programmePresentationHeld();
    }
    static void await(GameTestHelper h,long deadline,BooleanSupplier condition,Runnable done,String why) {
        h.runAfterDelay(5,new Runnable(){public void run(){
            if(condition.getAsBoolean()){done.run();return;}
            h.assertTrue(System.nanoTime()<deadline,why);
            // GameTestInfo removes the executing Runnable from its identity-keyed task map
            // after this call; a fresh wrapper preserves the next poll and its deadline.
            h.runAfterDelay(5,()->this.run());
        }});
    }
    private static IPayloadContext context(ServerPlayer actor) {
        return (IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class[]{IPayloadContext.class},
                (proxy,method,args)->switch(method.getName()){
                    case "player"->actor;case "flow"->PacketFlow.SERVERBOUND;
                    default->throw new UnsupportedOperationException(method.getName());
                });
    }
    private static void stopPacket(FilmPlaybackGameTests.Fixture f) {
        TheaterPacket.handle(new TheaterPacket(f.terminal.getBlockPos(),TheaterPacket.STOP,
                CinemaProgrammeNbt.writeRequest(request(f,CinemaProgramme.Action.STOP,0))),context(f.actor));
    }
    private static FilmCatalog.Film ordered(FilmPlaybackGameTests.Fixture f,long duration,String... sources)throws Exception {
        var film=f.manager.library.create(f.actor,f.manager.library.catalog().revision(),new FilmCatalog.Draft("A B A ordered fixture","",List.of(sources)));
        for(int index=0;index<sources.length;index++){
            var proof=CinemaSourceEvidence.get(f.h.getLevel()).remember(
                    f.actor.getUUID(),f.terminal.identity(),sources[index],0,duration);
            film=f.manager.library.catalog().observeTrusted(film.id(),film.revision(),index,sources[index],false,duration,
                    new FilmCatalog.Provenance(f.actor.getUUID().toString(),f.manager.identity.toString(),
                            f.terminal.identity().toString(),sources[index],proof.id().toString()));
        }
        return film;
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void playlist_current_seek_clamps_and_operation_requests_expire(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->{
            var initial=view(f);UUID epoch=f.terminal.mediaEpoch();
            h.assertTrue(initial.clipDurationMs()==6000&&initial.totalDurationMs()==24000,"clip duration excludes other clips and both three-second transitions");
            var beforePause=request(f,CinemaProgramme.Action.CHOOSE,2);
            control(f,CinemaProgramme.Action.PAUSE);
            h.assertTrue("stale_programme".equals(terminalControl(f,f.actor,beforePause)),"a request captured before Pause is rejected even when already paused");
            var beforeSeek=request(f,CinemaProgramme.Action.NEXT,0);
            control(f,CinemaProgramme.Action.SEEK_CURRENT,9000);
            h.assertTrue("stale_programme".equals(terminalControl(f,f.actor,beforeSeek)),"a request captured before Seek is rejected");
            h.assertTrue(view(f).currentIndex()==0&&view(f).clipPositionMs()==6000&&view(f).desiredPaused()
                    &&epoch.equals(f.terminal.mediaEpoch()),"seek past current clip clamps at its end and never jumps to another clip");
            String beyond=f.view().seekToken;
            h.assertTrue(!beyond.isBlank(),"paused finite current clip offers an owner seek ticket");
            PanelGameTests.rejects(h,()->f.manager.seek(f.actor,f.hall.id,beyond,6001),"manager ticket rejects a target inside programme time but beyond current clip");
            String ticket=f.view().seekToken;
            f.manager.seek(f.actor,f.hall.id,ticket,2345);
            PanelGameTests.rejects(h,()->f.manager.seek(f.actor,f.hall.id,ticket,1200),"current-clip ticket remains one-use");
            h.runAfterDelay(12,()->{
                h.assertTrue(view(f).currentIndex()==0&&view(f).pendingIndex()==-1&&view(f).clipPositionMs()==2345
                        &&epoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().paused(),"ticket seeks the same clock in the current clip only");
                f.pair();h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=1000000)
    public static void playlist_gap_prepare_hold_immutable_aba_and_cold_occurrence(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,1);f.authorize();f.ready(false);
        String a="water://playlist-A-"+UUID.randomUUID(),b="water://playlist-B-"+UUID.randomUUID();
        var film=ordered(f,6000,a,b,a);
        f.manager.playback.load(f.actor,f.hall.id,film.id(),film.revision(),true);
        UUID firstEpoch=f.terminal.mediaEpoch();
        h.assertTrue(f.terminal.programmeManaged()&&f.terminal.programmePresentationHeld()&&f.terminal.data.active,
                "first prepare establishes programme presentation hold while the decoder may prepare");
        var stalePreparing=request(f,CinemaProgramme.Action.CHOOSE,2);
        control(f,CinemaProgramme.Action.PAUSE);
        h.assertTrue("stale_programme".equals(terminalControl(f,f.actor,stalePreparing)),"Pause during preparation invalidates an earlier request");
        h.runAfterDelay(12,()->{
            h.assertTrue(f.terminal.clock().paused()&&f.terminal.clock().time()==0&&f.terminal.programmePresentationHeld(),"evidence without Ready cannot reveal or consume initial clip time");
            f.ready(true);
            h.runAfterDelay(12,()->{
                h.assertTrue(view(f).status().equals("PAUSED")&&!f.terminal.programmePresentationHeld(),"matching current Ready and Resume acknowledgement reveal the prepared clip");
                List<CinemaProgramme.Clip> immutable=view(f).clips();UUID run=view(f).run();
                f.manager.library.update(f.actor,film.id(),film.revision(),new FilmCatalog.Draft("Edited catalogue order","",List.of(b,a)));
                h.assertTrue(view(f).clips().equals(immutable)&&view(f).title().equals("A B A ordered fixture")&&view(f).run().equals(run),"editing the registry cannot rewrite the active immutable list");
                control(f,CinemaProgramme.Action.CHOOSE,2);
                h.assertTrue(view(f).status().equals("GAP")&&view(f).currentIndex()==0&&view(f).pendingIndex()==2
                        &&view(f).gapRemainingMs()==3000&&f.terminal.clock().stopped(),"explicit selection holds the outgoing occurrence before the full three-second gap");
                h.assertTrue(presentationHeld(f.first)&&presentationHeld(f.second),"both projectors share the terminal black/silent presentation gate");
                int volume=f.terminal.data.volume;boolean muted=f.terminal.data.muted;
                h.runAfterDelay(12,()->{
                    h.assertTrue(view(f).gapRemainingMs()==3000&&firstEpoch.equals(f.terminal.mediaEpoch()),"paused gap cannot consume time or open the selected clip");
                    f.ready(false);long began=System.nanoTime();control(f,CinemaProgramme.Action.PLAY);
                    await(h,began+20_000_000_000L,()->{
                        var current=view(f);
                        h.assertTrue(f.terminal.programmePresentationHeld()&&presentationHeld(f.first)
                                &&presentationHeld(f.second),"presentation remains held throughout gap and unready prepare");
                        if(current.status().equals("GAP"))h.assertTrue(firstEpoch.equals(f.terminal.mediaEpoch())
                                &&f.terminal.clock().stopped()&&f.terminal.data.url.equals(a),"gap preserves outgoing exact source lease");
                        return current.currentIndex()==2;
                    },()->{
                        h.assertTrue(System.nanoTime()-began>=3_000_000_000L,"the adapter consumes at least three real continuously loaded seconds");
                        h.assertTrue(!firstEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.data.url.equals(a)
                                &&view(f).status().equals("PREPARING"),"repeated A is a new occurrence and epoch, still awaiting its own Ready");
                        h.assertTrue(f.terminal.data.volume==volume&&f.terminal.data.muted==muted,"black/silent hold does not overwrite user volume or mute settings");
                        h.runAfterDelay(12,()->{
                            h.assertTrue(f.terminal.clock().paused()&&f.terminal.clock().time()==0&&f.terminal.programmePresentationHeld(),"new epoch remains held until matching Ready and Resume");
                            f.ready(true);
                            h.runAfterDelay(12,()->{
                                h.assertTrue(view(f).currentIndex()==2&&view(f).status().equals("PLAYING")&&!f.terminal.programmePresentationHeld(),"matching Resume reveals the chosen third occurrence");
                                control(f,CinemaProgramme.Action.PAUSE);control(f,CinemaProgramme.Action.SEEK_CURRENT,1234);
                                var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
                                var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
                                UUID selectedEpoch=f.terminal.mediaEpoch();f.ready(false);
                                f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
                                h.runAfterDelay(12,()->{
                                    h.assertTrue(!selectedEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.programmePresentationHeld()&&f.view().seekToken.isBlank(),"cold occurrence restoration holds a fresh WF epoch without authority from old Ready");
                                    f.ready(true);
                                    h.runAfterDelay(12,()->{
                                        var restored=view(f);
                                        h.assertTrue(restored.run().equals(run)&&restored.clips().equals(immutable)&&restored.currentIndex()==2
                                                &&restored.clipPositionMs()==1234&&restored.status().equals("PAUSED")&&f.terminal.clock().time()==1234,
                                                "A-B-A persists by occurrence, preserving selected third A and paused local offset");
                                        f.pair();h.succeed();
                                    });
                                });
                            });
                        });
                    },"three-second gap did not reach its exact selected occurrence");
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void playlist_paused_gap_cold_manager_before_terminal_keeps_previous_zero(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->{
            control(f,CinemaProgramme.Action.CHOOSE,1);control(f,CinemaProgramme.Action.PREVIOUS);
            h.assertTrue(view(f).status().equals("GAP")&&view(f).pendingIndex()==0&&view(f).desiredPaused()
                    &&view(f).gapRemainingMs()==3000,"Previous selects first row while retaining the full paused gap");
            verifyClocklessGapRestore(f,2);
        });
    }

    private static void verifyClocklessGapRestore(FilmPlaybackGameTests.Fixture f,int remainingLoads) {
        var h=f.h;long remainder=h.getLevel().getGameTime()%5;
        if(remainder!=0){h.runAfterDelay(5-remainder,()->verifyClocklessGapRestore(f,remainingLoads));return;}
        var before=view(f);UUID previousEpoch=f.terminal.mediaEpoch();
        var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
        var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
        f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());
        f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
        UUID coldEpoch=f.terminal.mediaEpoch();
        h.assertTrue(f.terminal.clock()==null&&!previousEpoch.equals(coldEpoch),"true terminal disk load opens a fresh epoch without a clock");
        // Deliberately run manager first, then the normal TMS clock initialization, then manager.
        f.manager.playback.tick();
        h.assertTrue(f.terminal.clock()==null&&view(f).status().equals("GAP")&&view(f).failureCode().isEmpty()
                &&view(f).gapRemainingMs()==3000&&view(f).pendingIndex()==0&&view(f).desiredPaused(),
                "manager-first Halt waits with its exact paused gap and never accepts WAITING as Stop");
        var controller=new CinemaSources.ControllerRef(f.manager.getBlockPos(),f.manager.identity);
        h.assertTrue(CinemaSources.haltDelegated(f.terminal,UUID.randomUUID(),controller,coldEpoch).error()!=null
                &&CinemaSources.haltDelegated(f.terminal,f.actor.getUUID(),
                    new CinemaSources.ControllerRef(f.manager.getBlockPos(),UUID.randomUUID()),coldEpoch).error()!=null
                &&CinemaSources.haltDelegated(f.terminal,f.actor.getUUID(),controller,UUID.randomUUID()).error()!=null
                &&f.terminal.clock()==null,"cold initialization cannot bypass owner, controller or epoch authority");
        f.terminal.tick(f.terminal.getBlockState());
        h.assertTrue(f.terminal.theaterClockCurrent()&&f.terminal.clock().paused()
                &&f.terminal.programmePresentationHeld(),"ordinary TMS tick initializes only a held current clock");
        f.manager.playback.tick();
        h.assertTrue(f.terminal.clock().stopped()&&coldEpoch.equals(f.terminal.mediaEpoch())
                &&view(f).status().equals("GAP")&&view(f).failureCode().isEmpty(),"same authorized pending Halt stops the actual newly initialized clock");
        h.runAfterDelay(12,()->{
            var restored=view(f);
            h.assertTrue(restored.run().equals(before.run())&&restored.clips().equals(before.clips())
                    &&restored.pendingIndex()==0&&restored.gapRemainingMs()==3000&&restored.desiredPaused()
                    &&restored.status().equals("GAP")&&restored.failureCode().isEmpty()
                    &&f.terminal.clock().stopped()&&coldEpoch.equals(f.terminal.mediaEpoch())
                    &&presentationHeld(f.first)&&presentationHeld(f.second),
                    "paused cold reentry retains every row, target zero and full held gap without autoplay or elapsed burn");
            if(remainingLoads>1)verifyClocklessGapRestore(f,remainingLoads-1);
            else {f.pair();h.succeed();}
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void playlist_failed_header_replaced_only_after_successful_single_media_load(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->{
            f.terminal.revokeAutomation();
            h.runAfterDelay(12,()->{
                var hall=f.manager.state.hall(f.hall.id);
                h.assertTrue(hall.deviceState.equals("mycinema.catalog.programme_failed")&&!hall.issue.isEmpty(),
                        "revoked programme exposes its failed header and actual reason");
                String failure=hall.issue,title=hall.current;
                PanelGameTests.rejects(h,()->f.manager.media(f.actor,hall,"","Rejected replacement"),"invalid single-media load is rejected");
                h.assertTrue(hall.deviceState.equals("mycinema.catalog.programme_failed")&&hall.issue.equals(failure)
                        &&hall.current.equals(title)&&f.manager.playback.present(hall.id),"a rejected replacement cannot clear the existing programme failure");
                f.manager.media(f.actor,hall,"water://fresh-single-media-"+UUID.randomUUID(),"Replacement media");
                h.assertTrue(!f.manager.playback.present(hall.id)&&hall.programme==null&&hall.issue.isEmpty()
                        &&hall.deviceState.equals(local.cinemamanager.MessageText.key("message.m044"))
                        &&hall.durationMs==0&&hall.playheadMs==0&&hall.current.equals("Replacement media")
                        &&f.terminal.clock()==null&&f.terminal.data.paused,
                        "successful paused source replacement refreshes header and timing before another device poll");
                h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void playlist_tms_stop_preserves_cancelled_history_after_cold_epoch_rotation(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,3);f.authorize();f.ready(true);
        var show=f.manager.library.schedule(f.actor,f.hall.id,f.film.id(),f.film.revision(),f.manager.state.revision,
                f.manager.state.inputTime(System.currentTimeMillis()+180_000),"",true);
        show.start=System.currentTimeMillis()-1;show.end=show.start+show.filmDurationMs;show.status=ManagerState.Status.PREPARING;
        h.assertTrue(f.manager.playback.startScheduled(f.hall,show)==null,"exact scheduled programme starts");
        h.runAfterDelay(16,()->{
            h.assertTrue(f.manager.playback.transport(f.hall.id,true,f.actor)==null,"fixture pauses without taking over the scheduled claim");
            h.assertTrue(f.terminal.clock().seek(2345),"fixture-only clock offset preserves the scheduled claim before TMS Stop");
            UUID epoch=f.terminal.mediaEpoch();UUID evidence=f.terminal.sourceEvidenceId();var run=view(f).run();
            stopPacket(f);
            var stopped=view(f);
            h.assertTrue(stopped.status().equals("STOPPED")&&stopped.failureCode().isEmpty()
                    &&stopped.clipPositionMs()==2345&&f.terminal.clock().stopped(),"native TMS Stop routes through guarded programme stop and retains reported position: view="+stopped+" clock="+f.terminal.clock().snapshot());
            h.assertTrue(epoch.equals(f.terminal.mediaEpoch())&&evidence.equals(f.terminal.sourceEvidenceId())&&f.terminal.libraryManaged()
                    &&f.terminal.programmeManaged(),"programme Stop does not call sourceReplaced or retire its history lease");
            h.assertTrue(f.manager.state.show(show.id).status==ManagerState.Status.CANCELLED,"partial scheduled stop is cancellation, never completion");
            var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
            var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
            f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
            UUID coldEpoch=f.terminal.mediaEpoch();
            h.assertTrue(!epoch.equals(coldEpoch),"WF disk load rotates the source epoch");
            h.runAfterDelay(12,()->{
                h.assertTrue(view(f).run().equals(run)&&view(f).status().equals("STOPPED")&&view(f).clipPositionMs()==2345
                        &&f.manager.state.show(show.id).status==ManagerState.Status.CANCELLED,"cold verified history keeps stopped/cancelled summary and local position");
                h.assertTrue(!f.manager.playback.active(f.hall.id)&&!f.manager.playback.enabled(f.terminal.identity())
                        &&f.view().seekToken.isBlank()&&coldEpoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().paused(),
                        "history grants no fresh seek, prepare, playback or epoch change without Ready");
                // Alter only the persisted evidence lookup: same owner/source/grant is insufficient.
                var wrongEvidence=terminalSave.copy();wrongEvidence.getCompound("library_source").putUUID("evidence",UUID.randomUUID());
                f.terminal.loadWithComponents(wrongEvidence,h.getLevel().registryAccess());f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
                UUID unmatched=f.terminal.mediaEpoch();
                h.runAfterDelay(12,()->{
                    h.assertTrue(!f.manager.playback.present(f.hall.id)&&unmatched.equals(f.terminal.mediaEpoch())
                            &&f.terminal.clock().paused(),"cold history with a different source evidence ID is retired without commands");
                    h.assertTrue(f.manager.state.show(show.id).status==ManagerState.Status.CANCELLED,"retiring an unverifiable summary does not rewrite the historical schedule outcome");
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void playlist_local_gap_stop_keeps_exact_cleanup_authority(GameTestHelper h)throws Exception {
        var f=LocalProgrammeGameTests.fixture(h);LocalProgrammeGameTests.consent(f);f.load();
        h.runAfterDelay(16,()->{
            control(f,CinemaProgramme.Action.CHOOSE,1);
            h.assertTrue(view(f).status().equals("GAP")&&f.terminal.programmePresentationHeld(),"local programme is stopped and held during its paused gap");
            UUID epoch=f.terminal.mediaEpoch();stopPacket(f);
            var stopped=view(f);
            h.assertTrue(stopped.status().equals("STOPPED")&&stopped.failureCode().isEmpty()
                    &&epoch.equals(f.terminal.mediaEpoch())&&f.terminal.clock().stopped()&&f.terminal.programmePresentationHeld(),
                    "local-source cleanup uses the surviving exact owner/grant/epoch after active playback is disabled, with no stop_unconfirmed: view="+stopped+" clock="+f.terminal.clock().snapshot()+" epoch="+f.terminal.mediaEpoch());
            h.runAfterDelay(12,()->{
                h.assertTrue(view(f).status().equals("STOPPED")&&!f.manager.playback.active(f.hall.id),"local gap Stop remains final");h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=65)
    public static void playlist_local_tms_owner_control_does_not_require_nearby_manager(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,2,"water://far-controller-"+UUID.randomUUID(),new BlockPos(4,21,1));
        f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->{
            f.actor.setPos(f.terminal.getBlockPos().getCenter());
            h.assertTrue(f.manager.getBlockPos().distSqr(f.terminal.getBlockPos())==400&&!f.manager.controlAllowed(f.actor)
                    &&f.terminal.canControl(f.actor),"owner stands at TMS with manager twenty blocks away and ordinary manager controls unavailable");
            control(f,CinemaProgramme.Action.PLAY);control(f,CinemaProgramme.Action.PAUSE);
            var request=request(f,CinemaProgramme.Action.SEEK_CURRENT,1000);UUID epoch=f.terminal.mediaEpoch();
            ServerPlayer guest=h.makeMockServerPlayerInLevel();guest.setPos(f.actor.position());
            h.assertTrue(terminalControl(f,guest,request)!=null,"another nearby player cannot inherit the owner lease");
            UUID owner=f.manager.owner;f.manager.owner=UUID.randomUUID();
            h.assertTrue(terminalControl(f,f.actor,request)!=null,"TMS ownership alone cannot control a different manager owner");f.manager.owner=owner;
            UUID identity=f.manager.identity;f.manager.identity=UUID.randomUUID();
            h.assertTrue(terminalControl(f,f.actor,request)!=null,"another controller identity cannot use the saved source lease");f.manager.identity=identity;
            var enrollments=EnrollmentRegistry.get(h.getLevel());enrollments.release(f.terminal.identity(),identity);
            h.assertTrue(terminalControl(f,f.actor,request)!=null,"current enrollment is required in addition to a saved grant");
            h.assertTrue(enrollments.claim(f.terminal.identity(),identity),"fixture restores its original enrollment");
            h.assertTrue(terminalControl(f,f.actor,request)==null&&view(f).clipPositionMs()==1000,
                    "same owner controls locally only after all live authority checks pass");
            var current=request(f,CinemaProgramme.Action.PLAY,0);f.terminal.revokeAutomation();
            h.assertTrue(terminalControl(f,f.actor,current)!=null&&f.terminal.clock().paused()
                    &&epoch.equals(f.terminal.mediaEpoch()),"revoked grant cannot play or replace its paused source");
            f.terminal.owner(guest.getUUID());
            h.assertTrue(terminalControl(f,f.actor,current)!=null,"former TMS owner cannot retain local programme authority");
            h.succeed();
        });
    }
}
