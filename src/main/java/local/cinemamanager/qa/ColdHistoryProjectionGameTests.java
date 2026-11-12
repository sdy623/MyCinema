package local.cinemamanager.qa;

import local.cinemamanager.model.ManagerState;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Actual tile disk loads rotate the epoch; evidence/Ready remain explicitly synthetic fixtures. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ColdHistoryProjectionGameTests {
    private static void completedReload(FilmPlaybackGameTests.Fixture f,Runnable done){
        var h=f.h;f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->{
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PLAY);
            h.assertTrue(f.terminal.clock().seek(5999),"fixture moves real clock near natural completion");
            PlaylistTransitionGameTests.await(h,System.nanoTime()+20_000_000_000L,
                    ()->"COMPLETED".equals(PlaylistTransitionGameTests.view(f).status()),()->{
                h.assertTrue(f.first.data.active&&f.second.data.active,"natural completion preserves outputs before explicit Stop");
                UUID old=f.terminal.mediaEpoch();
                var terminalSave=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
                var managerSave=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
                f.ready(false);f.terminal.loadWithComponents(terminalSave,h.getLevel().registryAccess());
                f.manager.loadWithComponents(managerSave,h.getLevel().registryAccess());
                h.assertTrue(!old.equals(f.terminal.mediaEpoch()),"actual TMS disk reload creates a new epoch");
                h.runAfterDelay(12,()->{
                    h.assertTrue("COMPLETED".equals(PlaylistTransitionGameTests.view(f).status())
                            &&f.first.data.active&&f.second.data.active,"read-only cold history does not itself disable outputs");
                    done.run();
                });
            },"natural completion did not become visible");
        });
    }
    @GameTest(template="empty",timeoutTicks=1000000)
    public static void cold_completed_explicit_stop_and_close_use_current_user_authority(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,1);
        completedReload(f,()->{
            UUID epoch=f.terminal.mediaEpoch();var clock=f.terminal.clock();var links=f.terminal.projectors();
            h.assertTrue(f.manager.playback.stop(f.hall.id,null)!=null&&f.first.data.active&&f.second.data.active,
                    "a scheduler/null actor cannot promote observed history into output authority");
            var request=PlaylistTransitionGameTests.request(f,CinemaProgramme.Action.STOP,0);
            f.manager.playback.control(f.actor,f.hall.id,request);
            h.assertTrue(!f.first.data.active&&!f.second.data.active&&epoch.equals(f.terminal.mediaEpoch())
                    &&f.terminal.clock()==clock&&f.terminal.projectors().equals(links),"explicit manager Stop closes exact current cold outputs without rebinding history or clock");
            // Fixture-only reset tests the separate hall Close entry against the same cold history.
            f.first.data.active=true;f.second.data.active=true;
            f.manager.scene(f.actor,f.hall.id,ManagerState.Scene.CLOSE);
            h.assertTrue(!f.first.data.active&&!f.second.data.active,"explicit hall Close also closes verified cold historical outputs");
            f.first.data.active=true;f.second.data.active=true;
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.STOP);
            h.assertTrue(!f.first.data.active&&!f.second.data.active,"local TMS Stop retains its separate current owner authority path");
            f.pair();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=1000000)
    public static void cold_history_stop_rechecks_source_owner_grant_and_epoch(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,1);
        completedReload(f,()->{
            var request=PlaylistTransitionGameTests.request(f,CinemaProgramme.Action.STOP,0);
            String original=f.terminal.data.url;f.terminal.data.url="water://replacement-history-source";
            h.assertTrue("mycinema.catalog.source_stale_session".equals(f.manager.playback.stop(f.hall.id,f.actor)),
                    "hall Close path rejects a missing current epoch before model Session construction");
            PanelGameTests.rejects(h,()->f.manager.playback.control(f.actor,f.hall.id,request),"changed source cannot borrow historical output authority");
            h.assertTrue(f.first.data.active&&f.second.data.active,"source refusal changes neither output");f.terminal.data.url=original;
            UUID owner=f.manager.owner;f.manager.owner=UUID.randomUUID();
            PanelGameTests.rejects(h,()->f.manager.playback.control(f.actor,f.hall.id,request),"changed owner rejects historical Stop");
            h.assertTrue(f.first.data.active&&f.second.data.active,"owner refusal changes neither output");f.manager.owner=owner;
            var saved=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
            f.terminal.revokeAutomation();
            PanelGameTests.rejects(h,()->f.manager.playback.control(f.actor,f.hall.id,request),"revoked exact grant rejects historical Stop");
            h.assertTrue(f.first.data.active&&f.second.data.active,"grant refusal changes neither output");
            // A second load retains the same source/evidence but rotates beyond the observed epoch.
            f.terminal.loadWithComponents(saved,h.getLevel().registryAccess());
            PanelGameTests.rejects(h,()->f.manager.playback.control(f.actor,f.hall.id,request),"same-source new epoch cannot reuse previously observed history");
            f.terminal.cancelLibraryControl();
            PanelGameTests.rejects(h,()->f.manager.playback.control(f.actor,f.hall.id,request),"lost current source lease rejects historical Stop");
            h.assertTrue(f.first.data.active&&f.second.data.active,"new epoch and lost lease never power off replacement outputs");h.succeed();
        });
    }
}
