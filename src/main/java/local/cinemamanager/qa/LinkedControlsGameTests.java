package local.cinemamanager.qa;
import local.cinemamanager.projection.CinemaProjectionSettings;
import local.cinemamanager.network.TheaterPacket;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.data.DisplayData;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import me.srrapero720.waterframes.common.network.*;
import me.srrapero720.waterframes.common.network.packets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** Real dedicated-world packet/NBT checks with explicit simulated actors, not human GUI proof. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class LinkedControlsGameTests {
    private static final class Actor extends FakePlayer {
        String message="";
        Actor(net.minecraft.server.level.ServerLevel level) {super(level,new GameProfile(UUID.randomUUID(),"WFChainFixture"));setGameMode(GameType.SURVIVAL);}
        @Override public void displayClientMessage(Component text, boolean actionBar){message=text.getString();}
    }
    private record Fixture(CinemaTerminalTile terminal,CinemaProjectorTile a,CinemaProjectorTile b,Actor actor,IPayloadContext context) {}
    private static Fixture fixture(GameTestHelper h) {
        BlockPos t=new BlockPos(4,2,4),a=t.offset(-2,0,0),b=t.offset(2,0,0);
        h.setBlock(t,CinemaDisplays.TMS_TERMINAL.get());h.setBlock(a,CinemaDisplays.CINEMA_PROJECTOR.get());h.setBlock(b,CinemaDisplays.CINEMA_PROJECTOR.get());
        var terminal=(CinemaTerminalTile)h.getBlockEntity(t);var first=(CinemaProjectorTile)h.getBlockEntity(a);var second=(CinemaProjectorTile)h.getBlockEntity(b);
        Actor actor=new Actor(h.getLevel());actor.setPos(terminal.getBlockPos().getX()+.5,terminal.getBlockPos().getY(),terminal.getBlockPos().getZ()+.5);
        terminal.owner(actor.getUUID());first.owner(actor.getUUID());second.owner(actor.getUUID());
        IPayloadContext context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class[]{IPayloadContext.class},(proxy,method,args)->switch(method.getName()){case "player"->actor;case "flow"->PacketFlow.SERVERBOUND;default->throw new UnsupportedOperationException(method.getName());});
        h.assertTrue(terminal.bindNearby(actor)==2,"real bind selects both owned projectors");
        return new Fixture(terminal,first,second,actor,context);
    }
    private static CompoundTag settings(String key,float value){var tag=new CompoundTag();tag.putFloat(key,value);return tag;}
    private static void packet(Fixture f,BlockPos pos,int action,CompoundTag tag){TheaterPacket.handle(new TheaterPacket(pos,action,tag),f.context);}
    private static CompoundTag group(Fixture f,CompoundTag settings){var tag=new CompoundTag();tag.putUUID("terminal_id",f.terminal.identity());tag.putLong("projector_pos",f.a.getBlockPos().asLong());tag.putUUID("projector_id",f.a.identity());tag.putBoolean("linked",f.terminal.controlsLinked());tag.put("settings",settings);return tag;}
    private static void linked(Fixture f,boolean linked){var tag=new CompoundTag();tag.putUUID("terminal_id",f.terminal.identity());tag.putBoolean("linked",linked);packet(f,f.terminal.getBlockPos(),TheaterPacket.LINK_CONTROLS,tag);}
    private static void eq(GameTestHelper h,float actual,float expected,String label){h.assertTrue(Math.abs(actual-expected)<.0001f,label+": "+actual+" vs "+expected);}

    @GameTest(template="empty",timeoutTicks=40)
    public static void linked_controls_enforce_server_endpoints(GameTestHelper h)throws Exception {
        Fixture f=fixture(h);h.assertTrue(f.terminal.controlsLinked(),"binding defaults linked");
        h.assertTrue(f.a.directControlsLocked()&&f.b.directControlsLocked(),"both loaded members locked");
        eq(h,f.terminal.data.minVolumeDistance,64,"new TMS full-volume default");
        f.a.projectionOffsetX=2.5f;f.b.projectionOffsetX=-2.5f;f.a.projectionOffsetY=-13.5f;f.b.projectionOffsetY=-12.75f;
        var direct=settings(DisplayData.WIDTH,30);packet(f,f.a.getBlockPos(),TheaterPacket.SETTINGS,direct);eq(h,f.a.data.getWidth(),42,"locked direct settings refused");
        direct.putLong("controller",f.terminal.getBlockPos().asLong());packet(f,f.a.getBlockPos(),TheaterPacket.SETTINGS,direct);eq(h,f.a.data.getWidth(),42,"legacy controller cannot bypass lock");
        var geometry=settings(DisplayData.WIDTH,30);geometry.putFloat(DisplayData.HEIGHT,20);geometry.putFloat(DisplayData.PROJECTION_DISTANCE,30);geometry.putFloat(CinemaProjectionSettings.OFFSET_X,0);geometry.putFloat(CinemaProjectionSettings.OFFSET_Y,0);geometry.putFloat(CinemaProjectionSettings.OFFSET_Z,0);
        packet(f,f.a.getBlockPos(),TheaterPacket.GEOMETRY,geometry);eq(h,f.a.data.getWidth(),42,"legacy geometry packet refused");
        var onData=DisplayNetwork.class.getDeclaredMethod("onData",DataPacket.class,IPayloadContext.class);onData.setAccessible(true);onData.invoke(null,new DataSyncPacket(f.a.getBlockPos(),direct),f.context);eq(h,f.a.data.getWidth(),42,"raw data packet refused");
        var onControl=DisplayNetwork.class.getDeclaredMethod("onControl",ControlPacket.class,IPayloadContext.class);onControl.setAccessible(true);int volume=f.a.data.volume;onControl.invoke(null,new VolumePacket(f.a.getBlockPos(),1),f.context);eq(h,f.a.data.volume,volume,"legacy remote control packet refused");
        BlockPos unloaded=new BlockPos(100000,64,100000);h.assertFalse(h.getLevel().hasChunkAt(unloaded),"remote test chunk starts unloaded");
        onData.invoke(null,new DataSyncPacket(unloaded,direct),f.context);onControl.invoke(null,new VolumePacket(unloaded,1),f.context);
        h.assertFalse(h.getLevel().hasChunkAt(unloaded),"legacy data/control packets never load chunks");
        DisplayNetwork.openScreen(f.actor,f.a.getBlockPos(),true);h.assertTrue(!f.actor.message.isEmpty(),"remote UI shows disabled notice");
        var edit=settings(CinemaProjectionSettings.OFFSET_Y,-12.5f);edit.putFloat(DisplayData.WIDTH,40);packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,group(f,edit));
        eq(h,f.a.projectionOffsetY,-12.5f,"reference moved up one");eq(h,f.b.projectionOffsetY,-11.75f,"second moved by same delta");eq(h,f.a.projectionOffsetX,2.5f,"first horizontal alignment retained");eq(h,f.b.projectionOffsetX,-2.5f,"second horizontal alignment retained");eq(h,f.a.data.getWidth(),40,"first common width");eq(h,f.b.data.getWidth(),40,"second common width");
        var wrong=group(f,settings(DisplayData.WIDTH,32));wrong.putUUID("terminal_id",UUID.randomUUID());packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,wrong);eq(h,f.a.data.getWidth(),40,"stale terminal identity refused");
        linked(f,false);h.assertFalse(f.terminal.controlsLinked(),"only TMS unlock endpoint works");h.assertFalse(f.a.directControlsLocked(),"loaded unlocked member controllable");packet(f,f.a.getBlockPos(),TheaterPacket.SETTINGS,settings(DisplayData.WIDTH,35));eq(h,f.a.data.getWidth(),35,"unlocked member edit accepted");eq(h,f.b.data.getWidth(),40,"unlocked peer unchanged");
        f.terminal.bindNearby(f.actor);h.assertTrue(f.terminal.controlsLinked(),"immediate rate-limited rebind restores default lock");
        f.b.owner(UUID.randomUUID());packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,group(f,settings(DisplayData.WIDTH,33)));eq(h,f.a.data.getWidth(),35,"unauthorized second member prevents first write");eq(h,f.b.data.getWidth(),40,"unauthorized second unchanged");
        h.assertTrue(f.a.clock()==null&&f.b.clock()==null&&f.a.follower()==null&&f.b.follower()==null,"no new member playback resources");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void linked_controls_persist_and_fail_closed(GameTestHelper h) {
        Fixture f=fixture(h);var registries=h.getLevel().registryAccess();
        linked(f,false);var saved=f.terminal.saveWithoutMetadata(registries);f.terminal.controlsLinked(true);f.terminal.loadWithComponents(saved,registries);h.assertFalse(f.terminal.controlsLinked(),"unlocked mode reloads");
        saved.remove("controls_linked");f.terminal.loadWithComponents(saved,registries);h.assertTrue(f.terminal.controlsLinked(),"old save defaults linked");
        f.a.projectionOffsetX=2.5f;f.b.projectionOffsetX=512;
        packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,group(f,settings(CinemaProjectionSettings.OFFSET_X,3.5f)));eq(h,f.a.projectionOffsetX,2.5f,"overflow prevents first partial write");eq(h,f.b.projectionOffsetX,512,"overflow leaves second untouched");
        float before=f.a.data.getWidth();f.actor.setPos(f.actor.getX()+30,f.actor.getY(),f.actor.getZ());packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,group(f,settings(DisplayData.WIDTH,30)));eq(h,f.a.data.getWidth(),before,"remote group packet outside local TMS range refused");
        f.actor.setPos(f.terminal.getBlockPos().getX()+.5,f.terminal.getBlockPos().getY(),f.terminal.getBlockPos().getZ()+.5);f.actor.setGameMode(GameType.SPECTATOR);packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,group(f,settings(DisplayData.WIDTH,30)));eq(h,f.a.data.getWidth(),before,"spectator cannot edit");f.actor.setGameMode(GameType.SURVIVAL);
        h.getLevel().setBlock(f.b.getBlockPos(),Blocks.AIR.defaultBlockState(),3);packet(f,f.terminal.getBlockPos(),TheaterPacket.GROUP_SETTINGS,group(f,settings(DisplayData.WIDTH,30)));eq(h,f.a.data.getWidth(),before,"missing member makes linked operation atomic");
        h.getLevel().setBlock(f.terminal.getBlockPos(),Blocks.AIR.defaultBlockState(),3);h.assertTrue(f.a.terminal()==null&&f.a.terminalPos()!=null&&f.a.directControlsLocked(),"lost terminal leaves link locked");packet(f,f.a.getBlockPos(),TheaterPacket.SETTINGS,settings(DisplayData.WIDTH,30));eq(h,f.a.data.getWidth(),before,"lost terminal never authorizes direct edit");
        h.getLevel().setBlock(f.terminal.getBlockPos(),CinemaDisplays.TMS_TERMINAL.get().defaultBlockState(),3);h.assertTrue(f.a.terminal()==null&&f.a.directControlsLocked(),"replacement at same position cannot inherit authority");h.succeed();
    }
}
