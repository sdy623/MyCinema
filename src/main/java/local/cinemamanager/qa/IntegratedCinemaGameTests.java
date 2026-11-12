package local.cinemamanager.qa;
import local.cinemamanager.projection.CinemaProjectionSettings;

import local.cinemamanager.audio.TheaterAudioMode;
import local.cinemamanager.audio.TheaterAudioAnchor;
import local.cinemamanager.audio.TheaterAudioSettings;

import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import me.srrapero720.waterframes.common.block.data.DisplayData;
import local.cinemamanager.projection.CinemaProjectorItem;
import local.cinemamanager.projection.*;
import local.cinemamanager.network.TheaterPacket;
import local.cinemamanager.projection.ProjectionGeometry;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.Proxy;

/** Cross-feature checks in a real GameTest world, using explicitly simulated test actors. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class IntegratedCinemaGameTests {
    private record Fixture(ServerPlayer actor, CinemaTerminalTile t, CinemaProjectorTile a, CinemaProjectorTile b, ManagerTile manager, ManagerState.Hall hall) {}
    private static Fixture fixture(GameTestHelper h) {
        var actor=h.makeMockServerPlayerInLevel();actor.setGameMode(GameType.SURVIVAL);
        actor.setPos(h.absolutePos(new BlockPos(7,3,2)).getCenter());actor.setYRot(Direction.NORTH.toYRot());
        for(int x=2;x<=12;x++)for(int z=4;z<=9;z++)h.setBlock(new BlockPos(x,2,z),Blocks.STONE);
        var first=place(h,actor,new BlockPos(4,3,6),ProjectorStyle.CINEMA);
        var second=place(h,actor,new BlockPos(10,3,6),ProjectorStyle.IMAX_GT);
        h.setBlock(new BlockPos(7,3,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile terminal=h.getBlockEntity(new BlockPos(7,3,1));terminal.owner(actor.getUUID());
        h.assertTrue(terminal.bindNearby(actor)==2&&terminal.controlsLinked(),"new physical styles become one linked TMS unit");
        for(var p:new CinemaProjectorTile[]{first,second}){p.data.setWidth(8);p.data.setHeight(4);p.data.projectionDistance=4;p.projectionOffsetX=terminal.getBlockPos().getX()-p.getBlockPos().getX();p.projectionOffsetY=0;}
        h.setBlock(new BlockPos(7,3,0),CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(new BlockPos(7,3,0));manager.owner=actor.getUUID();var hall=manager.enroll(actor,terminal,true);hall.name="Integrated hall";hall.current="Integration fixture";
        return new Fixture(actor,terminal,first,second,manager,hall);
    }
    private static CinemaProjectorTile place(GameTestHelper h,ServerPlayer actor,BlockPos relative,ProjectorStyle style){
        BlockPos pos=h.absolutePos(relative);var stack=CinemaProjectorItem.stack(style);stack.setCount(2);actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var context=new BlockPlaceContext(h.getLevel(),actor,InteractionHand.MAIN_HAND,stack,new BlockHitResult(pos.getCenter(),Direction.UP,pos,false));
        h.assertTrue(stack.useOn(context).consumesAction()&&stack.getCount()==1,"real item places requested whole-machine style");
        h.assertTrue(h.getLevel().getBlockEntity(pos)instanceof CinemaProjectorTile,"new model retains TMS member tile");return (CinemaProjectorTile)h.getLevel().getBlockEntity(pos);
    }
    private static CompoundTag mode(Fixture f,String value){var tag=new CompoundTag();tag.putUUID("terminal_id",f.t.identity());tag.putString("mode",value);return tag;}
    private static CompoundTag point(Fixture f,Vec3 value){var tag=new CompoundTag();tag.putUUID("terminal_id",f.t.identity());tag.putDouble("x",value.x);tag.putDouble("y",value.y);tag.putDouble("z",value.z);return tag;}
    private static void near(GameTestHelper h,Vec3 actual,Vec3 expected,String why){h.assertTrue(actual!=null&&actual.distanceToSqr(expected)<1e-8,why);}
    private static IPayloadContext context(ServerPlayer actor){return(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class[]{IPayloadContext.class},(p,m,a)->switch(m.getName()){case"player"->actor;case"flow"->PacketFlow.SERVERBOUND;default->throw new UnsupportedOperationException(m.getName());});}
    private static ManagerState.Hall seekView(Fixture f){var copy=StateCodec.decode(StateCodec.publicView(f.manager.state));f.manager.describeSeek(f.actor,copy,false);return copy.halls.getFirst();}

    @GameTest(template="empty",timeoutTicks=70)
    public static void integrated_models_audio_origin_and_saved_identity(GameTestHelper h){
        var f=fixture(h);near(h,f.t.audioOrigin(),f.a.getBlockPos().getCenter().add(f.b.getBlockPos().getCenter()).scale(.5),"both new styles resolve projector midpoint");
        var part=ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,Direction.NORTH).keySet().stream().filter(p->!p.equals(BlockPos.ZERO)).findFirst().orElseThrow();
        var resolved=ProjectorStructure.resolve(h.getLevel(),f.b.getBlockPos().offset(part));h.assertTrue(f.b.getBlockPos().equals(resolved)&&f.b.directControlsLocked(),"whole-machine peripheral interaction resolves the same locked member");
        var geometry=new CompoundTag();geometry.putFloat(DisplayData.WIDTH,8);geometry.putFloat(DisplayData.HEIGHT,4);geometry.putFloat(DisplayData.PROJECTION_DISTANCE,4);geometry.putFloat(CinemaProjectionSettings.OFFSET_X,f.b.projectionOffsetX);geometry.putFloat(CinemaProjectionSettings.OFFSET_Y,1);geometry.putFloat(CinemaProjectionSettings.OFFSET_Z,0);
        TheaterPacket.handle(new TheaterPacket(resolved,TheaterPacket.GEOMETRY,geometry),context(f.actor));h.assertTrue(f.b.projectionOffsetY==0,"direct geometry on new linked model is denied by server");
        h.assertTrue(TheaterAudioSettings.select(f.t,f.actor,mode(f,"screen")),"screen mode accepts the new model pair");near(h,f.t.audioOrigin(),ProjectionGeometry.center(ProjectionGeometry.corners(f.a)),"screen mode uses actual projected geometry");
        var old=f.t.audioOrigin();f.a.projectionOffsetY+=.5f;f.b.projectionOffsetY+=.5f;near(h,f.t.audioOrigin(),old.add(0,.5,0),"screen origin follows common calibration delta");
        var audience=f.t.getBlockPos().getCenter().add(.125,0,3.25);h.assertTrue(TheaterAudioSettings.calibrate(f.t,f.actor,point(f,audience))&&TheaterAudioSettings.select(f.t,f.actor,mode(f,"audience")),"explicit audience calibration works with new models");near(h,f.a.audioOrigin(),audience,"cinema member uses TMS audio origin");near(h,f.b.audioOrigin(),audience,"IMAX member uses same TMS audio origin");
        var saved=f.t.saveWithFullMetadata(h.getLevel().registryAccess());var restored=(CinemaTerminalTile)BlockEntity.loadStatic(f.t.getBlockPos(),f.t.getBlockState(),saved,h.getLevel().registryAccess());h.assertTrue(restored!=null&&restored.identity().equals(f.t.identity())&&restored.controlsLinked()&&restored.audioMode()==TheaterAudioMode.AUDIENCE&&restored.audienceCenter().equals(audience)&&restored.projectors().size()==2,"unified NBT retains audio, link control and identity fields together");
        for(var member:new CinemaProjectorTile[]{f.a,f.b}){var copy=(CinemaProjectorTile)BlockEntity.loadStatic(member.getBlockPos(),member.getBlockState(),member.saveWithFullMetadata(h.getLevel().registryAccess()),h.getLevel().registryAccess());copy.setLevel(h.getLevel());h.assertTrue(copy.identity().equals(member.identity())&&copy.linkedTo(f.t)&&copy.clock()==null&&copy.follower()==null,"both saved model variants retain links without owning media resources");}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void integrated_origin_change_keeps_seek_clock_and_wall_panel(GameTestHelper h){
        var f=fixture(h);f.actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CinemaManager.INFO_ITEM.get(),8));
        InfoTile panel=PanelGameTests.place(h,f.actor,new BlockPos(13,2,2)),top=PanelGameTests.place(h,f.actor,new BlockPos(13,3,2));panel.bind(f.actor,f.manager,f.hall.id);h.assertTrue(PanelGroup.of(panel).height()==2&&top.hallId.equals(f.hall.id),"stacked wall panel binds to this model-based TMS hall");
        f.manager.state.timezone="Asia/Tokyo";var planned=new ManagerState.Show();planned.hallId=f.hall.id;planned.title="Integration fixture";planned.url="water://integrated-cinema-fixture";planned.start=System.currentTimeMillis()+60000;planned.end=planned.start+60000;f.manager.state.shows.add(planned);
        f.t.data.url="water://integrated-cinema-fixture";f.t.data.uuid=f.actor.getUUID();f.t.data.active=true;f.t.data.paused=true;f.t.setDirty();
        h.runAfterDelay(6,()->{
            var clock=f.t.clock();h.assertTrue(clock!=null&&clock.paused(),"one paused server session starts");clock.syncDuration(6000);var lease=seekView(f);h.assertTrue(!lease.seekToken.isBlank(),"new model pair can obtain normal authorized seek lease");
            var audience=f.t.getBlockPos().getCenter().add(0,0,3);h.assertTrue(TheaterAudioSettings.calibrate(f.t,f.actor,point(f,audience))&&TheaterAudioSettings.select(f.t,f.actor,mode(f,"audience")),"audio origin can change during a live session");h.assertTrue(f.t.clock()==clock&&clock.paused(),"origin edit keeps exact shared clock");
            f.manager.seek(f.actor,f.hall.id,lease.seekToken,2856);h.assertTrue(f.t.clock()==clock&&clock.time()==2856&&clock.paused(),"origin-only edit does not invalidate the unchanged media seek session");
            panel.refreshNow();h.assertTrue(panel.publicSnapshot.rows().getFirst().hall().equals(f.hall.name)&&top.publicSnapshot.equals(panel.publicSnapshot)&&panel.publicSnapshot.rows().getFirst().title().equals("Integration fixture"),"both display segments retain factual hall and scheduled movie rows");
            var info=PanelNetwork.guestData(panel);h.assertTrue(info.snapshot().rows().size()==1&&info.snapshot().rows().getFirst().id().equals(planned.id)&&!PanelNetwork.JSON.toJson(info).contains("seekToken"),"public panel remains bound-hall-only and cannot issue control authority");
            f.t.data.paused=false;clock.pause(false);f.t.setDirty();h.assertTrue(TheaterAudioSettings.select(f.t,f.actor,mode(f,"projector")),"playing session can move origin back to projector midpoint");long before=clock.time();
            h.runAfterDelay(25,()->{h.assertTrue(f.t.clock()==clock&&clock.playing()&&clock.time()>before,"same clock keeps advancing after audio-origin change");panel.refreshNow();h.assertTrue(panel.publicSnapshot.rows().getFirst().durationMs()==6000&&top.publicSnapshot.equals(panel.publicSnapshot)&&!PanelNetwork.JSON.toJson(PanelNetwork.guestData(panel)).contains("deviceState"),"stacked panel uses trusted duration while transport status stays private");h.assertTrue(f.a.clock()==null&&f.b.clock()==null&&f.a.follower()==null&&f.b.follower()==null,"new models still own no second playback authority");f.t.data.paused=true;clock.pause(true);f.t.setDirty();h.succeed();});
        });
    }
}
