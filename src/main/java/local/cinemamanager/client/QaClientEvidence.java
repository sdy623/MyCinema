package local.cinemamanager.client;
import com.google.gson.GsonBuilder;
import local.cinemamanager.CinemaManager;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.mirror.CinemaMirrors;
import me.srrapero720.waterui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import java.nio.file.*;
import java.util.*;
/** Opt-in evidence, not a production control path. Does not claim pixels or audible sound from field values. */
public final class QaClientEvidence {
    private static final String[][] FIELDS={
        {"seek_bar","open","play","pause","stop","close","lights_off","lights_half","lights_on","auto","admission_open","admission_close","title","url","load","load_show"},
        {"name","rename","nearest","remove"},
        {"timezone","zone_save","enable","disable","filter","show_row_0","show_row_1","show_row_2","show_row_3","show_row_4","add_show","edit_show","load_show","show_previous","show_next","cancel","delete_show"},
        {},
        {"preset_entry","preset_trailer","preset_feature","preset_pause","preset_exit","preset_cleaning","preset_previous","preset_next","light_main","light_aisle","light_exit","light_accent","preset_save"},
        {"show_title","show_url","show_time","show_minutes","save_show","editor_back"}
    };
    public static void ui(ManagerScreen screen,int page){
        if(!Boolean.getBoolean("mycinema.qa"))return;
        List<String> ids=new ArrayList<>(List.of("tab0","tab1","tab2","tab3","tab4","previous","next","scope","done","emergency_status","emergency_unlock","emergency_restore","manager_body"));ids.addAll(List.of(FIELDS[page]));
        List<String> missing=new ArrayList<>(),clipped=new ArrayList<>(),scrollFields=new ArrayList<>(),scrollToReach=new ArrayList<>();Map<String,String> rects=new LinkedHashMap<>();
        var body=screen.get(CinemaManager.id("manager_body"));
        for(String id:ids){
            var e=screen.get(CinemaManager.id(id));if(e==null){missing.add(id);continue;}
            rects.put(id,e.bounds.toString());boolean scroll=false;
            for(var parent=e.parent();parent!=null;parent=parent.parent())if(parent==body){scroll=true;break;}
            if(scroll)scrollFields.add(id);
            if(e.visible&&!e.hidden){
                boolean invalidSize=e.bounds.width<=0||e.bounds.height<=0;
                boolean outside=e.bounds.x<0||e.bounds.y<0||e.bounds.right()>screen.width||e.bounds.bottom()>screen.height;
                if(invalidSize||outside&&!scroll)clipped.add(id);
                if(scroll&&body!=null&&(e.bounds.y<body.bounds.y||e.bounds.bottom()>body.bounds.bottom()))scrollToReach.add(id);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("page",page);result.put("language",Minecraft.getInstance().options.languageCode);result.put("screenWidth",screen.width);result.put("screenHeight",screen.height);result.put("errorTheme",screen.theme()==Theme.ERROR);result.put("missing",missing);result.put("clipped",clipped);result.put("rectangles",rects);result.put("scrollFields",scrollFields);result.put("scrollToReach",scrollToReach);result.put("passScope","Fixed chrome bounds and required element existence only; scroll fields require separate native scroll/hit checks.");result.put("pass",screen.theme()!=Theme.ERROR&&missing.isEmpty()&&clipped.isEmpty());
        write("mycinema-ui-page-"+page+".json",result);
    }
    public static void commands(RegisterClientCommandsEvent e){if(Boolean.getBoolean("mycinema.qa"))e.getDispatcher().register(Commands.literal("mycinemaclientqa").executes(c->{media();return 1;}));}
    private static void media(){
        Minecraft mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        List<Map<String,Object>> terminals=new ArrayList<>();BlockPos base=mc.player.blockPosition();boolean pass=true;int active=0;
        for(int x=(base.getX()-80)>>4;x<=(base.getX()+80)>>4;x++)for(int z=(base.getZ()-80)>>4;z<=(base.getZ()+80)>>4;z++){
            if(!mc.level.getChunkSource().hasChunk(x,z))continue;
            for(var be:mc.level.getChunk(x,z).getBlockEntities().values())if(be instanceof CinemaTerminalTile t){
                var display=t.activeDisplay();if(display==null)continue;active++;int shared=0;boolean separatePlayer=false;
                for(BlockPos p:t.projectors().keySet())if(mc.level.hasChunkAt(p)&&mc.level.getBlockEntity(p) instanceof CinemaProjectorTile projector){if(projector.activeDisplay()==display)shared++;if(projector.follower()!=null)separatePlayer=true;}
                boolean valid=!display.isNoEngine()&&display.player()!=null&&shared==t.projectors().size()&&!separatePlayer;pass&=valid;
                Map<String,Object> one=new LinkedHashMap<>();one.put("terminal",t.getBlockPos().toShortString());one.put("displayClass",display.getClass().getName());one.put("playerClass",display.player()==null?"none":display.player().getClass().getName());one.put("status",display.status().toString());one.put("sourceTimeMs",display.time());one.put("texture",String.valueOf(display.textureId()));one.put("playerIdentity",System.identityHashCode(display.player()));one.put("sharedProjectors",shared);one.put("linkedProjectors",t.projectors().size());one.put("separateProjectorPlayer",separatePlayer);one.put("noEngine",display.isNoEngine());one.put("pass",valid);terminals.add(one);
            }
        }
        List<Map<String,Object>> mirrors=new ArrayList<>();
        Set<Object> players=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int x=(base.getX()-80)>>4;x<=(base.getX()+80)>>4;x++)for(int z=(base.getZ()-80)>>4;z<=(base.getZ()+80)>>4;z++){
            if(!mc.level.getChunkSource().hasChunk(x,z))continue;
            for(var be:mc.level.getChunk(x,z).getBlockEntities().values()){
                if(be instanceof DisplayTile tile){var display=CinemaMirrorFrames.active(tile);if(display!=null&&display.player()!=null&&!display.isReleased())players.add(display.player());}
                if(be instanceof local.cinemamanager.InfoTile tile&&tile.screenMode==local.cinemamanager.advertising.ScreenMode.MEDIA&&tile.publishedMirror!=null){
                    var source=CinemaMirrors.matchLoaded(mc.level,tile.publishedMirror);
                    DisplayTile authority=source instanceof CinemaProjectorTile projector?projector.terminal():source;
                    var display=CinemaMirrorFrames.active(authority);
                    var frame=CinemaMirrorFrames.current(mc.level,tile.publishedMirror);
                    boolean same=display!=null&&frame!=null&&frame.kind()==CinemaMirrorFrames.Kind.MEDIA&&frame.texture().equals(display.textureId())&&frame.width()==display.width()&&frame.height()==display.height();
                    Map<String,Object> mirror=new LinkedHashMap<>();mirror.put("panel",tile.getBlockPos().toShortString());mirror.put("source",authority==null?"none":authority.getBlockPos().toShortString());mirror.put("borrowedFrameAvailable",frame!=null);mirror.put("sameTextureAndDimensions",same);mirror.put("sourceTimeMs",display==null?-1:display.time());mirror.put("sourceStatus",display==null?"none":display.status().toString());mirror.put("sourcePlayerIdentity",display==null||display.player()==null?0:System.identityHashCode(display.player()));mirror.put("borrowedTexture",frame==null||frame.texture()==null?"none":frame.texture().toString());mirrors.add(mirror);
                }
            }
        }
        List<String> decoderThreads=Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName).filter(name->name.startsWith("FFThread-")).sorted().toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("sampleUtc",java.time.Instant.now().toString());result.put("sampleNanoTime",System.nanoTime());result.put("loadedDistinctDisplayPlayers",players.size());result.put("borrowedMediaPanels",mirrors);result.put("ffmpegThreads",decoderThreads);result.put("allBorrowedFramesMatchSource",!mirrors.isEmpty()&&mirrors.stream().allMatch(m->Boolean.TRUE.equals(m.get("sameTextureAndDimensions"))));result.put("activeTerminalDisplays",active);result.put("terminals",terminals);result.put("sharedPlaybackObjectPass",pass&&active>0);result.put("notProvenByThisCheck",List.of("visual synchronization pixels","perceptually audible audio","all clients viewing together"));write("mycinema-client-media-proof.json",result);write("mycinema-client-media-proof-"+System.currentTimeMillis()+".json",result);mc.player.displayClientMessage(Component.literal("MyCinema 客户端共享播放器检查："+(pass&&active>0?"PASS":"待确认/失败")+"；活动 TMS "+active),false);
    }
    private static void write(String name,Object value){try{Files.writeString(Path.of(name),new GsonBuilder().setPrettyPrinting().create().toJson(value));}catch(Exception e){System.err.println("MyCinema QA evidence write failed: "+e);}}
}
