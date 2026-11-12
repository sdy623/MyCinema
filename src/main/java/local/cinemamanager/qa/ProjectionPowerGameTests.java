package local.cinemamanager.qa;

import local.cinemamanager.library.ProjectionPower;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Server state/NBT only; actual render suppression and Iris light removal need client proof. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ProjectionPowerGameTests {
    @GameTest(template="empty",timeoutTicks=100)
    public static void projection_stop_pause_reload_next_show(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,2);f.authorize();f.ready(true);f.load();
        var links=f.terminal.projectors();var catalog=f.manager.library.catalog().revision();
        float distance=f.first.data.projectionDistance,offset=f.second.projectionOffsetX;
        h.runAfterDelay(16,()->{
            h.assertTrue(f.first.data.active&&f.second.data.active,"ordinary old/default projectors start enabled");
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PAUSE);
            h.assertTrue(f.first.data.active&&f.second.data.active&&f.terminal.clock().paused(),"Pause retains both outputs");
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.NEXT);
            h.assertTrue(f.first.data.active&&f.second.data.active&&f.terminal.clock().stopped()
                    &&PlaylistTransitionGameTests.view(f).status().equals("GAP"),"transition Halt retains both powered black-cue outputs");
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.STOP);
            h.assertTrue(f.terminal.clock().stopped()&&!f.first.data.active&&!f.second.data.active,"exact Stop ACK includes both output switches off");
            h.assertTrue(f.manager.library.catalog().revision()==catalog&&f.terminal.projectors().equals(links)
                    &&f.first.data.projectionDistance==distance&&f.second.projectionOffsetX==offset,"Stop preserves pairing calibration and film catalog");
            var saved=f.first.saveWithoutMetadata(h.getLevel().registryAccess());f.first.loadWithComponents(saved,h.getLevel().registryAccess());
            h.assertTrue(!f.first.data.active&&f.first.terminal()==f.terminal,"output off persists across projector NBT reload");
            f.load();
            h.runAfterDelay(16,()->{
                h.assertTrue(f.first.data.active&&f.second.data.active,"next verified programme Resume explicitly enables both outputs");
                f.manager.scene(f.actor,f.hall.id,ManagerState.Scene.CLOSE);
                h.assertTrue(f.terminal.clock().stopped()&&!f.first.data.active&&!f.second.data.active,"Exit also stops playback and disables both outputs");
                f.pair();h.succeed();
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=60)
    public static void projection_pair_guards_are_atomic(GameTestHelper h)throws Exception {
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,2);f.authorize();f.ready(true);f.load();
        h.runAfterDelay(16,()->{
            UUID epoch=f.terminal.mediaEpoch();
            h.assertTrue(ProjectionPower.apply(f.manager,f.terminal,null,UUID.randomUUID(),false)!=null
                    &&f.first.data.active&&f.second.data.active,"stale source epoch mutates neither projector");
            f.second.owner(UUID.randomUUID());
            h.assertTrue(ProjectionPower.apply(f.manager,f.terminal,null,epoch,false)!=null
                    &&f.first.data.active&&f.second.data.active,"foreign second owner rejects whole pair before any write");
            f.second.owner(f.actor.getUUID());f.second.bind(null);
            h.assertTrue(ProjectionPower.apply(f.manager,f.terminal,null,epoch,false)!=null
                    &&f.first.data.active&&f.second.data.active,"broken reciprocal binding cannot power off first member");
            f.second.bind(f.terminal);f.terminal.revokeAutomation();
            h.assertTrue(ProjectionPower.apply(f.manager,f.terminal,null,epoch,false)!=null
                    &&f.first.data.active&&f.second.data.active,"revoked controller grant rejects output mutation");
            h.succeed();
        });
    }
}
