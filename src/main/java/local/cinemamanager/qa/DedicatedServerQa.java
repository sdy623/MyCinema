package local.cinemamanager.qa;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.EnrollmentRegistry;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.automation.CinemaAutomation;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Two-boot, no-player dedicated-server smoke lane. Enabled only by the release task. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class DedicatedServerQa {
    private static final UUID OWNER=UUID.fromString("4f5afca7-a50a-4eeb-9493-cff83d3a1e2a");
    private static MinecraftServer server;
    private static BlockPos base;
    private static String showId;
    private static boolean sawRunning,finished;
    private static long deadline;
    private static final List<String> checks=new ArrayList<>();

    private DedicatedServerQa(){}

    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("mycinema.dedicatedQa"))return;
        server=event.getServer();deadline=server.overworld().getGameTime()+600;
        try{
            check(!server.isSingleplayer(),"dedicated server runtime");
            version("mycinema","0.1.0");version("waterframes","3.0.0-alpha.1");version("watermedia","3.0.0");
            ServerLevel level=server.overworld();base=level.getSharedSpawnPos().offset(0,5,0);
            int phase=Integer.getInteger("mycinema.dedicatedQaPhase",1);
            if(phase==1)firstBoot(level);else if(phase==2)reloadBoot(level);else throw new IllegalStateException("invalid dedicated QA phase");
        }catch(Throwable problem){fail(problem);}
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(server==null||finished||event.getServer()!=server)return;
        try{
            if(server.overworld().getGameTime()>deadline)throw new IllegalStateException("dedicated QA timeout");
            ManagerTile manager=manager(server.overworld());ManagerState.Show show=manager.state.show(showId);
            sawRunning|=show.status==ManagerState.Status.RUNNING;
            if(show.status==ManagerState.Status.FAILED||show.status==ManagerState.Status.MISSED||show.status==ManagerState.Status.STOP_PENDING)
                throw new IllegalStateException("scheduled show ended in "+show.status+": "+show.outcome);
            if(show.status!=ManagerState.Status.COMPLETED)return;
            check(sawRunning,"scheduler observed RUNNING before COMPLETED");
            CinemaTerminalTile terminal=manager.programmeTerminal(manager.state.halls.getFirst().id);
            check(terminal!=null&&terminal.clock()!=null&&terminal.clock().stopped(),"scheduled stop reached WaterMedia server clock");
            check(EnrollmentRegistry.get(server.overworld()).owns(terminal.identity(),manager.identity),"enrollment authority persisted");
            check(terminal.projectors().size()==2&&terminal.playbackAvailable(),"reciprocal projector group loaded");
            finish();
        }catch(Throwable problem){fail(problem);}
    }

    private static void firstBoot(ServerLevel level){
        for(int x=-3;x<=3;x++)for(int z=-1;z<=8;z++)level.setBlock(base.offset(x,-1,z),Blocks.SMOOTH_STONE.defaultBlockState(),Block.UPDATE_ALL);
        level.setBlock(base,CinemaManager.MANAGER.get().defaultBlockState(),Block.UPDATE_ALL);
        level.setBlock(base.offset(0,0,2),CinemaDisplays.TMS_TERMINAL.get().defaultBlockState(),Block.UPDATE_ALL);
        level.setBlock(base.offset(-2,0,6),CinemaDisplays.CINEMA_PROJECTOR.get().defaultBlockState(),Block.UPDATE_ALL);
        level.setBlock(base.offset(2,0,6),CinemaDisplays.CINEMA_PROJECTOR.get().defaultBlockState(),Block.UPDATE_ALL);
        ManagerTile manager=manager(level);manager.initializePlacement(OWNER);manager.state.name="MyCinema Dedicated QA persisted";manager.state.timezone="UTC";
        CinemaTerminalTile terminal=(CinemaTerminalTile)level.getBlockEntity(base.offset(0,0,2));terminal.owner(OWNER);
        for(BlockPos pos:List.of(base.offset(-2,0,6),base.offset(2,0,6))){
            CinemaProjectorTile projector=(CinemaProjectorTile)level.getBlockEntity(pos);projector.owner(OWNER);projector.bind(terminal);
        }
        check(terminal.projectors().size()==2&&terminal.playbackAvailable(),"fresh reciprocal projector group");
        ManagerState.Hall hall=new ManagerState.Hall();hall.name="Dedicated QA Hall";hall.terminalPos=terminal.getBlockPos().asLong();hall.terminalIdentity=terminal.identity().toString();hall.override=false;manager.state.halls.add(hall);
        check(EnrollmentRegistry.get(level).claim(terminal.identity(),manager.identity),"fresh terminal enrollment");
        terminal.automationGrant(new CinemaAutomation.Grant(terminal.identity(),manager.identity,OWNER,manager.getBlockPos(),GameType.CREATIVE));
        manager.state.schedulerEnabled=true;showId=schedule(manager,hall,"water://dedicated-qa-first");manager.dirty();
    }

    private static void reloadBoot(ServerLevel level){
        ManagerTile manager=manager(level);check("MyCinema Dedicated QA persisted".equals(manager.state.name),"manager NBT survived restart");
        check(manager.state.halls.size()==1&&manager.state.shows.stream().anyMatch(s->s.status==ManagerState.Status.COMPLETED),"completed schedule history survived restart");
        ManagerState.Hall hall=manager.state.halls.getFirst();CinemaTerminalTile terminal=manager.programmeTerminal(hall.id);
        check(terminal!=null&&terminal.projectors().size()==2&&terminal.playbackAvailable(),"device topology survived restart");
        check(terminal.automationGrant()!=null&&terminal.automationGrant().matches(terminal.identity(),manager.identity,OWNER,manager.getBlockPos()),"automation grant survived restart");
        manager.state.schedulerEnabled=true;hall.override=false;showId=schedule(manager,hall,"water://dedicated-qa-reload");manager.dirty();
    }

    private static String schedule(ManagerTile manager,ManagerState.Hall hall,String source){
        long now=System.currentTimeMillis();ManagerState.Show show=new ManagerState.Show();show.hallId=hall.id;show.title="Dedicated server scheduled playback";show.url=source;show.start=now+1000;show.end=now+3000;manager.state.shows.add(show);return show.id;
    }

    private static ManagerTile manager(ServerLevel level){
        if(level.getBlockEntity(base) instanceof ManagerTile manager)return manager;
        throw new IllegalStateException("dedicated QA manager missing at "+base.toShortString());
    }

    private static void version(String id,String expected){
        String actual=ModList.get().getModContainerById(id).orElseThrow(()->new IllegalStateException("missing mod "+id)).getModInfo().getVersion().toString();
        check(expected.equals(actual),id+" version "+actual);
    }

    private static void check(boolean value,String name){if(!value)throw new IllegalStateException(name);checks.add(name);}

    private static void finish(){
        Map<String,Object> result=new LinkedHashMap<>();result.put("success",true);result.put("phase",Integer.getInteger("mycinema.dedicatedQaPhase",1));result.put("checks",List.copyOf(checks));write(result);
        finished=true;server.saveEverything(true,true,true);server.halt(false);
    }

    private static void fail(Throwable problem){
        Map<String,Object> result=new LinkedHashMap<>();result.put("success",false);result.put("phase",Integer.getInteger("mycinema.dedicatedQaPhase",1));result.put("checks",List.copyOf(checks));result.put("error",problem.toString());write(result);
        finished=true;if(server!=null)server.halt(false);
    }

    private static void write(Map<String,Object> result){
        try{Path dir=Path.of(System.getProperty("mycinema.qaResultDirectory","."));Files.createDirectories(dir);Files.writeString(dir.resolve("mycinema-dedicated-qa-phase-"+Integer.getInteger("mycinema.dedicatedQaPhase",1)+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception problem){problem.printStackTrace();}
        System.out.println("MYCINEMA_DEDICATED_QA "+result);
    }
}
