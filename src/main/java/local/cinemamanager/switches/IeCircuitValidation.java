package local.cinemamanager.switches;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import static local.cinemamanager.switches.IeCircuitPlan.*;
/** Read-only static hardware + exact wire-graph validator. OFF never invokes route(). */
final class IeCircuitValidation {
 static BlockPos pos(RegisteredSwitchEngine.Pos p){return new BlockPos(p.x(),p.y(),p.z());}
 private static String property(BlockState state,String name){
  for(Property<?> p:state.getProperties())if(p.getName().equals(name))return value(state,p);return null;
 }
 private static <T extends Comparable<T>> String value(BlockState s,Property<T> p){return p.getName(s.getValue(p));}
 static String identity(ServerLevel l,Endpoint e,String installation){
  if(!l.hasChunkAt(pos(e.pos())))return "UNLOADED: "+e.role();
  BlockState s=l.getBlockState(pos(e.pos()));BlockEntity be=l.getBlockEntity(pos(e.pos()));
  if(!e.block().equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString())||be==null
   ||!e.beType().equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString())||!e.beClass().equals(be.getClass().getName()))return "TYPE: "+e.role();
  if(e.facing()!=null&&!e.facing().equals(property(s,"facing")))return "FACING: "+e.role();
  if("true".equals(property(s,"waterlogged")))return "WATERLOGGED: "+e.role();
  if(e.support()!=null&&(!l.hasChunkAt(pos(e.support()))||!e.supportBlock().equals(BuiltInRegistries.BLOCK.getKey(l.getBlockState(pos(e.support())).getBlock()).toString())))return "SUPPORT: "+e.role();
  String owner=e.owner()==null?installation:e.owner();var tags=be.getPersistentData();
  if(owner==null||!tags.contains("cinema_ie_owner",8)||!owner.equals(tags.getString("cinema_ie_owner"))
    ||!tags.contains("cinema_ie_endpoint",8)||!e.role().equals(tags.getString("cinema_ie_endpoint")))return "IDENTITY: "+e.role();
  try{
   if(e.rotation()>=0&&be.getClass().getField("rotation").getInt(be)!=e.rotation())return "ROTATION: "+e.role();
  }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){return "IE_STATIC_ADAPTER: "+e.role();}
  return null;
 }
 static String core(ServerLevel l,Circuit c,String owner){
  for(Endpoint e:List.of(c.controller(),c.breaker())){String f=identity(l,e,owner);if(f!=null)return f;}
  try{
   BlockEntity a=l.getBlockEntity(pos(c.controller().pos())),b=l.getBlockEntity(pos(c.breaker().pos()));
   if(IeSwitchBridge.inverted(a)||!IeSwitchBridge.inverted(b))return "MODE_MISMATCH: configuration unchanged";
   if(IeSwitchBridge.externalWires(l,a)!=0)return "SIGNAL_CONTROLLER_HAS_WIRES";
   return null;
  }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){return "IE_CORE_ADAPTER_UNKNOWN: "+ex.getClass().getSimpleName();}
 }
 static String route(ServerLevel l,Circuit c,String owner){
  int lamps=0;
  try{
   for(Endpoint e:c.endpoints()){
    String f=identity(l,e,owner);if(f!=null)return f;
    BlockEntity be=l.getBlockEntity(pos(e.pos()));
    if(e.lamp()){
     lamps++;
     if(!"north".equals(((net.minecraft.core.Direction)be.getClass().getField("facing").get(be)).getName())
      ||be.getClass().getField("rotX").getFloat(be)!=90F||be.getClass().getField("rotY").getFloat(be)!=0F
      ||!be.getClass().getField("redstoneControlInverted").getBoolean(be))return "LAMP_AIM_MODE: "+e.role();
     Object computer=be.getClass().getField("computerControl").get(be);
     if((Boolean)computer.getClass().getMethod("isAttached").invoke(computer))return "LAMP_EXTERNAL_COMPUTER_CONTROL: "+e.role();
    }
    if(e.source()){
     var nbt=be.saveWithFullMetadata(l.registryAccess());
     for(int i=0;i<6;i++)if(!nbt.contains("sideConfig_"+i,3)||nbt.getInt("sideConfig_"+i)!=2)return "SOURCE_FIXED_OUTPUT: "+e.role();
    }
   }
   if(lamps!=c.lampCount())return "LAMP_COUNT";
   return IeSwitchBridge.exactGraph(l,c);
  }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){return "IE_ROUTE_ADAPTER_UNKNOWN: "+ex.getClass().getSimpleName();}
 }
 private IeCircuitValidation(){}
}
