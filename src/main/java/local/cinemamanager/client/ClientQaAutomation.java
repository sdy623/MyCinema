package local.cinemamanager.client;

import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.projection.ProjectorGeometry;
import local.cinemamanager.projection.ProjectorStyle;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.qa.MyCinemaQa;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.TerminalNetwork;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.ponder.PonderTutorialCatalog;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.watermedia.api.player.MediaPlayer;

/**
 * Fully opt-in clean-profile client lane. It creates its own world, serves the generated
 * project-owned movie on loopback, drives the real integrated server, reconnects once,
 * records screenshots/evidence, and exits the client with a machine-readable result.
 */
final class ClientQaAutomation {
    private static final String WORLD_ID="MyCinema-QA";
    private static final int PORT=38471;
    private enum Phase { BOOT, JOIN, RUN, OFFLINE, REJOIN, REPLAY, FINAL_OFFLINE, DONE }
    private static Phase phase=Phase.BOOT;
    private static long ticks, phaseTick, deadline=3600;
    private static boolean worldRequested, serverAction, reconnectAction, stoppedAction;
    private static HttpServer mediaServer;
    private static ExecutorService mediaExecutor;
    private static Throwable failure;
    private static ManagerScreen lastScreen;
    private static int screenTicks, nextUiPage=1;
    private static long uiClosedTick=-1,hallFacingTick=-1,imaxEvidenceTick=-1,terminalEvidenceTick=-1;
    private static BlockPos imaxAnchor,terminalAnchor;
    private static long playMin=Long.MAX_VALUE,playMax=-1,pauseMin=Long.MAX_VALUE,pauseMax=-1,replayMin=Long.MAX_VALUE,replayMax=-1;
    private static int replayTerminalCount,replayDisplayCount;
    private static long pauseSegmentMin=Long.MAX_VALUE,pauseSegmentMax=-1,lastPauseSample=Long.MIN_VALUE,pauseDriftMax;
    private static boolean videoDecoded,audioOpened,sharedProjectors,stoppedObserved,seekObserved,imaxCabinetLoaded;
    private static boolean terminalOpenRequested,terminalUiCaptured;
    private static int terminalScreenTicks;
    private static boolean ponderVerified,ponderUiRequested,ponderUiCaptured;
    private static int ponderScreenTicks;
    private static int decoderThreadsAfterFirstUnload=-1,decoderThreadsAfterFinalUnload=-1;
    private static final List<String> screenshots=new ArrayList<>();
    private static final List<String> mediaTrace=new ArrayList<>();
    private static String lastMediaTrace="";

    private ClientQaAutomation(){}

    static void tick(){
        if(!Boolean.getBoolean("mycinema.clientQa")||phase==Phase.DONE)return;
        Minecraft mc=Minecraft.getInstance();ticks++;
        try{
            if(ticks>deadline)throw new IllegalStateException("client QA timeout in "+phase);
            switch(phase){
                case BOOT->boot(mc);
                case JOIN->join(mc);
                case RUN->run(mc,false);
                case OFFLINE->offline(mc,false);
                case REJOIN->rejoin(mc);
                case REPLAY->run(mc,true);
                case FINAL_OFFLINE->offline(mc,true);
                case DONE->{ }
            }
        }catch(Throwable problem){fail(mc,problem);}
    }

