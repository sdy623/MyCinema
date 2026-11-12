package local.cinemamanager.switches;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import static local.cinemamanager.switches.RegisteredSwitchEngine.*;

/** All coordinates come from the frozen registry. Never accepts user positions, block IDs, NBT or commands. */
final class MinecraftSwitchGateway implements Gateway {
    private final MinecraftServer server;
    MinecraftSwitchGateway(MinecraftServer server){this.server=server;}
    private CompoundTag storage(String key){return server.getCommandStorage().get(ResourceLocation.fromNamespaceAndPath("mycinema",key));}
    String profileFault(){
        CompoundTag profile=storage("floor_palette");
        if(!profile.getAllKeys().equals(Set.of("schema_version","profile","layout"))||!profile.contains("schema_version",3)
                ||profile.getInt("schema_version")!=1||!profile.contains("profile",8)||!profile.contains("layout",8)
                ||!profile.getString("profile").equals("wool_original368_carpet_v2")
                ||!profile.getString("layout").equals("original368_highback150_carpet_v2"))return "WRONG_OR_MALFORMED_WORLD_PROFILE";
        return null;
    }
    @Override public String worldFault(){return profileFault();}
    String positionFault(){
        String profile=profileFault();if(profile!=null)return profile;
        PositionEnrollment r=PositionEnrollment.read(server.overworld());
        String disk=PositionEnrollment.diskFault(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),r!=null);
        if(disk!=null)return disk;
        return r==null?"MISSING_POSITION_REGISTRY: explicit enroll_positions required":r.fault();
    }
    private boolean exactLever(Device d){
        ServerLevel l=level(d);if(l==null||d.controller()==null||!l.hasChunkAt(pos(d.controller())))return false;
        var state=l.getBlockState(pos(d.controller()));
        return state.is(Blocks.LEVER)&&state.getValue(LeverBlock.FACE)==net.minecraft.world.level.block.state.properties.AttachFace.FLOOR
                &&state.getValue(LeverBlock.FACING).getName().equals(d.facing())&&state.canSurvive(l,pos(d.controller()));
    }
    String enrollAll(){
        String fault=profileFault();if(fault!=null)return fault;
        PositionEnrollment existing=PositionEnrollment.read(server.overworld());
        String disk=PositionEnrollment.diskFault(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),existing!=null);
        if(disk!=null)return disk;
        if(existing!=null&&existing.fault()!=null)return existing.fault()+"; preserve and review existing registry";
        for(Device d:RegisteredSwitchRegistry.DEVICES)if(d.kind()==Kind.LEVER&&!exactLever(d))return "Enrollment refused: exact loaded/supported lever required at "+d.id();
        return PositionEnrollment.create(server.overworld()).enrollAll()?null:"Enrollment refused";
    }
    String reenroll(String id){
        String fault=positionFault();if(fault!=null)return fault;
        Device d=RegisteredSwitchRegistry.DEVICES.stream().filter(v->v.id().equals(id)&&v.kind()==Kind.LEVER).findFirst().orElse(null);
        if(d==null||!exactLever(d))return "Exact loaded/supported lever required for fixed ID";
        return PositionEnrollment.read(server.overworld()).reenroll(id)?null:"Re-enrollment refused";
    }
    @Override public long tick(){return server.overworld().getGameTime();}
    private ServerLevel level(Device d){return "minecraft:overworld".equals(d.dimension())?server.overworld():null;}
    private static BlockPos pos(Pos p){return new BlockPos(p.x(),p.y(),p.z());}
    private static Observation bad(String reason){return new Observation(false,false,false,false,-1,null,null,false,reason);}
    private static boolean identity(BlockEntity be,Device d,String role){
        if(be==null||d.installation()==null)return false;
        CompoundTag tag=be.getPersistentData();
        return tag.contains("cinema_ie_owner",8)&&tag.contains("cinema_ie_endpoint",8)
                &&d.installation().equals(tag.getString("cinema_ie_owner"))
                &&(d.id()+"_"+role).equals(tag.getString("cinema_ie_endpoint"));
    }
    @Override public Observation observe(Device d){return observe(d,true);}
    @Override public Observation observe(Device d,boolean on){
        ServerLevel level=level(d);
        if(level==null||d.controller()==null||!level.hasChunkAt(pos(d.controller())))return bad("Missing/unloaded registered controller");
        BlockPos cp=pos(d.controller());
        if(d.kind()==Kind.FLOOR_LEVER){
            var r=FloorPositionEnrollment.read(level);String fault=FloorPositionEnrollment.receiptFault(level,r);if(fault!=null)return bad(fault);
            var e=r.entry(d.id());var p=AuxiliaryPlan.get(d.id());if(e==null||!e.valid()||p==null)return bad("Floor position unbound/invalidated");
            fault=FloorCircuitValidation.core(level,p);if(fault!=null)return bad(fault);
            boolean ready=on&&e.routeValid()&&FloorCircuitValidation.route(level,p)==null;
            return new Observation(true,FloorCircuitValidation.powered(level,p),false,false,0,null,null,ready,null);
        }
        if(d.kind()==Kind.LEVER){
            String receiptFault=positionFault();if(receiptFault!=null)return bad(receiptFault);
            PositionEnrollment r=PositionEnrollment.read(level);
            if(r==null||!r.valid(d.id()))return bad("Position registration missing/invalidated; manual re-enrollment required");
            if(!exactLever(d))return bad("Exact lever type/face/facing/support mismatch");
            return new Observation(true,level.getBlockState(cp).getValue(LeverBlock.POWERED),false,false,0,null,null,true,null);
        }
        if(d.kind()!=Kind.IE_SIGNAL||d.breaker()==null)return bad("IE not bound");
        var plan=IeCircuitPlan.get(d.id());IeInstallationBinding receipt=IeInstallationBinding.read(level);
        String fault=IeInstallationBinding.receiptFault(level,receipt);if(fault!=null)return bad(fault);
        var entry=receipt.entry(d.id());
        if(plan==null||entry==null||!entry.valid()||!entry.owner().equals(d.installation()))return bad("Installation receipt missing/invalidated");
        fault=IeCircuitValidation.core(level,plan,entry.owner());if(fault!=null)return bad(fault);
        BlockEntity controller=level.getBlockEntity(pos(d.controller())),breaker=level.getBlockEntity(pos(d.breaker()));
        try{
            // Route faults never contaminate core authentication, so emergency OFF remains available.
            boolean ready=on&&entry.routeValid()&&IeCircuitValidation.route(level,plan,entry.owner())==null;
            return new Observation(true,IeSwitchBridge.active(controller),IeSwitchBridge.inverted(controller),
                    IeSwitchBridge.inverted(breaker),IeSwitchBridge.externalWires(level,controller),
                    IeSwitchBridge.internal(level,pos(d.breaker())),IeSwitchBridge.active(breaker),ready,null);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError bad){return bad("IE adapter unavailable or network unknown: "+bad.getClass().getSimpleName());}
    }
    @Override public boolean request(Device d,boolean on){
        if(worldFault()!=null||!d.eligible())return false;
        Observation check=observe(d,on);
        if(!check.authenticated()||check.fault()!=null)return false;
        if(d.kind()==Kind.FLOOR_LEVER){
            ServerLevel l=level(d);var r=FloorPositionEnrollment.read(l);var e=r==null?null:r.entry(d.id());
            if(e==null||!e.valid()||(on&&(!d.routeCertified()||!check.downstreamReady())))return false;
            BlockPos p=pos(d.controller());var state=l.getBlockState(p);if(state.getValue(LeverBlock.POWERED)==on)return true;
            ((LeverBlock)state.getBlock()).pull(state,l,p,null);
            var after=r.entry(d.id());Observation result=observe(d,on);
            return after!=null&&after.valid()&&after.generation()==e.generation()&&result.authenticated()&&result.fault()==null&&result.powered()==on;
        }
        if(d.kind()==Kind.LEVER){
            ServerLevel l=level(d);BlockPos p=pos(d.controller());
            PositionEnrollment r=PositionEnrollment.read(l);if(r==null||!r.valid(d.id())||!exactLever(d))return false;
            long generation=r.generation(d.id());var state=l.getBlockState(p);
            if(state.getValue(LeverBlock.POWERED)==on)return true;
            ((LeverBlock)state.getBlock()).pull(state,l,p,null); // Official same-device POWERED transition and normal neighbor updates.
            return r.valid(d.id())&&r.generation(d.id())==generation&&exactLever(d)&&l.getBlockState(p).getValue(LeverBlock.POWERED)==on;
        }
        if(d.kind()!=Kind.IE_SIGNAL||check.controllerMode()||!check.breakerMode()||check.externalControllerWires()!=0
                ||(on&&(!d.routeCertified()||!check.downstreamReady())))return false;
        try{
            IeInstallationBinding receipt=IeInstallationBinding.read(level(d));var before=receipt==null?null:receipt.entry(d.id());
            if(before==null||!before.valid())return false;
            boolean requested=IeSwitchBridge.request(level(d).getBlockEntity(pos(d.controller())),on);
            var after=receipt.entry(d.id());return requested&&after!=null&&after.valid()&&after.generation()==before.generation();
        }
        catch(ReflectiveOperationException|RuntimeException|LinkageError bad){return false;}
    }
}
