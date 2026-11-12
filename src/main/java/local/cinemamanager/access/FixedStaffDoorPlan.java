package local.cinemamanager.access;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
/** The three R7 low-door assemblies only; no arbitrary door/reader activation. */
final class FixedStaffDoorPlan {
 static final BlockPos MANAGER=new BlockPos(42,96,66);
 static final UUID OWNER=UUID.fromString("403c4c68-ec3a-43e6-b7d4-92ee18d2ccc8"), CONTROLLER=UUID.fromString("5d335a03-99b4-4118-acb5-e966e76614f4");
 record Cell(BlockPos pos,String block,Map<String,String> properties){}
 record Plan(String id,boolean iron,List<Cell> cells,List<BlockPos> leaves,BlockPos reader,String backing,String below,AABB occupied,AABB egress){}
 static final List<Cell> BACKSTAGE=List.of(new Cell(new BlockPos(12,64,4),"minecraft:iron_door",Map.of("hinge","left","half","lower","facing","east")),
 new Cell(new BlockPos(12,65,4),"minecraft:iron_door",Map.of("hinge","left","half","upper","facing","east")),
 new Cell(new BlockPos(12,64,5),"minecraft:iron_door",Map.of("hinge","right","half","lower","facing","east")),
 new Cell(new BlockPos(12,65,5),"minecraft:iron_door",Map.of("hinge","right","half","upper","facing","east")),
 new Cell(new BlockPos(12,63,4),"minecraft:polished_deepslate",Map.of()),
 new Cell(new BlockPos(12,63,5),"minecraft:polished_deepslate",Map.of()));
 static final List<Cell> SERVICE_STRIP=List.of(new Cell(new BlockPos(12,65,9),"mycinema:cinema_door_metal",Map.of("hinge","left","half_drop","false","access","staff_back","part","lower","facing","east")),
 new Cell(new BlockPos(12,66,9),"mycinema:cinema_door_metal",Map.of("hinge","left","half_drop","false","access","staff_back","part","middle","facing","east")),
 new Cell(new BlockPos(12,67,9),"mycinema:cinema_door_metal",Map.of("hinge","left","half_drop","false","access","staff_back","part","upper","facing","east")),
 new Cell(new BlockPos(12,65,10),"mycinema:cinema_door_metal",Map.of("hinge","right","half_drop","false","access","staff_back","part","lower","facing","east")),
 new Cell(new BlockPos(12,66,10),"mycinema:cinema_door_metal",Map.of("hinge","right","half_drop","false","access","staff_back","part","middle","facing","east")),
 new Cell(new BlockPos(12,67,10),"mycinema:cinema_door_metal",Map.of("hinge","right","half_drop","false","access","staff_back","part","upper","facing","east")),
 new Cell(new BlockPos(12,64,9),"minecraft:polished_deepslate",Map.of()),
 new Cell(new BlockPos(12,64,10),"minecraft:polished_deepslate",Map.of()));
 static final List<Cell> UPPER_SERVICE=List.of(new Cell(new BlockPos(12,69,19),"mycinema:cinema_door_metal",Map.of("hinge","right","half_drop","false","access","staff_front","part","lower","facing","west")),
 new Cell(new BlockPos(12,70,19),"mycinema:cinema_door_metal",Map.of("hinge","right","half_drop","false","access","staff_front","part","middle","facing","west")),
 new Cell(new BlockPos(12,71,19),"mycinema:cinema_door_metal",Map.of("hinge","right","half_drop","false","access","staff_front","part","upper","facing","west")),
 new Cell(new BlockPos(12,69,20),"mycinema:cinema_door_metal",Map.of("hinge","left","half_drop","false","access","staff_front","part","lower","facing","west")),
 new Cell(new BlockPos(12,70,20),"mycinema:cinema_door_metal",Map.of("hinge","left","half_drop","false","access","staff_front","part","middle","facing","west")),
 new Cell(new BlockPos(12,71,20),"mycinema:cinema_door_metal",Map.of("hinge","left","half_drop","false","access","staff_front","part","upper","facing","west")),
 new Cell(new BlockPos(12,68,19),"minecraft:polished_deepslate",Map.of()),
 new Cell(new BlockPos(12,68,20),"minecraft:polished_deepslate",Map.of()));
 static final List<Plan> PLANS=List.of(new Plan("backstage",true,BACKSTAGE,List.of(new BlockPos(12,64,4),new BlockPos(12,64,5)),new BlockPos(11,65,2),"minecraft:black_concrete","minecraft:air",new AABB(12,64,4,13,66,6),new AABB(13,64,3,15,66,7)),
 new Plan("service_strip",false,SERVICE_STRIP,List.of(new BlockPos(12,65,9),new BlockPos(12,65,10)),new BlockPos(11,66,8),"minecraft:black_concrete","minecraft:air",new AABB(12,65,9,13,68,11),new AABB(13,65,8,15,67,12)),
 new Plan("upper_service",false,UPPER_SERVICE,List.of(new BlockPos(12,69,19),new BlockPos(12,69,20)),new BlockPos(11,70,21),"minecraft:gray_concrete","minecraft:polished_deepslate",new AABB(12,69,19,13,72,21),new AABB(13,69,18,15,71,22)));
 static Plan at(BlockPos pos){return PLANS.stream().filter(p->p.cells().stream().anyMatch(c->c.pos().equals(pos)&&c.block().endsWith("door"))||p.cells().stream().anyMatch(c->c.pos().equals(pos)&&c.block().equals("mycinema:cinema_door_metal"))).findFirst().orElse(null);}
 static Plan base(BlockPos pos){return PLANS.stream().filter(p->p.leaves().contains(pos)).findFirst().orElse(null);}
 static boolean profile(ServerLevel l){
  if(l!=l.getServer().overworld())return false;
  var t=l.getServer().getCommandStorage().get(ResourceLocation.fromNamespaceAndPath("mycinema","floor_palette"));
  return t.getAllKeys().equals(Set.of("schema_version","profile","layout"))&&t.contains("schema_version",3)&&t.getInt("schema_version")==1
    &&t.getString("profile").equals("wool_original368_carpet_v2")&&t.getString("layout").equals("original368_highback150_carpet_v2");
 }
 static boolean loaded(ServerLevel l,BlockPos p){return l.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null;}
 static boolean exact(ServerLevel l,Plan p){
  if(p==null||!profile(l))return false;
  for(var c:p.cells()){
   if(!loaded(l,c.pos()))return false;var s=l.getBlockState(c.pos());
   if(!c.block().equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString())||l.getBlockEntity(c.pos())!=null)return false;
   for(var e:c.properties().entrySet())if(!e.getValue().equals(property(s,e.getKey())))return false;
  }
  return true;
 }
 static String property(BlockState s,String name){for(var p:s.getProperties())if(p.getName().equals(name))return value(s,p);return null;}
 private static <T extends Comparable<T>>String value(BlockState s,Property<T> p){return p.getName(s.getValue(p));}
 private FixedStaffDoorPlan(){}
}
