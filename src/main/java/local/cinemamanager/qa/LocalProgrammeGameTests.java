package local.cinemamanager.qa;

import local.cinemamanager.catalog.FilmCatalog;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Synthetic files/evidence/Ready, actual loaded authority, OP list, world clocks and NBT. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class LocalProgrammeGameTests {
    static FilmPlaybackGameTests.Fixture fixture(GameTestHelper h)throws Exception{
        var f=new FilmPlaybackGameTests.Fixture(h,true,6000,2,"file:/qa/local-programme-"+UUID.randomUUID()+".mkv");
        h.assertTrue(!CinemaPermissions.isWhitelisted(f.film.segments().getFirst().sourceKey()),"domain whitelist stays enabled and excludes local paths");
        f.ready(true);f.authorize();return f;
    }
    static void consent(FilmPlaybackGameTests.Fixture f){f.manager.authorizeLocalProgramme(f.actor,f.hall.id,f.film.id(),f.film.revision(),true);}
    @GameTest(template="empty",timeoutTicks=75)
    public static void local_programme_requires_explicit_exact_film_consent(GameTestHelper h)throws Exception{
        var f=fixture(h);int snapshots=f.manager.library.catalog().snapshots().size();PanelGameTests.rejects(h,f::load,"ordinary programme grant cannot imply local consent");
        h.assertTrue(f.manager.library.catalog().snapshots().size()==snapshots,"denied load consumes no snapshot capacity");
        h.assertTrue(f.terminal.data.getUrl()==null&&!f.manager.playback.active(f.hall.id),"denial leaves original source and no active candidate");
        PanelGameTests.rejects(h,()->f.manager.authorizeLocalProgramme(f.actor,f.hall.id,f.film.id(),f.film.revision(),false),"explicit confirmation required");
        consent(f);f.load();h.runAfterDelay(16,()->{
            h.assertTrue(f.terminal.clock().paused()&&f.view().durationMs==15000,"exact local film loads paused with both clips and its full three-second gap");
            h.assertFalse(f.manager.theaterLocalSourcePlaybackAllowed(f.terminal.identity(),"file:/qa/not-consented.mkv"),"different URL rejected");
            h.assertFalse(f.manager.theaterLocalSourcePlaybackAllowed(UUID.randomUUID(),f.film.segments().getFirst().sourceKey()),"different TMS rejected");f.pair();h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=85)
    public static void local_programme_consent_revocation_stops_active_source(GameTestHelper h)throws Exception{
        var f=fixture(h);consent(f);f.load();h.runAfterDelay(16,()->{
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PLAY);h.assertTrue(f.terminal.clock().playing(),"actual authorized clock starts");
            f.manager.revokeLocalProgramme(f.actor,f.hall.id,true);
            h.runAfterDelay(10,()->{h.assertTrue(!f.terminal.clock().playing()&&f.terminal.data.paused,"revoke fails closed in actual clock");
                PanelGameTests.rejects(h,f::load,"revoked consent cannot silently reappear");h.succeed();});
        });
    }
    @GameTest(template="empty",timeoutTicks=85)
    public static void local_programme_deop_revokes_current_qualification(GameTestHelper h)throws Exception{
        var f=fixture(h);consent(f);f.load();h.runAfterDelay(16,()->{
            PlaylistTransitionGameTests.control(f,CinemaProgramme.Action.PLAY);h.assertTrue(f.terminal.clock().playing(),"actual authorized clock starts");
            h.getLevel().getServer().getPlayerList().deop(f.actor.getGameProfile());
            h.runAfterDelay(10,()->{h.assertTrue(!f.terminal.clock().playing()&&f.terminal.data.paused,"current OP loss pauses, no historical admin bypass");h.succeed();});
        });
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void local_programme_consent_reload_and_revision_isolation(GameTestHelper h)throws Exception{
        var f=fixture(h);consent(f);f.load();h.runAfterDelay(16,()->{
            var saved=f.manager.saveWithoutMetadata(h.getLevel().registryAccess());f.manager.loadWithComponents(saved,h.getLevel().registryAccess());
            h.runAfterDelay(16,()->{
                h.assertTrue(f.terminal.clock().paused()&&f.view().durationMs==15000,"loaded consent retains exact paused programme including its three-second gap");
                var oldClock=f.terminal.clock();var oldEpoch=f.terminal.mediaEpoch();
                var revised=f.manager.library.update(f.actor,f.film.id(),f.film.revision(),new FilmCatalog.Draft("Revised local film","",f.film.segments().stream().map(FilmCatalog.Segment::sourceKey).toList()));
                PanelGameTests.rejects(h,()->f.manager.playback.load(f.actor,f.hall.id,revised.id(),revised.revision(),true),"film edit requires a new reviewed revision consent");
                h.assertTrue(oldClock==f.terminal.clock()&&oldEpoch.equals(f.terminal.mediaEpoch())&&f.manager.playback.active(f.hall.id),"failed candidate rollback preserves original immutable programme");h.succeed();
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=50)
    public static void local_film_plan_arm_checks_inactive_snapshot_scope(GameTestHelper h)throws Exception{
        var f=fixture(h);
        String time=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC).format(Instant.now().plusSeconds(180));
        var show=f.manager.library.schedule(f.actor,f.hall.id,f.film.id(),f.film.revision(),f.manager.state.revision,time,"",true);
        PanelGameTests.rejects(h,()->f.manager.armShow(f.actor,show.id,f.manager.state.revision,true),"inactive plan cannot arm without local source consent");
        consent(f);f.manager.armShow(f.actor,show.id,f.manager.state.revision,true);
        h.assertTrue(f.manager.state.schedulerEnabled&&!f.manager.state.hall(f.hall.id).override,"exact future local plan armed without preparing media");
        h.assertTrue(f.terminal.data.getUrl()==null,"arming never opens the source early");
        f.manager.revokeLocalProgramme(f.actor,f.hall.id,true);
        PanelGameTests.rejects(h,()->f.manager.armShow(f.actor,show.id,f.manager.state.revision,true),"revoked inactive consent no longer arms");h.succeed();
    }
}
