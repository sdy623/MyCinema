package local.cinemamanager.switches;
import java.util.*;
import static local.cinemamanager.switches.RegisteredSwitchEngine.*;
/** Compiled finite plan; no runtime JSON/coordinates accepted. */
final class IeCircuitPlan {
 static final String DIGEST="330904374c4c8fa10c211e18d0d1be0d390402bc61e0e3a065bd4b07fadffaf8";
 record Endpoint(Pos pos,String block,String beType,String beClass,String owner,String role,String facing,Pos support,String supportBlock,int rotation,boolean lamp,boolean source) {}
 record Point(Pos pos,int index) {}
 record Edge(Point a,Point b) { String key(){String x=key(a),y=key(b);return x.compareTo(y)<0?x+"|"+y:y+"|"+x;} static String key(Point p){return p.pos().x()+","+p.pos().y()+","+p.pos().z()+":"+p.index();}}
 record Circuit(String id,Endpoint controller,Endpoint breaker,List<Endpoint> endpoints,List<Edge> edges,int lampCount) {}
 static final List<Endpoint> SCREENWASH_TOP_ENDPOINTS=List.of(
  new Endpoint(new Pos(17,98,9),"immersiveengineering:breaker_switch","immersiveengineering:breakerswitch","blusunrize.immersiveengineering.common.blocks.metal.BreakerSwitchBlockEntity",null,"screenwash_top_controller","north",new Pos(17,98,8),"minecraft:black_concrete",0,false,false),
  new Endpoint(new Pos(17,99,9),"immersiveengineering:redstone_breaker","immersiveengineering:redstonebreaker","blusunrize.immersiveengineering.common.blocks.metal.RedstoneBreakerBlockEntity",null,"screenwash_top_breaker","up",new Pos(17,100,9),"minecraft:black_concrete",0,false,false),
  new Endpoint(new Pos(15,98,9),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_top_left_relay","up",new Pos(15,99,9),"minecraft:black_wool",-1,false,false),
  new Endpoint(new Pos(16,99,10),"immersiveengineering:capacitor_creative","immersiveengineering:capacitorcreative","blusunrize.immersiveengineering.common.blocks.metal.CapacitorCreativeBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","screen_wash_ie9_rc1",null,null,null,-1,false,true),
  new Endpoint(new Pos(16,99,9),"immersiveengineering:connector_lv","immersiveengineering:lv_conn","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","source_connector","south",new Pos(16,99,10),"immersiveengineering:capacitor_creative",-1,false,false),
  new Endpoint(new Pos(18,99,10),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","relay_1","up",new Pos(18,100,10),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(26,99,10),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","relay_2","up",new Pos(26,100,10),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(38,99,10),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","relay_3","up",new Pos(38,100,10),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(50,99,10),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","relay_4","up",new Pos(50,100,10),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(14,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_1","down",new Pos(14,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(19,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_2","down",new Pos(19,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(24,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_3","down",new Pos(24,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(29,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_4","down",new Pos(29,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(34,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_5","down",new Pos(34,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(39,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_6","down",new Pos(39,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(44,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_7","down",new Pos(44,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(49,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_8","down",new Pos(49,99,9),"minecraft:black_wool",-1,true,false),
  new Endpoint(new Pos(54,98,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity","eb71a177-f6b0-4cfd-b7dd-5cbe8a1013f5","lamp_9","down",new Pos(54,99,9),"minecraft:black_wool",-1,true,false));
 static final List<Edge> SCREENWASH_TOP_EDGES=List.of(
  new Edge(new Point(new Pos(16,99,9),0),new Point(new Pos(17,99,9),0)),
  new Edge(new Point(new Pos(17,99,9),1),new Point(new Pos(18,99,10),0)),
  new Edge(new Point(new Pos(18,99,10),0),new Point(new Pos(26,99,10),0)),
  new Edge(new Point(new Pos(26,99,10),0),new Point(new Pos(38,99,10),0)),
  new Edge(new Point(new Pos(38,99,10),0),new Point(new Pos(50,99,10),0)),
  new Edge(new Point(new Pos(18,99,10),0),new Point(new Pos(15,98,9),0)),
  new Edge(new Point(new Pos(15,98,9),0),new Point(new Pos(14,98,9),0)),
  new Edge(new Point(new Pos(18,99,10),0),new Point(new Pos(19,98,9),0)),
  new Edge(new Point(new Pos(26,99,10),0),new Point(new Pos(24,98,9),0)),
  new Edge(new Point(new Pos(26,99,10),0),new Point(new Pos(29,98,9),0)),
  new Edge(new Point(new Pos(38,99,10),0),new Point(new Pos(34,98,9),0)),
  new Edge(new Point(new Pos(38,99,10),0),new Point(new Pos(39,98,9),0)),
  new Edge(new Point(new Pos(38,99,10),0),new Point(new Pos(44,98,9),0)),
  new Edge(new Point(new Pos(50,99,10),0),new Point(new Pos(49,98,9),0)),
  new Edge(new Point(new Pos(50,99,10),0),new Point(new Pos(54,98,9),0)));
 static final List<Endpoint> SCREENWASH_BOTTOM_ENDPOINTS=List.of(
  new Endpoint(new Pos(14,64,7),"immersiveengineering:capacitor_creative","immersiveengineering:capacitorcreative","blusunrize.immersiveengineering.common.blocks.metal.CapacitorCreativeBlockEntity",null,"screenwash_bottom_source",null,null,null,-1,false,true),
  new Endpoint(new Pos(14,64,8),"immersiveengineering:connector_lv","immersiveengineering:lv_conn","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_connector","north",new Pos(14,64,7),"immersiveengineering:capacitor_creative",-1,false,false),
  new Endpoint(new Pos(15,64,7),"immersiveengineering:breaker_switch","immersiveengineering:breakerswitch","blusunrize.immersiveengineering.common.blocks.metal.BreakerSwitchBlockEntity",null,"screenwash_bottom_controller","down",new Pos(15,63,7),"minecraft:black_concrete",0,false,false),
  new Endpoint(new Pos(15,64,8),"immersiveengineering:redstone_breaker","immersiveengineering:redstonebreaker","blusunrize.immersiveengineering.common.blocks.metal.RedstoneBreakerBlockEntity",null,"screenwash_bottom_breaker","south",new Pos(15,64,9),"minecraft:polished_deepslate",0,false,false),
  new Endpoint(new Pos(16,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_0","south",new Pos(16,64,9),"minecraft:polished_deepslate",-1,false,false),
  new Endpoint(new Pos(28,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_1","south",new Pos(28,64,9),"minecraft:polished_deepslate",-1,false,false),
  new Endpoint(new Pos(40,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_2","south",new Pos(40,64,9),"minecraft:polished_deepslate",-1,false,false),
  new Endpoint(new Pos(52,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_3","south",new Pos(52,64,9),"minecraft:polished_deepslate",-1,false,false),
  new Endpoint(new Pos(16,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_0","up",new Pos(16,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(16,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_1","up",new Pos(16,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(22,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_1","up",new Pos(22,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(22,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_2","up",new Pos(22,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(28,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_2","up",new Pos(28,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(28,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_3","up",new Pos(28,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(34,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_3","up",new Pos(34,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(34,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_4","up",new Pos(34,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(40,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_4","up",new Pos(40,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(40,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_5","up",new Pos(40,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(46,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_5","up",new Pos(46,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(46,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_6","up",new Pos(46,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(52,65,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_elbow_6","up",new Pos(52,66,8),"minecraft:black_concrete",-1,false,false),
  new Endpoint(new Pos(52,65,9),"immersiveengineering:floodlight","immersiveengineering:floodlight","blusunrize.immersiveengineering.common.blocks.metal.FloodlightBlockEntity",null,"screenwash_bottom_lamp_7","up",new Pos(52,64,9),"minecraft:polished_deepslate",-1,true,false),
  new Endpoint(new Pos(22,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_mid_0","south",new Pos(22,64,9),"minecraft:polished_deepslate",-1,false,false),
  new Endpoint(new Pos(34,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_mid_1","south",new Pos(34,64,9),"minecraft:polished_deepslate",-1,false,false),
  new Endpoint(new Pos(46,64,8),"immersiveengineering:connector_lv_relay","immersiveengineering:lv_relay","blusunrize.immersiveengineering.common.blocks.metal.EnergyConnectorBlockEntity",null,"screenwash_bottom_trunk_mid_2","south",new Pos(46,64,9),"minecraft:polished_deepslate",-1,false,false));
 static final List<Edge> SCREENWASH_BOTTOM_EDGES=List.of(
  new Edge(new Point(new Pos(14,64,8),0),new Point(new Pos(15,64,8),0)),
  new Edge(new Point(new Pos(15,64,8),1),new Point(new Pos(16,64,8),0)),
  new Edge(new Point(new Pos(16,64,8),0),new Point(new Pos(22,64,8),0)),
  new Edge(new Point(new Pos(28,64,8),0),new Point(new Pos(34,64,8),0)),
  new Edge(new Point(new Pos(40,64,8),0),new Point(new Pos(46,64,8),0)),
  new Edge(new Point(new Pos(16,64,8),0),new Point(new Pos(16,65,8),0)),
  new Edge(new Point(new Pos(16,65,8),0),new Point(new Pos(16,65,9),0)),
  new Edge(new Point(new Pos(16,64,8),0),new Point(new Pos(22,65,8),0)),
  new Edge(new Point(new Pos(22,65,8),0),new Point(new Pos(22,65,9),0)),
  new Edge(new Point(new Pos(28,64,8),0),new Point(new Pos(28,65,8),0)),
  new Edge(new Point(new Pos(28,65,8),0),new Point(new Pos(28,65,9),0)),
  new Edge(new Point(new Pos(28,64,8),0),new Point(new Pos(34,65,8),0)),
  new Edge(new Point(new Pos(34,65,8),0),new Point(new Pos(34,65,9),0)),
  new Edge(new Point(new Pos(40,64,8),0),new Point(new Pos(40,65,8),0)),
  new Edge(new Point(new Pos(40,65,8),0),new Point(new Pos(40,65,9),0)),
  new Edge(new Point(new Pos(40,64,8),0),new Point(new Pos(46,65,8),0)),
  new Edge(new Point(new Pos(46,65,8),0),new Point(new Pos(46,65,9),0)),
  new Edge(new Point(new Pos(52,64,8),0),new Point(new Pos(52,65,8),0)),
  new Edge(new Point(new Pos(52,65,8),0),new Point(new Pos(52,65,9),0)),
  new Edge(new Point(new Pos(22,64,8),0),new Point(new Pos(28,64,8),0)),
  new Edge(new Point(new Pos(34,64,8),0),new Point(new Pos(40,64,8),0)),
  new Edge(new Point(new Pos(46,64,8),0),new Point(new Pos(52,64,8),0)));
 static final List<Circuit> CIRCUITS=List.of(new Circuit("screenwash_top",SCREENWASH_TOP_ENDPOINTS.get(0),SCREENWASH_TOP_ENDPOINTS.get(1),SCREENWASH_TOP_ENDPOINTS,SCREENWASH_TOP_EDGES,9),new Circuit("screenwash_bottom",SCREENWASH_BOTTOM_ENDPOINTS.get(2),SCREENWASH_BOTTOM_ENDPOINTS.get(3),SCREENWASH_BOTTOM_ENDPOINTS,SCREENWASH_BOTTOM_EDGES,7));
 static Circuit get(String id){return CIRCUITS.stream().filter(c->c.id().equals(id)).findFirst().orElse(null);}
 private IeCircuitPlan(){}
}
