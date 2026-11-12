package local.cinemamanager.switches;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Optional IE adapter: only fixed public read methods and the official empty-hand controller interaction. */
final class IeSwitchBridge {
    private static final String PACKAGE="blusunrize.immersiveengineering.";
    static final String CONTROLLER=PACKAGE+"common.blocks.metal.BreakerSwitchBlockEntity";
    static final String BREAKER=PACKAGE+"common.blocks.metal.RedstoneBreakerBlockEntity";
    private static Object invoke(Object o,String method) throws ReflectiveOperationException {
        return o.getClass().getMethod(method).invoke(o);
    }
    static boolean active(BlockEntity be) throws ReflectiveOperationException { return (Boolean)invoke(be,"getIsActive"); }
    static boolean inverted(BlockEntity be) throws ReflectiveOperationException { return be.getClass().getField("inverted").getBoolean(be); }
    static int externalWires(ServerLevel level,BlockEntity be) throws ReflectiveOperationException {
        int count=0;
        for (int i=0;i<2;i++) {
            Collection<?> connections=connections(level,be.getBlockPos(),i);
            if (connections==null) throw new IllegalStateException("Controller network unknown");
            for(Object c:connections) if(!(Boolean)invoke(c,"isInternal")) count++;
        }
        return count;
    }
    static Boolean internal(ServerLevel level,BlockPos pos) throws ReflectiveOperationException {
        Collection<?> connections=connections(level,pos,0);
        if(connections==null || connections(level,pos,1)==null) return null;
        for(Object c:connections) {
            if(!(Boolean)invoke(c,"isInternal"))continue;
            Object a=invoke(c,"getEndA"),b=invoke(c,"getEndB");
            if(pos.equals(invoke(a,"position"))&&pos.equals(invoke(b,"position"))) {
                int ai=(Integer)invoke(a,"index"),bi=(Integer)invoke(b,"index");
                if((ai==0&&bi==1)||(ai==1&&bi==0))return true;
            }
        }
        return false;
    }
    static Collection<?> connections(ServerLevel level,BlockPos pos,int index) throws ReflectiveOperationException {
        ClassLoader loader=IeSwitchBridge.class.getClassLoader();
        Class<?> cp=Class.forName(PACKAGE+"api.wires.ConnectionPoint",false,loader);
        Class<?> gn=Class.forName(PACKAGE+"api.wires.GlobalWireNetwork",false,loader);
        Object point=cp.getConstructor(BlockPos.class,int.class).newInstance(pos,index);
        Object global=gn.getMethod("getNetwork",Level.class).invoke(null,level);
        if(global==null)return null;
        // getNullableLocalNet is required: read-only checks must not create a network.
        Object local=gn.getMethod("getNullableLocalNet",cp).invoke(global,point);
        return local==null?null:(Collection<?>)local.getClass().getMethod("getConnections",cp).invoke(local,point);
    }
    static boolean request(BlockEntity controller,boolean on) throws ReflectiveOperationException {
        if(!controller.getClass().getName().equals(CONTROLLER)||inverted(controller))return false;
        if(active(controller)==on)return true;
        Method method=controller.getClass().getMethod("interact",Direction.class,Player.class,InteractionHand.class,
                ItemStack.class,float.class,float.class,float.class);
        // 12.4.2-194's empty-hand branch never dereferences Player. Native validation remains held.
        method.invoke(controller,Direction.UP,null,InteractionHand.MAIN_HAND,ItemStack.EMPTY,0.5F,0.5F,0.5F);
        return active(controller)==on;
    }
    static String exactGraph(ServerLevel level,IeCircuitPlan.Circuit plan) throws ReflectiveOperationException {
        Set<String> expected=new TreeSet<>(),seen=new TreeSet<>();
        Map<IeCircuitPlan.Point,Set<String>> expectedAt=new HashMap<>();
        for(var e:plan.edges()){
            if(!expected.add(e.key()))return "DUPLICATE_PINNED_EDGE";
            expectedAt.computeIfAbsent(e.a(),k->new TreeSet<>()).add(e.key());
            expectedAt.computeIfAbsent(e.b(),k->new TreeSet<>()).add(e.key());
        }
        Object copperIns=Class.forName(PACKAGE+"api.wires.WireType",false,IeSwitchBridge.class.getClassLoader()).getField("COPPER_INSULATED").get(null);
        for(var endpoint:plan.endpoints()){
            if(endpoint.source())continue;
            BlockEntity be=level.getBlockEntity(IeCircuitValidation.pos(endpoint.pos()));
            Collection<?> points=(Collection<?>)invoke(be,"getConnectionPoints");
            Set<IeCircuitPlan.Point> actualPoints=new HashSet<>();
            for(Object cp:points){
                BlockPos p=(BlockPos)invoke(cp,"position");int index=(Integer)invoke(cp,"index");
                if(!p.equals(be.getBlockPos()))return "FOREIGN_CONNECTION_POINT";
                var point=new IeCircuitPlan.Point(endpoint.pos(),index);if(!actualPoints.add(point))return "DUPLICATE_CONNECTION_POINT";
                Collection<?> edges=connections(level,p,index);if(edges==null)return "UNKNOWN_NETWORK: "+endpoint.role();
                Set<String> found=new TreeSet<>();
                for(Object edge:edges){
                    var a=point(invoke(edge,"getEndA"));var b=point(invoke(edge,"getEndB"));
                    if(!a.equals(point)&&!b.equals(point))return "NONINCIDENT_EDGE";
                    if((Boolean)invoke(edge,"isInternal")){
                        // Official pure signal breaker_switch also creates its own isolated internal edge when ON.
                        // This never permits external controller power wires or internal edges at any other endpoint.
                        if(!allowedInternal(plan,a,b))return "UNEXPECTED_INTERNAL_EDGE";
                        continue;
                    }
                    if(edge.getClass().getField("type").get(edge)!=copperIns)return "WRONG_WIRE_TYPE: "+endpoint.role();
                    String key=new IeCircuitPlan.Edge(a,b).key();if(!found.add(key))return "DUPLICATE_EXTERNAL_EDGE";
                    seen.add(key);
                }
                if(!found.equals(expectedAt.getOrDefault(point,Set.of())))return "EXACT_TOPOLOGY_MISMATCH: "+endpoint.role()+" CP"+index;
            }
            Set<IeCircuitPlan.Point> required=new HashSet<>();
            for(var p:expectedAt.keySet())if(p.pos().equals(endpoint.pos()))required.add(p);
            if(endpoint.role().endsWith("_controller")){required.add(new IeCircuitPlan.Point(endpoint.pos(),0));required.add(new IeCircuitPlan.Point(endpoint.pos(),1));}
            if(!actualPoints.equals(required))return "CONNECTION_POINT_SET_MISMATCH: "+endpoint.role();
        }
        return seen.equals(expected)?null:"INCOMPLETE_GRAPH";
    }
    static boolean allowedInternal(IeCircuitPlan.Circuit plan,IeCircuitPlan.Point a,IeCircuitPlan.Point b){
        return a.pos().equals(b.pos())&&(a.pos().equals(plan.breaker().pos())||a.pos().equals(plan.controller().pos()))
                &&Set.of(0,1).equals(new HashSet<>(List.of(a.index(),b.index())));
    }
    private static IeCircuitPlan.Point point(Object p)throws ReflectiveOperationException {
        BlockPos pos=(BlockPos)invoke(p,"position");return new IeCircuitPlan.Point(new RegisteredSwitchEngine.Pos(pos.getX(),pos.getY(),pos.getZ()),(Integer)invoke(p,"index"));
    }
    private IeSwitchBridge(){}
}
