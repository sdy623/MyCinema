package local.cinemamanager.doors;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.BooleanOp;

/** Pure offline checks: native VoxelShapes and the exact state-decision helper used at runtime. */
public final class DoorGeometryChecks {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        for (Direction facing : Direction.Plane.HORIZONTAL) for (DoorHingeSide hinge : DoorHingeSide.values())
            for (boolean open : new boolean[]{false,true}) for (boolean power : new boolean[]{false,true}) for (DoorPart part : DoorPart.values()) {
                var shape=DoorGeometry.shape(facing,hinge,open); AABB box=shape.bounds();
                check(box.minY==0 && box.maxY==1,"every section has a full metre, including upper");
                check(box.minX>=0 && box.maxX<=1 && box.minZ>=0 && box.maxZ<=1,"collision remains within its own cell");
                check(Math.abs(box.getXsize()*box.getYsize()*box.getZsize()-3./16)<1e-10,"exact solid 3/16 slab");
                check(shape.toAabbs().size()==1,"no hole in panel");
                check(part.offset()>=0&&part.offset()<3,"unique bounded part offset");
                var center=Shapes.box(.3,.1,.3,.7,.9,.7);
                check(!Shapes.joinIsNotEmpty(shape,center,BooleanOp.AND),"standing center remains clear; panel is at edge");
                Direction edge=DoorGeometry.panelEdge(facing,hinge,open);
                check(edge.getAxis().isHorizontal(),"panel never creates horizontal header");
                check(open ? edge.getAxis()!=facing.getAxis() : edge==facing.getOpposite(),"correct swing plane");
            }
        for(Direction facing:Direction.Plane.HORIZONTAL) {
            var a=DoorGeometry.partnerDirection(facing,DoorHingeSide.LEFT);
            var b=DoorGeometry.partnerDirection(facing,DoorHingeSide.RIGHT);
            check(a==b.getOpposite(),"double pairing reciprocal");
            check(DoorGeometry.panelEdge(facing,DoorHingeSide.LEFT,true)==a.getOpposite(),"left opened leaf rests on outer jamb");
            check(DoorGeometry.panelEdge(facing,DoorHingeSide.RIGHT,true)==b.getOpposite(),"right opened leaf rests on outer jamb");
            check(DoorGeometry.compatible(facing,DoorHingeSide.LEFT,facing,DoorHingeSide.RIGHT),"compatible pair");
            check(!DoorGeometry.compatible(facing,DoorHingeSide.LEFT,facing,DoorHingeSide.LEFT),"same hinges do not pair");
            check(!DoorGeometry.compatible(facing,DoorHingeSide.LEFT,facing.getOpposite(),DoorHingeSide.RIGHT),"opposed doors do not pair");
        }
        for(Direction facing:Direction.Plane.HORIZONTAL) {
            Direction across=DoorGeometry.partnerDirection(facing,DoorHingeSide.LEFT);
            var union=Shapes.empty();
            for(int y=0;y<3;y++) {
                union=Shapes.or(union,DoorGeometry.shape(facing,DoorHingeSide.LEFT,true).move(0,y,0));
                union=Shapes.or(union,DoorGeometry.shape(facing,DoorHingeSide.RIGHT,true).move(across.getStepX(),y,across.getStepZ()));
            }
            double minX=Math.min(0,across.getStepX()),minZ=Math.min(0,across.getStepZ());
            double t=DoorGeometry.THICKNESS;
            var clear=facing.getAxis()==Direction.Axis.Z?Shapes.box(minX+t,0,0,minX+2-t,3,1):Shapes.box(0,0,minZ+t,1,3,minZ+2-t);
            check(!Shapes.joinIsNotEmpty(union,clear,BooleanOp.AND),"entire continuous 1.625m wide by3m high passage clear, not just centerline");
            check(Math.abs((facing.getAxis()==Direction.Axis.Z?clear.bounds().getXsize():clear.bounds().getZsize())-1.625)<1e-10,"honest nominal2m versus minimum clear1.625m width");
            var expanded=Shapes.create(clear.bounds().inflate(facing.getAxis()==Direction.Axis.Z?.01:0,0,facing.getAxis()==Direction.Axis.X?.01:0));
            check(Shapes.joinIsNotEmpty(union,expanded,BooleanOp.AND),"exact jamb boundaries verified");
            check(clear.bounds().minY==0&&clear.bounds().maxY==3,"no lowered aperture or hidden header");
        }
        for(boolean signal:new boolean[]{false,true}) for(boolean powered:new boolean[]{false,true}) for(boolean open:new boolean[]{false,true})
            for(Boolean partner:new Boolean[]{null,false,true}) {
                boolean actual=DoorGeometry.reconciledOpen(signal,powered,open,partner);
                boolean edge=signal!=powered||(partner!=null&&partner!=signal);
                check(actual==(edge?signal:open),"redstone edge updates while steady power preserves manual state");
                check(DoorGeometry.reconciledOpen(signal,signal,actual,partner==null?null:signal)==actual,"reconciliation is idempotent; no self oscillation");
            }
        for(DoorPart part:DoorPart.values()) check(part.getSerializedName().equals(new String[]{"lower","middle","upper"}[part.offset()]),"stable serialized part");
        System.out.println("PASS: "+checks+" offline assertions:96 state geometries, reciprocal double hinges, full3m panels, redstone/manual decision table and idempotence. Runtime lifecycle is separate GameTest coverage.");
    }
}
