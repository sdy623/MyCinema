package local.cinemamanager.qa;
import local.cinemamanager.network.TheaterPacket;

import local.cinemamanager.audio.TheaterAudioMode;
import local.cinemamanager.audio.TheaterAudioAnchor;
import local.cinemamanager.audio.TheaterAudioSettings;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.data.DisplayData;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import me.srrapero720.waterframes.common.network.*;
import me.srrapero720.waterframes.common.network.packets.*;
import local.cinemamanager.projection.ProjectionGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** Compiled separately for a coordinated native QA run; not shipped in the candidate mod. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class AudioOriginGameTests {
    private static final class Actor extends FakePlayer {
        Actor(net.minecraft.server.level.ServerLevel level){this(level,UUID.randomUUID());}
        Actor(net.minecraft.server.level.ServerLevel level,UUID identity){super(level,new GameProfile(identity,"WFAudioFixture"));setGameMode(GameType.SURVIVAL);}
        @Override public void displayClientMessage(Component text,boolean actionBar){}
    }
    private record Fixture(CinemaTerminalTile terminal,CinemaProjectorTile a,CinemaProjectorTile b,Actor actor){}
    private static Fixture fixture(GameTestHelper h){
        BlockPos t=new BlockPos(4,3,4),a=t.offset(-2,0,0),b=t.offset(2,0,0);
        h.setBlock(t,CinemaDisplays.TMS_TERMINAL.get());h.setBlock(a,CinemaDisplays.CINEMA_PROJECTOR.get());h.setBlock(b,CinemaDisplays.CINEMA_PROJECTOR.get());
        var terminal=(CinemaTerminalTile)h.getBlockEntity(t);var first=(CinemaProjectorTile)h.getBlockEntity(a);var second=(CinemaProjectorTile)h.getBlockEntity(b);
        Actor actor=new Actor(h.getLevel());actor.setPos(Vec3.atCenterOf(terminal.getBlockPos()));
        terminal.owner(actor.getUUID());first.owner(actor.getUUID());second.owner(actor.getUUID());
        h.assertTrue(terminal.bindNearby(actor)==2,"owned pair bound");
        for(var p:new CinemaProjectorTile[]{first,second}){p.data.setWidth(8);p.data.setHeight(4);p.data.projectionDistance=2;p.projectionOffsetY=0;}
        // Compensate each projector's actual position independently, including rotated test structures.
        first.projectionOffsetX=terminal.getBlockPos().getX()-first.getBlockPos().getX();
        first.projectionOffsetZ=terminal.getBlockPos().getZ()-first.getBlockPos().getZ();
        second.projectionOffsetX=terminal.getBlockPos().getX()-second.getBlockPos().getX();
        second.projectionOffsetZ=terminal.getBlockPos().getZ()-second.getBlockPos().getZ();
        return new Fixture(terminal,first,second,actor);
    }
    private static IPayloadContext context(Actor actor){return (IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class[]{IPayloadContext.class},(proxy,method,args)->switch(method.getName()){case "player"->actor;case "flow"->PacketFlow.SERVERBOUND;default->throw new UnsupportedOperationException(method.getName());});}
    private static CompoundTag mode(Fixture f,String mode){var request=new CompoundTag();request.putUUID("terminal_id",f.terminal.identity());request.putString("mode",mode);return request;}
    private static CompoundTag point(Fixture f,Vec3 point){var request=new CompoundTag();request.putUUID("terminal_id",f.terminal.identity());request.putDouble("x",point.x);request.putDouble("y",point.y);request.putDouble("z",point.z);return request;}
    private static void send(Fixture f,int action,CompoundTag request){TheaterPacket.handle(new TheaterPacket(f.terminal.getBlockPos(),action,request),context(f.actor));}
    private static void near(GameTestHelper h,Vec3 actual,Vec3 expected,String message){h.assertTrue(actual!=null&&actual.distanceToSqr(expected)<1e-8,message+": "+actual+" vs "+expected);}

    @GameTest(template="empty",timeoutTicks=40)
    public static void audio_origin_modes_follow_geometry_and_persist(GameTestHelper h){
        var f=fixture(h);int min=f.terminal.data.minVolumeDistance,max=f.terminal.data.maxVolumeDistance;
        Vec3 midpoint=Vec3.atCenterOf(f.a.getBlockPos()).add(Vec3.atCenterOf(f.b.getBlockPos())).scale(.5);
        near(h,f.terminal.audioOrigin(),midpoint,"projector mode is exact pair midpoint");
        send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"audience"));h.assertTrue(f.terminal.audioAnchor().status()==TheaterAudioAnchor.Status.UNCALIBRATED&&f.terminal.audioOrigin()==null,"uncalibrated audience is silent");
        Vec3 audience=Vec3.atCenterOf(f.terminal.getBlockPos()).add(.125,1.25,2.5);
        send(f,TheaterPacket.AUDIENCE_CENTER,point(f,audience));near(h,f.terminal.audioOrigin(),audience,"explicit audience point used exactly");
        send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"screen"));near(h,f.terminal.audioOrigin(),ProjectionGeometry.center(ProjectionGeometry.corners(f.a)),"aligned projected image center used");
        Vec3 before=f.terminal.audioOrigin();f.a.projectionOffsetY+=1;f.b.projectionOffsetY+=1;
        near(h,f.terminal.audioOrigin(),before.add(0,1,0),"screen anchor follows both individual calibrations");
        f.b.projectionOffsetY+=.251f;h.assertTrue(f.terminal.audioAnchor().status()==TheaterAudioAnchor.Status.MISALIGNED&&f.terminal.audioOrigin()==null,"misalignment mutes without guessing a center");
        send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"projector"));near(h,f.terminal.audioOrigin(),midpoint,"projector midpoint independent of projection calibration");
        send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"audience"));var saved=f.terminal.saveWithoutMetadata(h.getLevel().registryAccess());
        f.terminal.audioMode(TheaterAudioMode.PROJECTOR);f.terminal.audienceCenter(null);f.terminal.loadWithComponents(saved,h.getLevel().registryAccess());near(h,f.terminal.audioOrigin(),audience,"NBT reload restores mode and audience");
        h.assertTrue(f.terminal.getUpdateTag(h.getLevel().registryAccess()).getCompound("theater_audio").getString("mode").equals("audience"),"client update includes authoritative mode");
        h.assertTrue(f.terminal.data.minVolumeDistance==min&&f.terminal.data.maxVolumeDistance==max,"min/max sound distances unchanged");
        h.assertTrue(f.a.clock()==null&&f.b.clock()==null&&f.a.follower()==null&&f.b.follower()==null,"members remain geometry-only");
        h.getLevel().setBlock(f.b.getBlockPos(),Blocks.AIR.defaultBlockState(),3);h.assertTrue(f.terminal.audioOrigin()==null,"missing pair member silences every mode");
        h.getLevel().setBlock(f.b.getBlockPos(),CinemaDisplays.CINEMA_PROJECTOR.get().defaultBlockState(),3);h.assertTrue(f.terminal.audioOrigin()==null,"replacement identity cannot inherit the old origin");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void audio_origin_real_actor_and_bounds_are_enforced(GameTestHelper h)throws Exception{
        var f=fixture(h);var request=mode(f,"audience");
        h.assertFalse(TheaterAudioSettings.select(null,f.actor,request),"null terminal public adapter refused");
        h.assertFalse(TheaterAudioSettings.select(f.terminal,f.actor,null),"null request public adapter refused");
        var offThread=java.util.concurrent.CompletableFuture.supplyAsync(()->TheaterAudioSettings.select(f.terminal,f.actor,mode(f,"audience")));
        h.assertFalse(offThread.join(),"off-thread mode mutation refused");
        var offThreadPoint=java.util.concurrent.CompletableFuture.supplyAsync(()->TheaterAudioSettings.calibrate(f.terminal,f.actor,point(f,Vec3.atCenterOf(f.terminal.getBlockPos()))));
        h.assertFalse(offThreadPoint.join(),"off-thread calibration refused");
        h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR&&f.terminal.audienceCenter()==null,"off-thread writes leave state unchanged");
        request.putUUID("terminal_id",UUID.randomUUID());send(f,TheaterPacket.AUDIO_ORIGIN,request);h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"stale terminal ID refused");
        Actor guest=new Actor(h.getLevel());guest.setPos(f.actor.position());TheaterPacket.handle(new TheaterPacket(f.terminal.getBlockPos(),TheaterPacket.AUDIO_ORIGIN,mode(f,"audience")),context(guest));h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"guest cannot select mode");
        f.actor.setPos(f.actor.position().add(20,0,0));send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"audience"));h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"distant owner refused");f.actor.setPos(Vec3.atCenterOf(f.terminal.getBlockPos()));
        f.actor.setGameMode(GameType.SPECTATOR);send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"audience"));h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"spectator refused");f.actor.setGameMode(GameType.SURVIVAL);
        f.b.owner(UUID.randomUUID());send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"audience"));h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"unauthorized peer prevents origin write");f.b.owner(f.actor.getUUID());
        Actor otherDimension=new Actor(h.getLevel().getServer().getLevel(Level.NETHER),f.actor.getUUID());otherDimension.setPos(f.actor.position());h.assertFalse(TheaterAudioSettings.select(f.terminal,otherDimension,mode(f,"audience")),"cross-dimension actor refused");
        Vec3 center=Vec3.atCenterOf(f.terminal.getBlockPos());
        for(Vec3 bad:new Vec3[]{new Vec3(Double.NaN,center.y,center.z),center.add(TheaterAudioAnchor.audienceRange()+1,0,0),new Vec3(center.x,h.getLevel().getMaxBuildHeight(),center.z),new Vec3(30_000_000,center.y,center.z)}){send(f,TheaterPacket.AUDIENCE_CENTER,point(f,bad));h.assertTrue(f.terminal.audienceCenter()==null,"bad audience point refused");}
        BlockPos unloaded=new BlockPos(100000,64,100000);h.assertFalse(h.getLevel().hasChunkAt(unloaded),"remote chunk starts unloaded");send(f,TheaterPacket.AUDIENCE_CENTER,point(f,Vec3.atCenterOf(unloaded)));h.assertFalse(h.getLevel().hasChunkAt(unloaded),"point validation never force-loads chunks");
        var raw=new CompoundTag();raw.put("theater_audio",mode(f,"audience"));var onData=DisplayNetwork.class.getDeclaredMethod("onData",DataPacket.class,IPayloadContext.class);onData.setAccessible(true);onData.invoke(null,new DataSyncPacket(f.terminal.getBlockPos(),raw),context(f.actor));h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"raw NBT cannot write origin");
        send(f,TheaterPacket.AUDIENCE_CENTER,point(f,center.add(.125,0,1.25)));h.assertTrue(f.terminal.audienceCenter()!=null&&f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"valid calibration saves without implicitly selecting");
        h.getLevel().setBlock(f.b.getBlockPos(),Blocks.AIR.defaultBlockState(),3);send(f,TheaterPacket.AUDIO_ORIGIN,mode(f,"audience"));h.assertTrue(f.terminal.audioMode()==TheaterAudioMode.PROJECTOR,"missing member refuses selection atomically");
        h.getLevel().setBlock(f.terminal.getBlockPos(),Blocks.AIR.defaultBlockState(),3);
        h.assertFalse(TheaterAudioSettings.select(f.terminal,f.actor,mode(f,"audience")),"removed terminal object refused by public adapter");
        h.getLevel().setBlock(f.terminal.getBlockPos(),CinemaDisplays.TMS_TERMINAL.get().defaultBlockState(),3);
        h.assertFalse(TheaterAudioSettings.select(f.terminal,f.actor,mode(f,"audience")),"stale terminal object cannot mutate replacement");h.succeed();
    }
}
