package local.cinemamanager.qa;
import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ScheduleDisplayGameTests {
    private record Fixture(ManagerTile manager,ServerPlayer owner,CinemaTerminalTile terminal,ManagerState.Hall hall){}
    private static Fixture fixture(GameTestHelper h){
        var actor=PanelGameTests.actor(h);h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile m=h.getBlockEntity(new BlockPos(1,1,1));m.owner=actor.getUUID();m.state.timezone="Asia/Tokyo";
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=h.getBlockEntity(new BlockPos(4,1,1));t.owner(actor.getUUID());
        h.setBlock(new BlockPos(4,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile projector=h.getBlockEntity(new BlockPos(4,1,4));projector.owner(actor.getUUID());t.bindNearby(actor);
        return new Fixture(m,actor,t,m.enroll(actor,t,true));
    }
    private static ManagerState.Show plan(Fixture f){long now=System.currentTimeMillis();return f.manager.state.addShow(f.hall.id,"Human movie title","water://scheduled-display-fixture",f.manager.state.inputTime((now/60000+10)*60000),2,now);}
    @GameTest(template="empty",timeoutTicks=100)
    public static void inspection_then_explicit_arm_starts_single_clock_without_changing_other_hall(GameTestHelper h){
        Fixture f=fixture(h);var s=plan(f);var other=new ManagerState.Hall();other.name="Other";other.terminalIdentity=UUID.randomUUID().toString();other.override=true;f.manager.state.halls.add(other);
        f.manager.loadShow(f.owner,s.id,f.manager.state.revision);h.assertTrue(f.hall.override&&f.terminal.data.paused&&!f.manager.state.schedulerEnabled,"inspection explicitly loads paused and manual");
        String source=f.terminal.data.url;long revision=f.manager.state.revision;
        f.manager.armShow(f.owner,s.id,revision,true);
        h.assertTrue(f.manager.state.schedulerEnabled&&!f.hall.override&&other.override,"explicit arming releases only selected hall override");
        h.assertTrue(s.status==ManagerState.Status.PENDING&&f.terminal.data.paused&&f.terminal.data.url.equals(source),"arming itself neither starts nor reloads media");
        PanelGameTests.rejects(h,()->f.manager.armShow(f.owner,s.id,revision,true),"stale arm replay refused");
        var saved=f.manager.saveWithFullMetadata(h.getLevel().registryAccess());ManagerTile copy=new ManagerTile(f.manager.getBlockPos(),f.manager.getBlockState());copy.loadWithComponents(saved,h.getLevel().registryAccess());
        h.assertTrue(copy.state.schedulerEnabled&&!copy.state.hall(f.hall.id).override&&copy.state.hall(other.id).override,"armed mode persists without changing other hall");
        h.assertTrue(f.terminal.projectors().size()==1&&f.terminal.playbackAvailable(),"one bound projector is supported by the existing WF playback API");
        var output=(CinemaProjectorTile)h.getLevel().getBlockEntity(f.terminal.projectors().keySet().iterator().next());
        output.data.active=false;output.setDirty();
        s.start=System.currentTimeMillis()-1;s.end=s.start+60000;f.manager.dirty();
        h.runAfterDelay(30,()->{h.assertTrue(s.status==ManagerState.Status.RUNNING&&f.terminal.clock()!=null&&f.terminal.clock().playing(),"actual manager tick performs authorized scheduled start");h.assertTrue(output.data.active&&output.terminal()==f.terminal,"scheduled singleton start re-enables existing output and preserves its binding");var clock=f.terminal.clock();long time=clock.time();
            h.runAfterDelay(5,()->{h.assertTrue(f.terminal.clock()==clock&&clock.time()>time,"same server clock advances without replay");h.succeed();});});
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void arm_permission_expiry_confirmation_and_failed_grant_leave_modes_intact(GameTestHelper h){
        Fixture f=fixture(h);var s=plan(f);f.hall.override=true;var guest=h.makeMockServerPlayerInLevel();guest.setPos(f.owner.position());long rev=f.manager.state.revision;
        PanelGameTests.rejects(h,()->f.manager.armShow(guest,s.id,rev,true),"guest cannot arm");PanelGameTests.rejects(h,()->f.manager.armShow(f.owner,s.id,rev,false),"explicit confirmation required");
        f.terminal.owner(UUID.randomUUID());PanelGameTests.rejects(h,()->f.manager.armShow(f.owner,s.id,rev,true),"changed TMS owner rejects grant");
        h.assertTrue(!f.manager.state.schedulerEnabled&&f.hall.override&&s.status==ManagerState.Status.PENDING,"failed validation changes no scheduler/manual/show state");
        f.terminal.owner(f.owner.getUUID());s.start=System.currentTimeMillis()-60000;s.end=s.start+120000;s.status=ManagerState.Status.MISSED;s.outcome=MessageText.key("message.m128");
        PanelGameTests.rejects(h,()->f.manager.armShow(f.owner,s.id,f.manager.state.revision,true),"missed show cannot be resurrected by arming");
        var copy=StateCodec.decode(StateCodec.encode(f.manager.state));h.assertTrue(copy.show(s.id).status==ManagerState.Status.MISSED&&copy.show(s.id).outcome.equals(s.outcome),"original missed reason survives save/reload");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void manual_time_and_runtime_survive_poster_changes_and_public_reload(GameTestHelper h){
        var actor=PanelGameTests.actor(h);InfoTile lower=PanelGameTests.place(h,actor,new BlockPos(2,1,2)),upper=PanelGameTests.place(h,actor,new BlockPos(2,2,2));
        h.setBlock(new BlockPos(4,1,2),CinemaManager.MANAGER.get());ManagerTile m=h.getBlockEntity(new BlockPos(4,1,2));m.owner=actor.getUUID();var hall=new ManagerState.Hall();hall.name="Hall";hall.terminalIdentity=UUID.randomUUID().toString();m.state.halls.add(hall);lower.bind(actor,m,hall.id);
        for(var layout:PanelSettings.Layout.values())for(String poster:List.of("","resource:minecraft:textures/block/stone.png")){
            var settings=new PanelSettings(layout,"Hidden hall","Manual movie","18:45–20:45","private","private",poster,0xffffff,0,0xffffff,100,PanelSettings.PUBLIC,"1h 53m");
            lower.configure(actor,PanelGroup.of(lower).signature(),settings,PanelScope.MANUAL);
            h.assertTrue(lower.manager==null&&upper.manager==null&&m.state.halls.size()==1,"standalone mode unbinds only panels, retains manager");
            var data=PanelNetwork.guestData(lower);var fields=GuestFields.forRow(data.settings(),data.scope(),data.snapshot(),null,true,k->k);
            h.assertTrue(data.snapshot().rows().isEmpty()&&fields.stream().anyMatch(x->x.value().equals("18:45–20:45"))&&fields.stream().anyMatch(x->x.value().equals("Manual movie"))&&fields.stream().anyMatch(x->x.label().equals("duration_manual_label")&&x.value().equals("1h 53m")),"actual public renderer input preserves manual title/time/runtime with and without poster");
            InfoTile copy=new InfoTile(lower.getBlockPos(),lower.getBlockState());copy.loadWithComponents(lower.getUpdateTag(h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(copy.panel.time().equals(settings.time())&&copy.panel.duration().equals(settings.duration())&&copy.scope==PanelScope.MANUAL,"client cold tag preserves standalone values");
            InfoTile disk=new InfoTile(lower.getBlockPos(),lower.getBlockState());disk.loadWithComponents(lower.saveWithFullMetadata(h.getLevel().registryAccess()),h.getLevel().registryAccess());h.assertTrue(disk.panel.equals(settings)&&disk.manager==null,"disk save preserves manual fields independently of poster/layout");
        }
        PanelGameTests.rejects(h,()->lower.bind(actor,m,hall.id),"manual mode cannot silently become a controller mode");h.succeed();
    }
}
