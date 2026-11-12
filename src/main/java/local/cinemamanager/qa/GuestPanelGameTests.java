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
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class GuestPanelGameTests {
    private static ManagerTile manager(GameTestHelper h,ServerPlayer actor,BlockPos pos){h.setBlock(pos,CinemaManager.MANAGER.get());ManagerTile m=h.getBlockEntity(pos);m.owner=actor.getUUID();m.state.timezone="Asia/Tokyo";return m;}
    private static ManagerState.Hall hall(ManagerTile m,String name){var hall=new ManagerState.Hall();hall.name=name;hall.terminalIdentity=UUID.randomUUID().toString();m.state.halls.add(hall);return hall;}
    private static ManagerState.Show show(ManagerTile m,ManagerState.Hall hall,String title,String url){var show=new ManagerState.Show();show.hallId=hall.id;show.title=title;show.url=url;show.start=System.currentTimeMillis()+60000;show.end=show.start+60000;m.state.shows.add(show);return show;}
    private static InfoTile publicCopy(GameTestHelper h,InfoTile source){InfoTile copy=new InfoTile(source.getBlockPos(),source.getBlockState());copy.loadWithComponents(source.getUpdateTag(h.getLevel().registryAccess()),h.getLevel().registryAccess());return copy;}
    @GameTest(template="empty",timeoutTicks=40)
    public static void public_group_tokens_preserve_split_rebind_boundaries(GameTestHelper h){
        var actor=PanelGameTests.actor(h);InfoTile lower=PanelGameTests.place(h,actor,new BlockPos(2,1,2)),middle=PanelGameTests.place(h,actor,new BlockPos(2,2,2)),top=PanelGameTests.place(h,actor,new BlockPos(2,3,2));
        ManagerTile m=manager(h,actor,new BlockPos(4,1,2));var a=hall(m,"Hall A");var b=hall(m,"Hall B");show(m,a,"Film A","water://a");show(m,b,"Film B","water://b");
        lower.bind(actor,m,a.id);UUID persistedGroup=lower.panelGroup;
        h.setBlock(new BlockPos(2,2,2),Blocks.AIR);top.bind(actor,m,b.id);middle=PanelGameTests.place(h,actor,new BlockPos(2,2,2));lower.refreshNow();top.refreshNow();
        h.assertTrue(lower.panelGroup.equals(persistedGroup)&&middle.panelGroup.equals(persistedGroup)&&top.panelGroup.equals(persistedGroup),"original persistent group identity survives split and rebind");
        h.assertTrue(PanelGroup.of(lower).height()==2&&PanelGroup.of(top).height()==1,"private server binding keeps A and B separate");
        InfoTile publicLower=publicCopy(h,lower),publicMiddle=publicCopy(h,middle),publicTop=publicCopy(h,top);
        h.assertTrue(PanelGroup.visualCompatible(publicLower,publicMiddle)&&!PanelGroup.visualCompatible(publicMiddle,publicTop),"client-only tags reconstruct the same A/B boundary without private bindings");
        h.assertTrue(publicLower.publicSnapshot.rows().getFirst().title().equals("Film A")&&publicTop.publicSnapshot.rows().getFirst().title().equals("Film B"),"both client render roots keep their own complete factual rows");
        h.assertTrue(publicLower.manager==null&&publicMiddle.managerIdentity==null&&publicTop.hallId.isEmpty(),"visual boundary needs no manager position, manager identity or private hall ID");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void lobby_scope_binding_redaction_and_root_replacement(GameTestHelper h){
        var actor=PanelGameTests.actor(h);InfoTile lower=PanelGameTests.place(h,actor,new BlockPos(2,1,2)),upper=PanelGameTests.place(h,actor,new BlockPos(2,2,2));
        ManagerTile m=manager(h,actor,new BlockPos(4,1,2)),other=manager(h,actor,new BlockPos(6,1,2));
        var a=hall(m,"Same hall");var b=hall(m,"Second hall");var foreign=hall(other,"Same hall");
        var first=show(m,a,"First show","https://example.com/private-first.mkv");show(m,b,"Second show","https://example.com/private-second.mkv");show(other,foreign,"FOREIGN_PRIVATE","water://foreign");
        a.deviceState="DEVICE_PRIVATE";a.issue="ISSUE_PRIVATE";a.seekToken="SEEK_PRIVATE";m.state.notice="NOTICE_PRIVATE";
        var settings=new PanelSettings(PanelSettings.Layout.TEXT,"Override hall","Override title","Override time","NEXT_PRIVATE","FOOTER_PRIVATE","",0xffffff,0,0xffffff,100,127);
        lower.configure(actor,PanelGroup.of(lower).signature(),settings,PanelScope.LOBBY);lower.bind(actor,m,"");
        h.assertTrue(lower.hallId.isEmpty()&&upper.scope==PanelScope.LOBBY&&upper.managerIdentity.equals(m.identity),"explicit lobby binds whole column without any selected hall");
        var data=PanelNetwork.guestData(upper);String json=PanelNetwork.JSON.toJson(data);String tag=lower.getUpdateTag(h.getLevel().registryAccess()).toString();
        h.assertTrue(data.snapshot().rows().size()==2&&data.settings().title().isBlank(),"lobby includes both local halls and ignores legacy content overrides");
        for(String secret:List.of(first.url,"FOREIGN_PRIVATE","DEVICE_PRIVATE","ISSUE_PRIVATE","SEEK_PRIVATE","NOTICE_PRIVATE","NEXT_PRIVATE","FOOTER_PRIVATE","Override title"))h.assertFalse(json.contains(secret)||tag.contains(secret),"guest allowlist excludes "+secret);
        var update=lower.getUpdateTag(h.getLevel().registryAccess());h.assertFalse(update.contains("owner")||update.contains("manager")||update.contains("manager_id")||update.contains("lines")||update.contains("panel_fields"),"public tag excludes ownership, bindings and all legacy diagnostics");
        h.assertTrue(lower.diagnostics().contains("NOTICE_PRIVATE"),"owner diagnostics remain accessible");
        var topTag=upper.getUpdateTag(h.getLevel().registryAccess());h.assertTrue(PublicPanelSnapshot.read(topTag.getCompound("guest_snapshot")).rows().isEmpty(),"non-root sends no duplicate row list");
        h.setBlock(new BlockPos(2,1,2),Blocks.AIR);upper.refreshNow();
        h.assertTrue(PanelGroup.of(upper).root()==upper&&PublicPanelSnapshot.read(upper.getUpdateTag(h.getLevel().registryAccess()).getCompound("guest_snapshot")).rows().size()==2,"remaining upper segment becomes root with a complete fresh snapshot");
        InfoTile cold=new InfoTile(upper.getBlockPos(),upper.getBlockState());cold.loadWithComponents(upper.getUpdateTag(h.getLevel().registryAccess()),h.getLevel().registryAccess());h.assertTrue(cold.publicSnapshot.rows().size()==2&&cold.owner==null,"initial client tag works before level attachment without owner data");
        h.setBlock(new BlockPos(4,1,2),Blocks.AIR);manager(h,actor,new BlockPos(4,1,2));upper.refreshNow();h.assertTrue(upper.publicSnapshot.rows().isEmpty(),"replacement manager at same coordinates never inherits the old identity binding");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void guest_read_permission_scope_stale_and_no_chunk_loads(GameTestHelper h){
        var actor=PanelGameTests.actor(h);InfoTile panel=PanelGameTests.place(h,actor,new BlockPos(2,1,2));ManagerTile m=manager(h,actor,new BlockPos(4,1,2));var hall=hall(m,"Hall");panel.bind(actor,m,hall.id);
        var custom=new PanelSettings(PanelSettings.Layout.TEXT,"Unplanned hall","Unplanned title","20:00","PRIVATE_NEXT","PRIVATE_FOOTER","",0xffffff,0,0xffffff,100,127);panel.configure(actor,PanelGroup.of(panel).signature(),custom);
        var data=PanelNetwork.guestData(panel);h.assertTrue(data.snapshot().rows().isEmpty()&&data.settings().title().equals("Unplanned title")&&data.settings().time().equals("20:00"),"explicit HALL customization survives without inventing a scheduled row");
        var guest=h.makeMockServerPlayerInLevel();guest.setPos(panel.getBlockPos().getCenter());h.assertTrue(PanelNetwork.guestAllowed(guest,panel),"nearby visitor can view without ownership");
        PanelGameTests.rejects(h,()->panel.configure(guest,PanelGroup.of(panel).signature(),PanelSettings.DEFAULT,PanelScope.LOBBY),"guest cannot change scope");
        String old=PanelGroup.of(panel).signature();panel.configure(actor,old,custom,PanelScope.LOBBY);PanelGameTests.rejects(h,()->panel.configure(actor,old,custom,PanelScope.HALL),"stale editor cannot overwrite changed scope");
        h.assertTrue(PanelNetwork.guestData(panel).settings().title().isBlank(),"lobby never turns customization into a fictitious programme row");
        m.owner=UUID.randomUUID();PanelGameTests.rejects(h,()->panel.configure(actor,PanelGroup.of(panel).signature(),custom,PanelScope.HALL),"scope changes recheck manager authority");
        guest.setPos(panel.getBlockPos().getCenter().add(20,0,0));h.assertFalse(PanelNetwork.guestAllowed(guest,panel),"remote guest request denied");
        BlockPos far=new BlockPos(100000,64,100000);h.assertFalse(h.getLevel().hasChunkAt(far),"target region starts unloaded");panel.manager=far;panel.refreshNow();PanelNetwork.refreshGuest(guest,new PanelNetwork.GuestRefresh(far,UUID.randomUUID().toString()));h.assertFalse(h.getLevel().hasChunkAt(far),"public projection and forged request create no chunk tickets");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=50)
    public static void panel_duration_uses_exact_prepared_clock_without_playback_mutation(GameTestHelper h){
        var actor=PanelGameTests.actor(h);ManagerTile m=manager(h,actor,new BlockPos(1,1,1));
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=h.getBlockEntity(new BlockPos(4,1,1));t.owner(actor.getUUID());
        h.setBlock(new BlockPos(4,1,4),CinemaDisplays.CINEMA_PROJECTOR.get());CinemaProjectorTile projector=h.getBlockEntity(new BlockPos(4,1,4));projector.owner(actor.getUUID());t.bindNearby(actor);
        var hall=m.enroll(actor,t,true);InfoTile panel=PanelGameTests.place(h,actor,new BlockPos(2,1,2));panel.bind(actor,m,hall.id);
        t.data.url="water://guest-duration-fixture";t.data.uuid=actor.getUUID();t.data.active=true;t.data.paused=true;t.setDirty();
        var planned=show(m,hall,"Prepared future film",t.data.url);var foreign=show(m,hall,"Unknown future film","water://different-source");foreign.start=planned.end+60000;foreign.end=foreign.start+60000;
        h.runAfterDelay(6,()->{
            var clock=t.clock();h.assertTrue(clock!=null&&clock.paused(),"existing paused TMS clock is prepared");h.assertTrue(m.publicDuration(planned)==0,"unknown media never inherits its sixty-second scheduled slot");
            clock.syncDuration(6000);long time=clock.time();panel.refreshNow();h.assertTrue(panel.publicSnapshot.rows().getFirst().durationMs()==6000&&m.publicDuration(foreign)==0,"only exact prepared source gives future show duration");
            h.assertTrue(clock==t.clock()&&clock.paused()&&clock.time()==time&&planned.status==ManagerState.Status.PENDING&&!m.state.schedulerEnabled&&projector.clock()==null,"projection changes no player, clock, transport, schedule or automation");
            hall.durationMs=6000;t.data.url="water://changed-before-tick";h.assertTrue(m.publicDuration(planned)==0,"new source cannot reuse stale hall duration or old clock before a tick");t.data.url=planned.url;
            clock.live(true);h.assertTrue(m.publicDuration(planned)==0,"live current source does not claim finite duration");clock.live(false);
            t.data.active=false;h.assertTrue(m.publicDuration(planned)==0,"inactive source does not claim prepared duration");t.data.active=true;
            var id=t.identity();m.state.hall(hall.id).terminalIdentity=UUID.randomUUID().toString();h.assertTrue(m.publicDuration(planned)==0,"replaced terminal identity cannot publish old metadata");m.state.hall(hall.id).terminalIdentity=id.toString();h.succeed();
        });
    }
}
