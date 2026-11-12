package local.cinemamanager.qa;

import com.google.gson.GsonBuilder;
import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.projection.CinemaProjectorPartTile;
import local.cinemamanager.projection.ProjectorGeometry;
import local.cinemamanager.projection.ProjectorStyle;
import net.minecraft.commands.Commands;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in QA only: no commands registered without -Dmycinema.qa=true; rejects non-QA worlds. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class MyCinemaQa {
    private static BlockPos managerPos;
    private static UUID tester;
    private static int phase=-1, checks;
    private static long nextTick;
    private static String scheduledId;
    private static Object clockBefore;
    private static final List<String> passed=new ArrayList<>();

    /** Clean-profile client lane entry point. It still uses the real integrated-server player. */
    public static void startAutomatic(ServerPlayer player){
        if(!Boolean.getBoolean("mycinema.qa")||!Boolean.getBoolean("mycinema.clientQa"))
            throw new IllegalStateException("Automated client QA is disabled");
        if(phase>=0)throw new IllegalStateException("QA already running");
        setup(player);start(player);ManagerNetwork.open(player,manager(player),false);
    }

    /** Reopens the same saved world and starts the persisted source through the ordinary manager path. */
    public static void restartAfterReload(ServerPlayer player){
        if(!Boolean.getBoolean("mycinema.qa")||!Boolean.getBoolean("mycinema.clientQa"))
            throw new IllegalStateException("Automated client QA is disabled");
        ManagerTile m=manager(player);if(m.state.halls.size()!=2||m.state.schedulerEnabled)
            throw new IllegalStateException("Saved manager state did not survive reload");
        ManagerState.Hall hall=m.state.halls.getFirst();CinemaTerminalTile terminal=m.programmeTerminal(hall.id);
        if(terminal==null||terminal.projectors().size()!=2||!terminal.playbackAvailable())
            throw new IllegalStateException("Saved projector group did not survive reload");
        m.media(player,hall,media(),"MyCinema QA reconnect playback");m.scene(player,hall.id,ManagerState.Scene.PLAY);
        ManagerNetwork.open(player,m,false);
    }

    public static void stopAfterReload(ServerPlayer player){
        ManagerTile m=manager(player);ManagerState.Hall hall=m.state.halls.getFirst();
        m.scene(player,hall.id,ManagerState.Scene.STOP);m.dirty();
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){
        if(!Boolean.getBoolean("mycinema.qa"))return;
        e.getDispatcher().register(Commands.literal("mycinemaqa").requires(s->s.hasPermission(2))
            .then(Commands.literal("setup").executes(c->{setup(c.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("check").executes(c->{start(c.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("guestplans").executes(c->{guestPlans(c.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("open").executes(c->{var p=c.getSource().getPlayerOrException();ManagerNetwork.open(p,manager(p),false);return 1;}))
            .then(Commands.literal("tablet").executes(c->{var p=c.getSource().getPlayerOrException();ManagerTile m=manager(p);ItemStack stack=new ItemStack(CinemaManager.TABLET.get());CompoundTag d=new CompoundTag();d.putLong("manager",m.getBlockPos().asLong());d.putUUID("identity",m.identity);d.putString("dimension",p.level().dimension().location().toString());d.putString("name",m.state.name);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(d));p.setItemInHand(InteractionHand.MAIN_HAND,stack);p.sendSystemMessage(Component.literal("QA 平板已准备。正常版本须潜行右击终端绑定。"));return 1;})));
    }
    private static void world(ServerPlayer p){if(!p.getServer().getWorldData().getLevelName().toLowerCase(Locale.ROOT).contains("qa"))throw new IllegalStateException("QA commands are limited to a world whose name contains QA");}
    private static ManagerTile manager(ServerPlayer p){world(p);ServerLevel level=p.serverLevel();if(managerPos!=null&&level.hasChunkAt(managerPos)&&level.getBlockEntity(managerPos) instanceof ManagerTile m)return m;
        BlockPos base=p.blockPosition();for(int x=(base.getX()-80)>>4;x<=(base.getX()+80)>>4;x++)for(int z=(base.getZ()-80)>>4;z<=(base.getZ()+80)>>4;z++)if(level.getChunkSource().hasChunk(x,z))for(var be:level.getChunk(x,z).getBlockEntities().values())if(be instanceof ManagerTile m&&m.state.name.startsWith("MyCinema QA")){managerPos=m.getBlockPos();return m;}
        throw new IllegalStateException("Run /mycinemaqa setup first");
    }
    private static void block(ServerLevel l,BlockPos p,Block b){if(!l.hasChunkAt(p))throw new IllegalStateException("QA builder refuses unloaded chunks: "+p);l.setBlockAndUpdate(p,b.defaultBlockState());}
    private static void setup(ServerPlayer p){world(p);ServerLevel l=p.serverLevel();BlockPos origin=p.blockPosition().offset(0,0,5);managerPos=origin;
        for(int x=-17;x<=17;x++)for(int z=-3;z<=14;z++){block(l,origin.offset(x,-1,z),Blocks.SMOOTH_STONE);for(int y=0;y<8;y++)block(l,origin.offset(x,y,z),Blocks.AIR);}
        block(l,origin,CinemaManager.MANAGER.get());ManagerTile m=(ManagerTile)l.getBlockEntity(origin);m.owner=p.getUUID();m.state.name="MyCinema QA 双厅影院";m.dirty();
        for(int index=0;index<2;index++){
            int x=index==0?-10:10;BlockPos tpos=origin.offset(x,0,3);block(l,tpos,CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile t=(CinemaTerminalTile)l.getBlockEntity(tpos);t.owner(p.getUUID());
            for(int i=0;i<2;i++){
                BlockPos pp=origin.offset(x+(i==0?-2:2),0,9);
                ProjectorStyle style=index==0&&i==1?ProjectorStyle.IMAX_GT:ProjectorStyle.CINEMA;
                Block projectorBlock=style==ProjectorStyle.IMAX_GT?CinemaDisplays.IMAX_GT_PROJECTOR.get():CinemaDisplays.CINEMA_PROJECTOR.get();
                l.setBlockAndUpdate(pp,projectorBlock.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH));
                CinemaProjectorTile projector=(CinemaProjectorTile)l.getBlockEntity(pp);
                projector.initializeStyle(style,Direction.SOUTH);
                projector.owner(p.getUUID());projector.data.setWidth(10);projector.data.setHeight(5.625f);
                projector.data.projectionDistance=6;
                projector.projectionOffsetX=i==0?2:-2;projector.projectionOffsetY=2;projector.setDirty();
                for(var offset:ProjectorGeometry.cells(style,Direction.SOUTH).keySet())if(!offset.equals(BlockPos.ZERO)){
                    BlockPos partPos=pp.offset(offset);l.setBlockAndUpdate(partPos,CinemaManager.PROJECTOR_PART.get().defaultBlockState());
                    ((CinemaProjectorPartTile)l.getBlockEntity(partPos)).bind(pp,projector.identity());
                }
            }
            p.teleportTo(tpos.getX()+.5,tpos.getY()+1,tpos.getZ()-1.5);t.bindNearby(p);p.teleportTo(origin.getX()+.5,origin.getY(),origin.getZ()-2.5);
            ManagerState.Hall h=m.enroll(p,t,false);h.name=index==0?"一号厅 · IMAX测试":"二号厅 · 同步测试";
            Block[] outputs={CinemaManager.LIGHT.get(),CinemaManager.AISLE.get(),CinemaManager.EXIT.get(),CinemaManager.ACCENT.get(),CinemaManager.ENTRY.get()};
            for(int i=0;i<outputs.length;i++){BlockPos op=origin.offset(x-4+i*2,0,-1);block(l,op,outputs[i]);EndpointTile out=(EndpointTile)l.getBlockEntity(op);out.owner=p.getUUID();p.teleportTo(op.getX()+.5,op.getY()+1,op.getZ()-1.5);m.bindOutput(p,out,h.id);block(l,op.above(),Blocks.REDSTONE_LAMP);}
            BlockPos gate=BlockPos.of(h.outputs.get(4)).south();
            var door=CinemaManager.DOOR_METAL.get();
            for(int leaf=0;leaf<2;leaf++){
                BlockPos dp=gate.east(leaf);var state=door.defaultBlockState().setValue(local.cinemamanager.doors.CinemaDoorBlock.FACING,Direction.NORTH).setValue(local.cinemamanager.doors.CinemaDoorBlock.HINGE,leaf==0?DoorHingeSide.LEFT:DoorHingeSide.RIGHT);
                l.setBlock(dp,state,2|16);door.setPlacedBy(l,dp,state,p,new ItemStack(CinemaManager.DOOR_METAL_ITEM.get()));
            }
            BlockPos ip=origin.offset(x,1,1);block(l,ip,CinemaManager.INFO.get());InfoTile info=(InfoTile)l.getBlockEntity(ip);info.owner=p.getUUID();info.manager=origin;info.managerIdentity=m.identity;info.hallId=h.id;info.dirty();
        }
        p.teleportTo(origin.getX()+.5,origin.getY(),origin.getZ()-2.5);p.setYRot(0);p.setXRot(10);
        p.getInventory().add(new ItemStack(CinemaManager.LINKER.get()));p.getInventory().add(new ItemStack(CinemaManager.TABLET.get()));
        m.notice("QA 双厅已建立；自动排片保持关闭。控制中心两侧灯组顺序：主灯/通道/出口/氛围/入场。");p.sendSystemMessage(Component.literal("MyCinema QA ready at "+origin.toShortString()));
    }
    /** Adds only synthetic programme rows; never rebuilds blocks or opens/prepares media. */
    private static void guestPlans(ServerPlayer p){
        ManagerTile m=manager(p);ServerLevel level=p.serverLevel();
        if(phase>=0||m.state.schedulerEnabled)throw new IllegalStateException("Finish QA and disable scheduling before creating the guest programme fixture");
        if(!m.permits(p,Double.POSITIVE_INFINITY)||m.state.halls.size()<2)throw new IllegalStateException("The two-hall QA manager must be owned and loaded");
        if(!m.state.shows.isEmpty())throw new IllegalStateException("Guest fixture requires an empty schedule; review and prune old completed rows in the owner screen first. No existing rows were removed");
        m.state.setTimezone("Asia/Tokyo");
        long first=(System.currentTimeMillis()/60000+5)*60000;
        for(int i=0;i<ManagerState.MAX_SHOWS;i++){
            var hall=m.state.halls.get(i%2);var show=new ManagerState.Show();show.id="guest-qa-"+String.format(Locale.ROOT,"%03d",i+1);show.hallId=hall.id;
            show.title=i==0?"QA FIRST 001 — Prepared source":i==127?"QA LAST 128 — All rows reached":i==1?"QA 002 — A deliberately long film title for complete wrapping across wall pages":"QA programme "+String.format(Locale.ROOT,"%03d",i+1);
            show.start=first+(i/2)*120000L;show.end=show.start+60000;
            show.url="water://guest-qa-unmatched-"+(i+1);
            BlockPos terminal=BlockPos.of(hall.terminalPos);
            if(i%4==0&&level.hasChunkAt(terminal)&&level.getBlockEntity(terminal) instanceof CinemaTerminalTile t&&t.identity().toString().equals(hall.terminalIdentity)&&t.data.getSource()==0){
                String source=local.cinemamanager.media.MediaSource.normalize(t.data.getUrl());if(source!=null)show.url=source;
            }
            m.state.shows.add(show);
        }
        m.state.validate();m.state.revision++;m.dirty();
        List<String> panels=new ArrayList<>();BlockPos base=m.getBlockPos();
        for(int x=(base.getX()-80)>>4;x<=(base.getX()+80)>>4;x++)for(int z=(base.getZ()-80)>>4;z<=(base.getZ()+80)>>4;z++)if(level.getChunkSource().hasChunk(x,z))
            for(var tile:level.getChunk(x,z).getBlockEntities().values())if(tile instanceof InfoTile info&&m.identity.equals(info.managerIdentity)&&base.equals(info.manager)){
                info.refreshNow();panels.add(info.getBlockPos().toShortString()+" ("+info.scope+")");
            }
        p.sendSystemMessage(Component.literal("Guest QA: 128 dated shows; Asia/Tokyo; scheduler OFF. Manager: "+base.toShortString()+". Bound panels: "+String.join("; ",panels)+". Set an existing panel to Lobby programme in its owner editor to view all rows. Existing blocks/media were unchanged."));
    }
    private static void start(ServerPlayer p){ManagerTile m=manager(p);if(phase>=0)throw new IllegalStateException("QA already running");tester=p.getUUID();checks=0;passed.clear();phase=0;nextTick=p.serverLevel().getGameTime();p.teleportTo(managerPos.getX()+.5,managerPos.getY(),managerPos.getZ()-2.5);p.sendSystemMessage(Component.literal("MyCinema 真实服务器状态验证开始，约 20 秒。"));}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;passed.add(name);}
    private static String media(){return System.getProperty("mycinema.qaMedia","");}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){if(phase<0||tester==null)return;ServerPlayer p=event.getServer().getPlayerList().getPlayer(tester);if(p==null){phase=-1;return;}ServerLevel l=p.serverLevel();if(l.getGameTime()<nextTick)return;
        try {ManagerTile m=manager(p);ManagerState.Hall h=m.state.halls.getFirst();CinemaTerminalTile t=(CinemaTerminalTile)l.getBlockEntity(BlockPos.of(h.terminalPos));
            switch(phase){
                case 0->{check(m.permits(p,8),"live player owns local terminal");check(t.projectors().size()==2&&t.playbackAvailable(),"two reciprocal projectors loaded");check(!m.state.schedulerEnabled,"default scheduler disabled");check(!media().isBlank(),"QA media explicitly configured");m.media(p,h,media(),"MyCinema QA test pattern");m.scene(p,h.id,ManagerState.Scene.OPEN);}
                case 1->{
                    check(h.open&&h.preset==ManagerState.LightPreset.ENTRY,"admission opening preset");check(t.data.paused,"loading never autoplays");check(l.getBlockState(BlockPos.of(h.outputs.get(4))).getValue(EndpointBlock.POWER)==15,"admission output high");check(l.getBlockState(BlockPos.of(h.outputs.get(4)).south()).getValue(local.cinemamanager.doors.CinemaDoorBlock.OPEN),"entry scene physically opens three-metre pair");m.scene(p,h.id,ManagerState.Scene.PLAY);
                    // Keep the real movie running long enough for the client lane to
                    // finish cycling every manager page and capture an unobscured frame.
                    if(Boolean.getBoolean("mycinema.clientQa")){phase++;nextTick=l.getGameTime()+120;return;}
                }
                case 2->{check(!t.data.paused&&t.clock()!=null,"single TMS server clock playing");if(t.clock().duration()<=0){nextTick=l.getGameTime()+10;return;}check(h.open&&!h.admissionHeld,"movie keeps venue open with entry hold closed");check(!l.getBlockState(BlockPos.of(h.outputs.get(4)).south().above(2)).getValue(local.cinemamanager.doors.CinemaDoorBlock.OPEN),"feature scene closes actual upper door segment");clockBefore=t.clock();check(l.getBlockState(BlockPos.of(h.outputs.get(0))).getValue(EndpointBlock.POWER)==0,"feature main lamps off");check(l.getBlockState(BlockPos.of(h.outputs.get(1))).getValue(EndpointBlock.POWER)>0,"aisle lamps preserved");check(l.getBlockState(BlockPos.of(h.outputs.get(2))).getValue(EndpointBlock.POWER)>0,"exit lamps preserved");check(t.clock().seek(1200),"shared server clock accepts seek");m.scene(p,h.id,ManagerState.Scene.PAUSE);if(Boolean.getBoolean("mycinema.clientQa")){phase++;nextTick=l.getGameTime()+100;return;}}
                case 3->{check(t.data.paused&&t.clock().paused(),"pause reaches server clock");check(t.clock()==clockBefore,"pause preserves single clock identity");check(Math.abs(t.clock().time()-1200)<=500,"seek reaches the shared server clock");check(local.cinemamanager.library.ProjectionPower.check(m,t,p,null)==null,"projector group remains controllable before stop");m.scene(p,h.id,ManagerState.Scene.STOP);check(h.issue.isBlank(),"stop command accepted by the projector group: "+h.issue);}
                case 4->{check(t.clock().stopped(),"stop reaches server clock");check(h.preset==ManagerState.LightPreset.EXIT,"stop applies exit preset");m.scene(p,h.id,ManagerState.Scene.CLOSE);}
                case 5->{check(!h.open,"venue close persisted");check(l.getBlockState(BlockPos.of(h.outputs.get(4))).getValue(EndpointBlock.POWER)==0,"admission output low");m.state.setTimezone("UTC");m.scene(p,h.id,ManagerState.Scene.AUTO);m.enableScheduler(p,true);check(t.automationGrant()!=null,"real owner grants bounded server automation");ManagerState.Show s=new ManagerState.Show();s.hallId=h.id;s.title="Scheduled QA";s.url="water://mycinema-qa-schedule";s.start=System.currentTimeMillis()+1500;s.end=s.start+5000;scheduledId=s.id;m.state.shows.add(s);m.dirty();nextTick=l.getGameTime()+60;phase++;return;}
                case 6->{var show=m.state.shows.stream().filter(s->s.id.equals(scheduledId)).findFirst().orElseThrow();check(show.status==ManagerState.Status.RUNNING,"dated schedule triggered");check(!t.data.paused,"scheduled WM playback started");var saved=m.saveWithoutMetadata(l.registryAccess());m.loadWithComponents(saved,l.registryAccess());check(m.state.shows.stream().anyMatch(s->s.id.equals(scheduledId)&&s.status==ManagerState.Status.RUNNING),"real manager NBT roundtrip preserves running claim");check(m.identity.toString().equals(saved.getUUID("identity").toString()),"manager identity persisted");nextTick=l.getGameTime()+120;phase++;return;}
                case 7->{var show=m.state.shows.stream().filter(s->s.id.equals(scheduledId)).findFirst().orElseThrow();check(show.status==ManagerState.Status.COMPLETED,"scheduled ending completed");check(t.data.paused&&t.clock().stopped(),"scheduled ending stopped WM clock");check(!EnrollmentRegistry.get(l).claim(t.identity(),UUID.randomUUID()),"second manager cannot claim existing TMS");m.enableScheduler(p,false);finish(p,null);return;}
            }
            phase++;nextTick=l.getGameTime()+30;
        }catch(Throwable failure){finish(p,failure);}
    }
    private static void finish(ServerPlayer p,Throwable error){phase=-1;Map<String,Object> result=new LinkedHashMap<>();result.put("success",error==null);result.put("checks",checks);result.put("passed",passed);if(error!=null)result.put("error",error.toString());try{Files.writeString(Path.of("mycinema-qa-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}catch(Exception ignored){}p.sendSystemMessage(Component.literal(error==null?"MyCinema QA PASS "+checks+" checks":"MyCinema QA FAIL: "+error));System.out.println("MYCINEMA_QA "+result);}
}
