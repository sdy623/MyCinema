package local.cinemamanager.switches;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
/** Read-only revision2 exact route. Dynamic dust power/shape and lamp LIT are not rewritten. */
final class FloorCircuitValidation {
 static String value(BlockState state,String name){
  for(Property<?> p:state.getProperties())if(p.getName().equals(name))return property(state,p);return null;
 }
 private static <T extends Comparable<T>>String property(BlockState s,Property<T> p){return p.getName(s.getValue(p));}
 static String cell(ServerLevel l,AuxiliaryPlan.Cell c){
  BlockPos p=IeCircuitValidation.pos(c.pos());if(!l.hasChunkAt(p))return "UNLOADED_FLOOR: "+c.pos();var s=l.getBlockState(p);
  if(!BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString().equals(c.block()))return "FLOOR_BLOCK: "+c.pos();
  for(var e:c.properties().entrySet())if(!e.getValue().equals(value(s,e.getKey())))return "FLOOR_PROPERTY: "+c.pos()+" "+e.getKey();
  if(l.getBlockEntity(p)!=null)return "FOREIGN_FLOOR_BLOCK_ENTITY";
  return null;
 }
 static String core(ServerLevel l,AuxiliaryPlan.Plan p){
  if(p==null||!p.floor())return "UNKNOWN_FLOOR_CIRCUIT";
  for(var c:p.cells())if(c.pos().equals(p.controller())||c.pos().equals(p.support())){String f=cell(l,c);if(f!=null)return f;}
  BlockPos at=IeCircuitValidation.pos(p.controller());
  if(!l.hasChunksAt(at.offset(-1,-1,-1),at.offset(1,1,1))||!l.getBlockState(at).canSurvive(l,at))return "UNSUPPORTED_FLOOR_CONTROLLER";
  return null;
 }
 static String route(ServerLevel l,AuxiliaryPlan.Plan p){
  if(p==null||!p.floor())return "UNKNOWN_FLOOR_CIRCUIT";
  for(var c:p.cells()){
   String f=cell(l,c);if(f!=null)return f;
   if(c.block().equals("minecraft:redstone_wire")||c.block().equals("minecraft:repeater")){
    BlockPos at=IeCircuitValidation.pos(c.pos());if(!l.hasChunksAt(at.offset(-1,-1,-1),at.offset(1,1,1))||!l.getBlockState(at).canSurvive(l,at))return "UNSUPPORTED_REDSTONE_COMPONENT: "+c.pos();
   }
  }
  return null;
 }
 static boolean powered(ServerLevel l,AuxiliaryPlan.Plan p){return l.getBlockState(IeCircuitValidation.pos(p.controller())).getValue(LeverBlock.POWERED);}
 static Boolean lamps(ServerLevel l,AuxiliaryPlan.Plan p){
  Boolean value=null;
  for(var at:p.lamps()){
   BlockPos pos=IeCircuitValidation.pos(at);if(!l.hasChunkAt(pos))return null;var s=l.getBlockState(pos);
   if(!s.is(net.minecraft.world.level.block.Blocks.REDSTONE_LAMP))return null;
   boolean lit=s.getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT);if(value!=null&&value!=lit)return null;value=lit;
  }
  return p.lamps().size()==12?value:null;
 }
 private FloorCircuitValidation(){}
}
