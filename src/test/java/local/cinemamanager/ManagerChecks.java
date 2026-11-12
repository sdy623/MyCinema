package local.cinemamanager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.model.ManagerState.*;
import local.cinemamanager.library.FilmNetwork;
import local.cinemamanager.projection.CinemaProjectorItem;
import local.cinemamanager.projection.ProjectorGeometry;
import local.cinemamanager.projection.ProjectorStyle;
import local.cinemamanager.theater.TerminalNetwork;
import local.cinemamanager.theater.TerminalGeometry;
import net.minecraft.core.Direction;
import me.srrapero720.waterui.format.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class ManagerChecks {
    private static int assertions;
    static void check(boolean condition,String name){assertions++;if(!condition)throw new AssertionError(name);}
    static void rejects(Runnable action,String name){boolean threw=false;try{action.run();}catch(RuntimeException e){threw=true;}check(threw,name);}
    static ManagerState fixture(){ManagerState m=new ManagerState();Hall h=new Hall();h.terminalIdentity=UUID.randomUUID().toString();m.halls.add(h);m.setTimezone("Asia/Shanghai");return m;}
    static long at(String value){return Instant.parse(value).toEpochMilli();}
    static double meanLuma(Path path)throws Exception {var image=ImageIO.read(path.toFile());double sum=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int rgb=image.getRGB(x,y);int r=rgb>>16&255,g=rgb>>8&255,b=rgb&255;sum+=.2126*r+.7152*g+.0722*b;}return sum/(image.getWidth()*image.getHeight());}
    static class Count implements Executor {int starts,stops;String fail;public String start(Hall h,Show s){starts++;return fail;}public String stop(Hall h){stops++;return fail;}}
    public static void main(String[] args)throws Exception{
        var projectorStyles=CinemaProjectorItem.creativeStyles();
        check(projectorStyles.size()==2,"creative inventory exposes both MyCinema projector variants");
        check(projectorStyles.get(0)==ProjectorStyle.CINEMA,"first projector entry is Cinema");
        check(projectorStyles.get(1)==ProjectorStyle.IMAX_GT,"second projector entry is IMAX GT");
        check(!CinemaProjectorItem.translationKey(ProjectorStyle.CINEMA).equals(CinemaProjectorItem.translationKey(ProjectorStyle.IMAX_GT)),"projector variants have distinct item names");
        var cinemaGeometry=ProjectorGeometry.cells(ProjectorStyle.CINEMA,Direction.NORTH);
        var imaxGeometry=ProjectorGeometry.cells(ProjectorStyle.IMAX_GT,Direction.NORTH);
        check(cinemaGeometry.size()==1&&cinemaGeometry.containsKey(net.minecraft.core.BlockPos.ZERO),"Cinema cabinet artwork owns one collision cell");
        check(imaxGeometry.size()==18&&imaxGeometry.containsKey(net.minecraft.core.BlockPos.ZERO)
                &&imaxGeometry.keySet().stream().anyMatch(pos->pos.getX()<0)&&imaxGeometry.keySet().stream().anyMatch(pos->pos.getX()>0),
                "IMAX GT parallel cabinets own their complete centered 3x2x3 footprint");
        check(imaxGeometry.values().stream().anyMatch(shape->shape.toAabbs().stream().anyMatch(box->box.getXsize()<0.99||box.getYsize()<0.99||box.getZsize()<0.99)),"IMAX GT collision follows detailed cabinet cuboids instead of invisible full blocks");
        check(Files.isRegularFile(Path.of("src/main/resources/assets/mycinema/models/item/imax_gt_projector.json"))&&Files.isRegularFile(Path.of("src/main/resources/assets/mycinema/models/block/imax_gt_projector.json")),"IMAX GT item and cabinet artwork are packaged by MyCinema");
        JsonObject imaxModel=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mycinema/models/block/imax_gt_projector.json"))).getAsJsonObject();
        check(imaxModel.getAsJsonArray("elements").size()==64,"IMAX GT artwork contains two complete parallel projector cabinets");
        check(imaxModel.has("ambientocclusion")&&!imaxModel.get("ambientocclusion").getAsBoolean(),
                "oversized IMAX GT artwork disables vanilla full-block ambient occlusion so its textures stay lit");
        check(imaxModel.getAsJsonArray("elements").asList().stream().flatMap(element->java.util.stream.Stream.of(
                        element.getAsJsonObject().getAsJsonArray("from"),element.getAsJsonObject().getAsJsonArray("to")))
                .flatMap(coordinates->coordinates.asList().stream()).allMatch(value->value.getAsDouble()>=-16&&value.getAsDouble()<=32),
                "IMAX GT artwork stays inside Minecraft's renderable element bounds");
        long imaxLenses=imaxModel.getAsJsonArray("elements").asList().stream().filter(e->e.getAsJsonObject().get("name").getAsString().contains("optical front")).count();
        check(imaxLenses==2,"IMAX GT artwork exposes one optical front per cabinet");
        String imaxJson=imaxModel.toString();
        check(!imaxJson.contains("waterframes:")&&!imaxJson.contains("cinema_v22:"),"IMAX GT artwork resolves only MyCinema-owned runtime resources");
        Path imaxTextures=Path.of("src/main/resources/assets/mycinema/textures/block/imax_gt");
        check(meanLuma(imaxTextures.resolve("cabinet.png"))>=70&&meanLuma(imaxTextures.resolve("edge.png"))>=55,
                "IMAX GT body textures remain visible under ordinary Minecraft lighting instead of rendering as a black mass");
        Path tmsBlock=Path.of("src/main/resources/assets/mycinema/models/block/tms_terminal.json");
        Path tmsState=Path.of("src/main/resources/assets/mycinema/blockstates/tms_terminal.json");
        Path tmsItem=Path.of("src/main/resources/assets/mycinema/models/item/tms_terminal.json");
        check(Files.isRegularFile(tmsBlock)&&Files.isRegularFile(tmsState)&&Files.isRegularFile(tmsItem),"TMS terminal block, facing state and item artwork are packaged by MyCinema");
        JsonObject tmsModel=JsonParser.parseString(Files.readString(tmsBlock)).getAsJsonObject();
        check(tmsModel.getAsJsonArray("elements").size()==32,"TMS terminal restores the complete private terminal cabinet artwork");
        String tmsAssets=Files.readString(tmsState)+Files.readString(tmsItem)+tmsModel;
        check(!tmsAssets.contains("waterframes:"),"TMS terminal no longer renders the WaterFrames television-box placeholder");
        check(TerminalGeometry.shape(Direction.NORTH).toAabbs().size()>1,"TMS selection and collision follow the restored console cuboids");
        check(!TerminalGeometry.shape(Direction.NORTH).equals(TerminalGeometry.shape(Direction.EAST)),"TMS console collision rotates with its horizontal facing");
        ManagerState empty=new ManagerState();check(!empty.schedulerEnabled,"scheduler default off");check(empty.timezone.isEmpty(),"no implicit timezone");rejects(()->empty.enable(true),"cannot enable unconfigured");
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        for(boolean admin:List.of(false,true))for(boolean spectator:List.of(false,true))for(boolean protection:List.of(false,true))for(double distance:List.of(0.,64.,64.01,10000.))for(UUID actor:List.of(owner,other)){
            boolean expected=!spectator&&protection&&distance<=64&&(admin||actor.equals(owner));
            check(ManagerState.identityAllowed(owner,actor,admin,spectator,protection,distance)==expected,"permission matrix");
        }
        check(!ManagerState.identityAllowed(owner,null,true,false,true,0),"null actor denied");
        check(!ManagerState.identityAllowed(null,other,false,false,true,0),"unowned controller never guest-controlled");
        ManagerState m=fixture();Hall h=m.halls.getFirst();Count count=new Count();
        long now=at("2030-01-01T00:00:00Z");Show s=m.addShow(h.id,"测试影片","water://demo","2030-01-01 09:00",10,now);
        check(s.start==at("2030-01-01T01:00:00Z"),"timezone conversion");check(s.end-s.start==600000,"duration minutes");
        rejects(()->m.addShow(h.id,"碰撞","water://x","2030-01-01 09:01",1,now),"overlap rejected");
        rejects(()->m.addShow(h.id,"past","water://x","2030-01-01 08:00",1,now),"past rejected");
        rejects(()->m.addShow(h.id,"bad","water://x","2030-02-30 09:00",1,now),"invalid calendar rejected");
        rejects(()->m.addShow(h.id,"bad","water://x","2030-01-01 09:30",0,now),"zero duration rejected");
        rejects(()->m.addShow(h.id,"bad","water://x","2030-01-01 09:30",1441,now),"duration upper bound");
        m.enable(true);rejects(()->m.setTimezone("UTC"),"enabled zone change refused");
        check(!m.tick(s.start-1,count),"before start unchanged");check(count.starts==0,"before start no execute");
        check(m.tick(s.start,count),"exact boundary starts");check(s.status==Status.RUNNING&&count.starts==1,"one start");check(h.open&&h.transport==Transport.PLAYING&&h.lights==0,"opening scene");
        for(int i=0;i<100;i++)m.tick(s.start+i,count);check(count.starts==1,"repeat ticks never duplicate");
        ManagerState restored=StateCodec.decode(StateCodec.encode(m));Count second=new Count();restored.tick(s.start+10000,second);check(second.starts==0,"reload running not replayed");
        restored.tick(s.end,second);check(second.stops==1&&restored.shows.getFirst().status==Status.COMPLETED,"reload ends once");for(int i=0;i<100;i++)restored.tick(s.end+i,second);check(second.stops==1,"no duplicate stop");
        check(restored.halls.getFirst().lights==12&&restored.halls.getFirst().transport==Transport.STOPPED,"end lights restored");
        ManagerState off=fixture();Show os=off.addShow(off.halls.getFirst().id,"off","water://x","2030-01-01 09:00",1,now);Count oc=new Count();off.tick(os.start,oc);check(os.status==Status.MISSED&&oc.starts==0,"default-off never starts");off.enable(true);off.tick(os.start+10,oc);check(oc.starts==0,"enabling after missed never replays");
        ManagerState late=fixture();late.enable(true);Show ls=late.addShow(late.halls.getFirst().id,"late","water://x","2030-01-01 09:00",2,now);Count lc=new Count();late.tick(ls.start+30001,lc);check(ls.status==Status.MISSED&&lc.starts==0,"late window no catch-up");
        ManagerState grace=fixture();grace.enable(true);Show gs=grace.addShow(grace.halls.getFirst().id,"grace","water://x","2030-01-01 09:00",2,now);Count gc=new Count();grace.tick(gs.start+30000,gc);check(gs.status==Status.RUNNING&&gc.starts==1,"grace boundary");
        ManagerState fail=fixture();fail.enable(true);Show fs=fail.addShow(fail.halls.getFirst().id,"fail","water://x","2030-01-01 09:00",2,now);Count fc=new Count();fc.fail="permission revoked";fail.tick(fs.start,fc);check(fs.status==Status.FAILED&&!fail.halls.getFirst().open,"failed start no open scene");for(int i=0;i<10;i++)fail.tick(fs.start+i,fc);check(fc.starts==1,"failed start not retried");
        ManagerState manual=fixture();manual.enable(true);Hall mh=manual.halls.getFirst();Show ms=manual.addShow(mh.id,"manual","water://x","2030-01-01 09:00",2,now);Count mc=new Count();manual.tick(ms.start,mc);manual.manual(mh,Scene.PAUSE);check(ms.status==Status.OVERRIDDEN&&mh.override,"manual takes ownership");manual.tick(ms.end,mc);check(mc.stops==0,"schedule cannot stop manual takeover");check(mh.transport==Transport.PAUSED&&mh.lights==7,"pause scene");manual.manual(mh,Scene.AUTO);check(!mh.override&&mh.transport==Transport.PAUSED,"rearm does not play");
        ManagerState blocked=fixture();blocked.enable(true);Hall bh=blocked.halls.getFirst();blocked.manual(bh,Scene.OPEN);Show bs=blocked.addShow(bh.id,"blocked","water://x","2030-01-01 09:00",2,now);Count bc=new Count();blocked.tick(bs.start,bc);check(bs.status==Status.MISSED&&bc.starts==0,"override suppresses upcoming start");
        for(Status activeStatus:List.of(Status.PREPARING,Status.RUNNING,Status.STOP_PENDING))for(boolean priorOverride:List.of(false,true)){
            ManagerState retained=fixture();Hall currentHall=retained.halls.getFirst();currentHall.override=priorOverride;
            Show currentShow=retained.addShow(currentHall.id,"current film","water://x","2030-01-01 09:00",2,now);currentShow.status=activeStatus;
            for(Scene operational:List.of(Scene.OPEN,Scene.ADMISSION_OPEN,Scene.ADMISSION_CLOSE,Scene.LIGHTS_OFF,Scene.LIGHTS_HALF,Scene.LIGHTS_ON)){
                retained.manual(currentHall,operational,true);
                check(currentShow.status==activeStatus&&currentHall.override==priorOverride,"current programme identity survives operational scene "+operational);
            }
            retained.manual(currentHall,Scene.CLOSE,true);
            check(currentShow.status==Status.OVERRIDDEN&&!currentHall.open,"CLOSE remains explicit takeover/dismissal with current programme");
        }
        for(Scene transportScene:List.of(Scene.PLAY,Scene.PAUSE,Scene.STOP)){
            ManagerState retained=fixture();Hall currentHall=retained.halls.getFirst();
            Show currentShow=retained.addShow(currentHall.id,"current film","water://x","2030-01-01 09:00",2,now);currentShow.status=Status.RUNNING;
            retained.manual(currentHall,transportScene,true);
            check(currentShow.status==Status.OVERRIDDEN&&currentHall.override,"raw transport still takes over; programme transport uses its own validated path "+transportScene);
        }
        Hall titled=new Hall();titled.current="电影标题";for(Scene scene:List.of(Scene.STOP,Scene.CLOSE,Scene.PLAY,Scene.PAUSE)){ManagerState.apply(titled,scene);check(titled.current.equals("电影标题"),"transport preserves media title "+scene);}
        Hall stages=new Hall();ManagerState.apply(stages,Scene.OPEN);check(stages.open&&stages.admissionHeld,"admission opens hold and business");ManagerState.apply(stages,Scene.PLAY);check(stages.open&&!stages.admissionHeld,"feature closes door hold without closing business");ManagerState.apply(stages,Scene.PAUSE);check(stages.open&&!stages.admissionHeld,"pause retains light seal");ManagerState.apply(stages,Scene.STOP);check(stages.open&&stages.admissionHeld,"end opens admission for exit");ManagerState.apply(stages,Scene.CLOSE);check(!stages.open&&!stages.admissionHeld,"closed venue closes hold");ManagerState.apply(stages,Scene.ADMISSION_OPEN);check(!stages.open&&stages.admissionHeld,"manual door hold independent of venue");ManagerState.applyStage(stages,LightPreset.TRAILER);check(!stages.admissionHeld,"trailer stage closes hold");
        ManagerState dst=fixture();dst.setTimezone("America/New_York");rejects(()->dst.addShow(dst.halls.getFirst().id,"gap","water://x","2030-03-10 02:30",1,now),"DST missing time rejected");rejects(()->dst.addShow(dst.halls.getFirst().id,"repeat","water://x","2030-11-03 01:30",1,now),"DST ambiguous time rejected");
        for(Scene scene:Scene.values()){Hall x=new Hall();ManagerState.apply(x,scene);for(boolean light:List.of(true,false)){int power=ManagerState.outputPower(light,x,true);check(power>=0&&power<=15,"redstone bound "+scene);check(ManagerState.outputPower(light,x,false)==(light?15:0),"offline fail-safe "+scene);}}
        Hall close=new Hall();ManagerState.apply(close,Scene.PLAY);ManagerState.apply(close,Scene.CLOSE);check(!close.open&&close.lights==15&&close.transport==Transport.STOPPED,"close safe state");
        check(ManagerState.outputPower(true,null,true)==15&&ManagerState.outputPower(false,null,true)==0,"missing hall safe output");
        String json=StateCodec.encode(m);ManagerState round=StateCodec.decode(json);check(round.shows.getFirst().url.equals("water://demo")&&round.halls.getFirst().terminalIdentity.equals(h.terminalIdentity),"source and target persist");check(StateCodec.decode(StateCodec.publicView(m)).shows.getFirst().url.isEmpty(),"public payload redacts URLs");
        rejects(()->StateCodec.decode("{"),"corrupt json rejected");rejects(()->StateCodec.decode("null"),"null state rejected");rejects(()->StateCodec.decode("x".repeat(512001)),"oversize state rejected");
        for(LightPreset preset:LightPreset.values()) {
            Hall lightHall=new Hall();ManagerState.applyPreset(lightHall,preset);
            for(LightGroup group:LightGroup.values()){int power=ManagerState.outputPower(group,lightHall,true);check(power>=0&&power<=15,"preset power bounded");if(group==LightGroup.AISLE||group==LightGroup.EXIT)check(power>=1,"preset protects egress");check(ManagerState.outputPower(group,lightHall,false)==15,"offline light fail-safe");}
            int[] custom={3,4,5,6};ManagerState.savePreset(lightHall,preset,custom);custom[1]=0;check(lightHall.presets.get(preset.name())[1]==4,"preset copies input");
            ManagerState.applyPreset(lightHall,preset);check(ManagerState.outputPower(LightGroup.MAIN,lightHall,true)==3,"per-hall main preset");check(ManagerState.outputPower(LightGroup.AISLE,lightHall,true)==4,"per-hall aisle preset");
        }
        Hall maintenance=new Hall();ManagerState.savePreset(maintenance,LightPreset.CLEANING,new int[]{0,0,0,0});ManagerState.applyPreset(maintenance,LightPreset.CLEANING);check(ManagerState.outputPower(LightGroup.AISLE,maintenance,true)==0,"owner can deliberately disable aisle for maintenance");check(ManagerState.outputPower(LightGroup.EXIT,maintenance,true)==0,"owner can deliberately disable exit for maintenance");rejects(()->ManagerState.savePreset(new Hall(),LightPreset.FEATURE,new int[]{16,5,15,0}),"over-range preset rejected");
        ManagerState retry=fixture();retry.enable(true);Hall rh=retry.halls.getFirst();Show rs=retry.addShow(rh.id,"retry stop","water://x","2030-01-01 09:00",1,now);Count rc=new Count();retry.tick(rs.start,rc);rc.fail="unloaded";retry.tick(rs.end,rc);check(rs.status==Status.STOP_PENDING&&rh.lights==15&&!rh.admissionHeld,"unloaded stop remains pending, safe lighting and admission");
        ManagerState retryReload=StateCodec.decode(StateCodec.encode(retry));Count rr=new Count();retryReload.tick(rs.end+1000,rr);check(rr.starts==0&&rr.stops==1&&retryReload.shows.getFirst().status==Status.COMPLETED,"pending stop survives reload and resolves without replay");
        var packet=new ManagerNetwork.Snapshot(new net.minecraft.core.BlockPos(-10,80,25),UUID.randomUUID().toString(),false,true,true,StateCodec.publicView(m));
        var buffer=io.netty.buffer.Unpooled.buffer();ManagerNetwork.Snapshot.CODEC.encode(buffer,packet);var decoded=ManagerNetwork.Snapshot.CODEC.decode(buffer);check(decoded.equals(packet),"remote snapshot roundtrip");buffer.release();
        var request=new ManagerNetwork.Request(new net.minecraft.core.BlockPos(-10,80,25),packet.identity(),"refresh",new CompoundTag());buffer=io.netty.buffer.Unpooled.buffer();ManagerNetwork.Request.CODEC.encode(buffer,request);check(ManagerNetwork.Request.CODEC.decode(buffer).equals(request),"request roundtrip");buffer.release();
        CompoundTag terminalState=new CompoundTag();terminalState.putString("url","https://example.invalid/movie.mp4");terminalState.putBoolean("linked",true);terminalState.putLong("time",1250);terminalState.putLong("duration",5000);
        var terminalSnapshot=new TerminalNetwork.Snapshot(new net.minecraft.core.BlockPos(8,70,-4),UUID.randomUUID().toString(),true,terminalState);
        buffer=io.netty.buffer.Unpooled.buffer();TerminalNetwork.Snapshot.CODEC.encode(buffer,terminalSnapshot);check(TerminalNetwork.Snapshot.CODEC.decode(buffer).equals(terminalSnapshot),"TMS screen snapshot roundtrip");buffer.release();
        CompoundTag terminalArgs=new CompoundTag();terminalArgs.putString("url","water://terminal-qa");
        var terminalRequest=new TerminalNetwork.Request(terminalSnapshot.pos(),terminalSnapshot.identity(),"media",terminalArgs);
        buffer=io.netty.buffer.Unpooled.buffer();TerminalNetwork.Request.CODEC.encode(buffer,terminalRequest);check(TerminalNetwork.Request.CODEC.decode(buffer).equals(terminalRequest),"TMS screen request roundtrip");buffer.release();
        for(String action:List.of("auth_local_programme","revoke_local_programme")){
            var consentRequest=new FilmNetwork.Request(packet.source(),packet.identity(),action,UUID.randomUUID().toString(),"{}");
            buffer=io.netty.buffer.Unpooled.buffer();
            try{FilmNetwork.Request.CODEC.encode(buffer,consentRequest);check(FilmNetwork.Request.CODEC.decode(buffer).equals(consentRequest),"full local-consent action roundtrip "+action);}finally{buffer.release();}
        }
        EnrollmentRegistry registry=new EnrollmentRegistry();UUID t=UUID.randomUUID(),manager=UUID.randomUUID(),another=UUID.randomUUID();check(registry.claim(t,manager),"initial exclusive enrollment");check(!registry.claim(t,another),"second manager refused");check(registry.claim(t,manager),"idempotent claim");EnrollmentRegistry loaded=EnrollmentRegistry.load(registry.save(new CompoundTag(),null),null);check(loaded.owns(t,manager),"enrollment persisted");loaded.release(t,another);check(loaded.owns(t,manager),"stranger cannot release");loaded.release(t,manager);check(loaded.claim(t,another),"authorized release allows re-enroll");
        var ui=WaterUiSchemaChecks.validateAll(Path.of("src/main/resources/assets/mycinema/ui"));
        assertions+=ui.assertions();
        System.out.println("PASS: "+assertions+" assertions; permission matrix, dated scheduler, DST, restart/duplicate prevention, manual override, output fail-safe, serialization, exact local-consent action codecs, exclusive enrollment, "+ui.documents()+" discovered WaterUI templates and actual property/event/live-binding schemas.");
    }

}