    private static void boot(Minecraft mc)throws Exception{
        mc.options.pauseOnLostFocus=false;
        startMediaServer();
        if(mc.level!=null){phase(Phase.JOIN);return;}
        if(worldRequested||ticks<30||mc.screen==null)return;
        if(mc.getLevelSource().levelExists(WORLD_ID)){
            worldRequested=true;mc.createWorldOpenFlows().openWorld(WORLD_ID,()->{});
        }else{
            worldRequested=true;
            var settings=new LevelSettings("MyCinema QA",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,
                    new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(WORLD_ID,settings,WorldOptions.defaultWithRandomSeed(),
                    WorldPresets::createNormalWorldDimensions,mc.screen);
        }
        phase(Phase.JOIN);
    }

    private static void join(Minecraft mc){
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
        cycleUi(mc);
        if(ticks-phaseTick<80||serverAction)return;
        serverAction=true;var server=mc.getSingleplayerServer();var playerId=mc.player.getUUID();
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(playerId);
            if(player==null)throw new IllegalStateException("integrated-server player unavailable");
            MyCinemaQa.startAutomatic(player);
        });
        phase(Phase.RUN);
    }

    private static void run(Minecraft mc,boolean replay)throws Exception{
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
        cycleUi(mc);sampleMedia(mc,replay);
        if(!replay){
            Path serverResult=mc.gameDirectory.toPath().resolve("mycinema-qa-results.json");
            if(!Files.isRegularFile(serverResult))return;
            String serverJson=Files.readString(serverResult);
            if(serverJson.contains("\"success\": false"))throw new IllegalStateException("integrated-server QA failed: "+serverJson);
            if(!serverJson.contains("\"success\": true"))return;
            if(!stoppedObserved||playMax-playMin<500||pauseMin==Long.MAX_VALUE)return;
            if(!screenshots.contains("mycinema-qa-imax-gt.png")){
                if(imaxAnchor==null)return;
                if(imaxEvidenceTick<0){positionForImaxEvidence(mc);imaxEvidenceTick=ticks;return;}
                faceImaxCabinet(mc);
                if(ticks-imaxEvidenceTick<15)return;
                capture(mc,"mycinema-qa-imax-gt.png");
            }
            if(!screenshots.contains("mycinema-qa-tms-terminal.png")){
                if(terminalAnchor==null)return;
                if(terminalEvidenceTick<0){positionForTerminalEvidence(mc);terminalEvidenceTick=ticks;return;}
                faceBlock(mc,terminalAnchor,.72);
                if(ticks-terminalEvidenceTick<15)return;
                capture(mc,"mycinema-qa-tms-terminal.png");
            }
            if(!captureTerminalUi(mc))return;
            if(!capturePonderUi(mc))return;
            capture(mc,"mycinema-qa-complete.png");
            disconnectToTitle(mc,Phase.OFFLINE);
        }else if(replayMax-replayMin>=500&&!stoppedAction){
            stoppedAction=true;var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            server.execute(()->{var player=server.getPlayerList().getPlayer(id);if(player!=null)MyCinemaQa.stopAfterReload(player);});
            capture(mc,"mycinema-qa-reconnect.png");
        }else if(stoppedAction&&ticks-phaseTick>160){
            disconnectToTitle(mc,Phase.FINAL_OFFLINE);
        }
    }

    private static void offline(Minecraft mc,boolean finishing)throws Exception{
        if(mc.level!=null)return;
        var integratedServer=mc.getSingleplayerServer();
        if(integratedServer!=null&&!integratedServer.isStopped())return;
        if(ticks-phaseTick<100)return;
        int threads=decoderThreads();
        if(finishing){decoderThreadsAfterFinalUnload=threads;finish(mc);return;}
        decoderThreadsAfterFirstUnload=threads;
        mc.createWorldOpenFlows().openWorld(WORLD_ID,()->{});phase(Phase.REJOIN);
    }

    private static void rejoin(Minecraft mc){
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
        if(ticks-phaseTick<80||reconnectAction)return;
        reconnectAction=true;var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(id);
            if(player==null)throw new IllegalStateException("reconnected integrated-server player unavailable");
            MyCinemaQa.restartAfterReload(player);
        });
        phase(Phase.REPLAY);
    }

    private static void cycleUi(Minecraft mc){
        if(!(mc.screen instanceof ManagerScreen screen)){lastScreen=null;screenTicks=0;return;}
        if(screen!=lastScreen){lastScreen=screen;screenTicks=0;}else screenTicks++;
        if(screenTicks==16){
            if(nextUiPage<=4)screen.qaSwitchPage(nextUiPage++);else {mc.setScreen(null);uiClosedTick=ticks;}
        }
    }

    private static void sampleMedia(Minecraft mc,boolean replay){
        var base=mc.player.blockPosition();
        for(int x=(base.getX()-80)>>4;x<=(base.getX()+80)>>4;x++)for(int z=(base.getZ()-80)>>4;z<=(base.getZ()+80)>>4;z++){
            if(!mc.level.getChunkSource().hasChunk(x,z))continue;
            for(var tile:mc.level.getChunk(x,z).getBlockEntities().values()){
                if(!(tile instanceof CinemaTerminalTile terminal))continue;
                if(replay)replayTerminalCount++;
                var display=terminal.activeDisplay();if(display==null||display.player()==null)continue;
                MediaPlayer player=display.player();long time=Math.max(0,player.time());
                var authority=player.authority();
                String trace=phase+"/"+player.status()+"/"+(authority==null?"none":authority.status())
                        +"/dataPaused="+terminal.data.paused;
                if(replay)replayDisplayCount++;
                if(!trace.equals(lastMediaTrace)&&mediaTrace.size()<64){
                    mediaTrace.add("phase="+phase+" tick="+ticks+" local="+player.status()+"@"+time+" authority="
                            +(authority==null?"none":authority.status()+"@"+player.authorityTime()+"#"+authority.revision())
                            +" dataPaused="+terminal.data.paused);
                    lastMediaTrace=trace;
                }
                videoDecoded|=player.video()&&player.texture()!=MediaPlayer.NO_TEXTURE&&player.width()>0&&player.height()>0;
                audioOpened|=player.audio()&&player.audioSource()!=MediaPlayer.NO_SOURCE;
                int shared=0;for(var pos:terminal.projectors().keySet())if(mc.level.hasChunkAt(pos)
                        &&mc.level.getBlockEntity(pos) instanceof CinemaProjectorTile projector
                        &&projector.activeDisplay()==display){
                    shared++;
                    if(projector.style()==ProjectorStyle.IMAX_GT&&mc.level.getBlockState(pos).is(CinemaDisplays.IMAX_GT_PROJECTOR.get())){
                        boolean complete=true;
                        for(var offset:ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,projector.machineFacing()).keySet()){
                            var state=mc.level.getBlockState(pos.offset(offset));
                            if(offset.equals(net.minecraft.core.BlockPos.ZERO)?!state.is(CinemaDisplays.IMAX_GT_PROJECTOR.get()):!state.is(local.cinemamanager.CinemaManager.PROJECTOR_PART.get())){complete=false;break;}
                        }
                        imaxCabinetLoaded|=complete;
                        if(complete){imaxAnchor=pos.immutable();terminalAnchor=terminal.getBlockPos().immutable();}
                    }
                }
                sharedProjectors|=terminal.projectors().size()==2&&shared==2;
                if(player.playing()){
                    if(replay){replayMin=Math.min(replayMin,time);replayMax=Math.max(replayMax,time);if(mc.screen==null)capture(mc,"mycinema-qa-replay.png");}
                    else{
                        playMin=Math.min(playMin,time);playMax=Math.max(playMax,time);
                        if(mc.screen==null&&playMax-playMin>=1000&&uiClosedTick>=0&&ticks-uiClosedTick>=10){
                            if(hallFacingTick<0){faceHallScreen(mc);hallFacingTick=ticks;}
                            if(ticks-hallFacingTick>=5)capture(mc,"mycinema-qa-playing.png");
                        }
                    }
                }
                if(!replay&&player.paused()&&playMin!=Long.MAX_VALUE&&playMax-playMin>=500){
                    pauseMin=Math.min(pauseMin,time);pauseMax=Math.max(pauseMax,time);seekObserved|=Math.abs(time-1200)<=700;
                    if(lastPauseSample!=Long.MIN_VALUE&&Math.abs(time-lastPauseSample)>500){
                        // A large discontinuity is the explicit seek-under-pause check,
                        // not clock drift. Start a second stability segment after it.
                        pauseSegmentMin=time;pauseSegmentMax=time;
                    }else{
                        pauseSegmentMin=Math.min(pauseSegmentMin,time);pauseSegmentMax=Math.max(pauseSegmentMax,time);
                    }
                    lastPauseSample=time;pauseDriftMax=Math.max(pauseDriftMax,pauseSegmentMax-pauseSegmentMin);
                    if(pauseMin==time)capture(mc,"mycinema-qa-paused.png");
                }
                if(!replay&&player.stopped()){stoppedObserved=true;capture(mc,"mycinema-qa-stopped.png");}
            }
        }
    }

    private static void capture(Minecraft mc,String name){
        if(screenshots.contains(name))return;screenshots.add(name);
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),ignored->{});
    }

    private static void finish(Minecraft mc)throws Exception{
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("videoDecoded",videoDecoded);result.put("audioOpened",audioOpened);
        result.put("playingAdvanceMs",playMax<0||playMin==Long.MAX_VALUE?-1:playMax-playMin);
        result.put("pauseDriftMs",pauseMin==Long.MAX_VALUE?-1:pauseDriftMax);
        result.put("pausedMinMs",pauseMin==Long.MAX_VALUE?-1:pauseMin);
        result.put("pausedMaxMs",pauseMax);
        result.put("seekObserved",seekObserved);result.put("sharedTwoProjectors",sharedProjectors);
        result.put("imaxGtCabinetLoaded",imaxCabinetLoaded);
        result.put("terminalUiCaptured",terminalUiCaptured);
        result.put("ponderTutorialsVerified",ponderVerified);
        result.put("ponderUiCaptured",ponderUiCaptured);
        result.put("stopObserved",stoppedObserved);result.put("reconnectAdvanceMs",replayMax<0||replayMin==Long.MAX_VALUE?-1:replayMax-replayMin);
        result.put("replayTerminalSamples",replayTerminalCount);result.put("replayDisplaySamples",replayDisplayCount);
        result.put("decoderThreadsAfterFirstUnload",decoderThreadsAfterFirstUnload);
        result.put("decoderThreadsAfterFinalUnload",decoderThreadsAfterFinalUnload);
        result.put("screenshots",List.copyOf(screenshots));
        result.put("mediaTrace",List.copyOf(mediaTrace));
        List<Integer> uiPages=new ArrayList<>();for(int page=0;page<=4;page++)if(Files.isRegularFile(mc.gameDirectory.toPath().resolve("mycinema-ui-page-"+page+".json")))uiPages.add(page);
        result.put("uiPages",uiPages);
        boolean pauseStable=pauseMin!=Long.MAX_VALUE&&pauseDriftMax<=300;
        boolean success=videoDecoded&&audioOpened&&playMax-playMin>=500&&pauseStable&&seekObserved&&sharedProjectors&&imaxCabinetLoaded&&terminalUiCaptured
                &&ponderVerified&&ponderUiCaptured
                &&stoppedObserved&&replayMax-replayMin>=500&&decoderThreadsAfterFinalUnload==0&&uiPages.size()==5;
        result.put("success",success);if(!success)result.put("error","one or more native client/media acceptance checks failed");
        write(mc,result);closeMediaServer();phase=Phase.DONE;mc.stop();
    }

    private static void fail(Minecraft mc,Throwable problem){
        failure=problem;Map<String,Object> result=new LinkedHashMap<>();result.put("success",false);result.put("error",problem.toString());
        result.put("playingAdvanceMs",playMax<0||playMin==Long.MAX_VALUE?-1:playMax-playMin);
        result.put("pausedMinMs",pauseMin==Long.MAX_VALUE?-1:pauseMin);result.put("pausedMaxMs",pauseMax);
        result.put("seekObserved",seekObserved);result.put("stopObserved",stoppedObserved);
        result.put("reconnectAdvanceMs",replayMax<0||replayMin==Long.MAX_VALUE?-1:replayMax-replayMin);
        result.put("replayTerminalSamples",replayTerminalCount);result.put("replayDisplaySamples",replayDisplayCount);
        result.put("mediaTrace",List.copyOf(mediaTrace));
        try{write(mc,result);}catch(Exception ignored){}closeMediaServer();phase=Phase.DONE;mc.stop();
    }

    private static void write(Minecraft mc,Map<String,Object> value)throws IOException{
        Files.writeString(mc.gameDirectory.toPath().resolve("mycinema-client-qa-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }

    private static int decoderThreads(){return (int)Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName).filter(n->n.startsWith("FFThread-")).count();}
    private static void positionForImaxEvidence(Minecraft mc){
        if(mc.player==null||mc.getSingleplayerServer()==null||imaxAnchor==null)return;
        mc.options.hideGui=true;
        var server=mc.getSingleplayerServer();var id=mc.player.getUUID();BlockPos anchor=imaxAnchor;
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(id);
            if(player==null||!player.serverLevel().hasChunkAt(anchor)||!(player.serverLevel().getBlockEntity(anchor) instanceof CinemaProjectorTile projector))return;
            var facing=projector.machineFacing();var camera=anchor.relative(facing,4).relative(facing.getCounterClockWise(),3);
            player.teleportTo(camera.getX()+.5,anchor.getY(),camera.getZ()+.5);
        });
    }
    private static void faceImaxCabinet(Minecraft mc){
        faceBlock(mc,imaxAnchor,.9);
    }
    private static void positionForTerminalEvidence(Minecraft mc){
        if(mc.player==null||mc.getSingleplayerServer()==null||terminalAnchor==null)return;
        mc.options.hideGui=true;
        var server=mc.getSingleplayerServer();var id=mc.player.getUUID();BlockPos anchor=terminalAnchor;
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(id);
            if(player==null||!player.serverLevel().hasChunkAt(anchor)||!(player.serverLevel().getBlockEntity(anchor) instanceof CinemaTerminalTile))return;
            var state=player.serverLevel().getBlockState(anchor);
            var facing=state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    ?state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING):net.minecraft.core.Direction.NORTH;
            // Photograph the console from its side. Looking along the projector
            // throw axis puts the stopped cinema screen directly behind the TMS,
            // which is correctly black but hides the terminal artwork.
            var camera=anchor.relative(facing.getClockWise(),3).relative(facing,3);
            player.teleportTo(camera.getX()+.5,anchor.getY(),camera.getZ()+.5);
        });
    }
    private static void faceBlock(Minecraft mc,BlockPos anchor,double targetHeight){
        if(mc.player==null||anchor==null)return;
        double dx=anchor.getX()+.5-mc.player.getX(),dy=anchor.getY()+targetHeight-mc.player.getEyeY(),dz=anchor.getZ()+.5-mc.player.getZ();
        double horizontal=Math.sqrt(dx*dx+dz*dz);
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));float pitch=(float)Math.toDegrees(Math.atan2(-dy,horizontal));
        mc.player.setYRot(yaw);mc.player.setYHeadRot(yaw);mc.player.setXRot(pitch);
    }
    private static boolean captureTerminalUi(Minecraft mc){
        if(terminalUiCaptured)return true;
        if(!(mc.screen instanceof CinemaTerminalScreen screen)){
            terminalScreenTicks=0;
            if(!terminalOpenRequested&&mc.getSingleplayerServer()!=null&&mc.player!=null&&terminalAnchor!=null){
                terminalOpenRequested=true;var server=mc.getSingleplayerServer();var id=mc.player.getUUID();BlockPos anchor=terminalAnchor;
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(id);
                    if(player!=null&&player.serverLevel().getBlockEntity(anchor) instanceof CinemaTerminalTile terminal)
                        TerminalNetwork.open(player,terminal);
                });
            }
            return false;
        }
        mc.options.hideGui=false;
        terminalScreenTicks++;
        if(terminalScreenTicks==10)capture(mc,"mycinema-qa-tms-media.png");
        else if(terminalScreenTicks==16)screen.qaSwitchTab(1);
        else if(terminalScreenTicks==24)capture(mc,"mycinema-qa-tms-group.png");
        else if(terminalScreenTicks==30)screen.qaSwitchTab(2);
        else if(terminalScreenTicks==38){
            capture(mc,"mycinema-qa-tms-audio.png");mc.setScreen(null);terminalUiCaptured=true;return true;
        }
        return false;
    }
    private static boolean capturePonderUi(Minecraft mc){
        if(ponderUiCaptured)return true;
        if(!ponderVerified){
            PonderIndex.registerAll();
            var scenes=PonderIndex.getSceneAccess();
            for(var spec:PonderTutorialCatalog.scenes()){
                var component=CinemaManager.id(spec.componentPath());
                if(!scenes.doScenesExistForId(component)||scenes.compile(component).isEmpty())
                    throw new IllegalStateException("Ponder tutorial missing or uncompileable: "+component);
            }
            ponderVerified=true;
        }
        if(!(mc.screen instanceof PonderUI)){
            ponderScreenTicks=0;
            if(!ponderUiRequested){
                ponderUiRequested=true;
                mc.setScreen(PonderUI.of(CinemaManager.id("manager_terminal")));
            }
            return false;
        }
        mc.options.hideGui=false;
        ponderScreenTicks++;
        if(ponderScreenTicks>=45){
            capture(mc,"mycinema-qa-ponder-manager.png");
            mc.setScreen(null);
            ponderUiCaptured=true;
            return true;
        }
        return false;
    }
    private static void faceHallScreen(Minecraft mc){
        if(mc.player==null)return;
        // The right-hand projector pair throws the image six blocks south,
        // away from the audience. From the manager this screen is about
        // near south once the ten-block projected plane is included, keeping
        // the decoded frame centered in the evidence image.
        mc.player.setYRot(-5);mc.player.setYHeadRot(-5);mc.player.setXRot(-5);
    }
    private static void disconnectToTitle(Minecraft mc,Phase next){
        // Match the vanilla pause-menu sequence. ClientLevel.disconnect() asks the
        // integrated server to halt; Minecraft.disconnect() then waits for its save.
        // Advance first because that wait runs nested client ticks.
        phase(next);if(mc.level!=null)mc.level.disconnect();mc.disconnect(new TitleScreen());
    }
    private static void phase(Phase next){
        System.out.println("MYCINEMA_CLIENT_QA phase="+phase+" -> "+next+" tick="+ticks);
        phase=next;phaseTick=ticks;lastScreen=null;screenTicks=0;
        if(next==Phase.REPLAY)lastMediaTrace="";
    }

    private static void startMediaServer()throws IOException{
        if(mediaServer!=null)return;
        Path file=Path.of(System.getProperty("mycinema.qaMediaFile","")).toAbsolutePath().normalize();
        if(!Files.isRegularFile(file))throw new IOException("QA movie fixture missing: "+file);
        mediaServer=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),0);
        mediaServer.createContext("/mycinema-qa.mp4",exchange->serve(exchange,file));
        mediaExecutor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MyCinema-QA-Loopback");t.setDaemon(true);return t;});
        mediaServer.setExecutor(mediaExecutor);mediaServer.start();
    }

    private static void serve(HttpExchange exchange,Path file)throws IOException{
        try{
            String method=exchange.getRequestMethod();if(!method.equals("GET")&&!method.equals("HEAD")){exchange.sendResponseHeaders(405,-1);return;}
            long size=Files.size(file);exchange.getResponseHeaders().set("Content-Type","video/mp4");exchange.getResponseHeaders().set("Accept-Ranges","bytes");
            // WaterMedia may probe byte ranges. The fixture is tiny, so a complete 200 response is deterministic.
            exchange.sendResponseHeaders(200,method.equals("HEAD")?-1:size);
            if(method.equals("GET"))try(var in=Files.newInputStream(file);var out=exchange.getResponseBody()){in.transferTo(out);}
        }finally{exchange.close();}
    }

    private static void closeMediaServer(){if(mediaServer!=null)mediaServer.stop(0);if(mediaExecutor!=null)mediaExecutor.shutdownNow();mediaServer=null;mediaExecutor=null;}
}
