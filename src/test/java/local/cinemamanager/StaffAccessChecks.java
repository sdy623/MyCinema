package local.cinemamanager;
import local.cinemamanager.doors.DoorAccess;
import local.cinemamanager.doors.DoorGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
public final class StaffAccessChecks {
    private static int count;
    private static void check(boolean value,String name){count++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        for(BlockPos p:new BlockPos[]{BlockPos.ZERO,new BlockPos(-120,69,400),new BlockPos(12,69,19)}) for(Direction f:Direction.Plane.HORIZONTAL){
            Vec3 plane=Vec3.atCenterOf(p).add(-f.getStepX()*(.5-DoorGeometry.THICKNESS/2),0,-f.getStepZ()*(.5-DoorGeometry.THICKNESS/2));
            for(double distance:new double[]{.00001,.05,.4,1,3,20}){
                Vec3 front=plane.add(f.getStepX()*distance,0,f.getStepZ()*distance),back=plane.add(-f.getStepX()*distance,0,-f.getStepZ()*distance);
                check(!DoorAccess.STAFF_FRONT.allows(f,p,front),"front blocks facing side");check(DoorAccess.STAFF_FRONT.allows(f,p,back),"front allows interior");
                check(DoorAccess.STAFF_BACK.allows(f,p,front),"back allows reverse interior");check(!DoorAccess.STAFF_BACK.allows(f,p,back),"back blocks reverse entry");
                check(DoorAccess.PUBLIC.allows(f,p,front)&&DoorAccess.PUBLIC.allows(f,p,back),"default both sides");
            }
            for(DoorAccess a:new DoorAccess[]{DoorAccess.STAFF_FRONT,DoorAccess.STAFF_BACK}){
                check(!a.allows(f,p,plane),"on actual seal plane fails closed");check(!a.allows(f,p,new Vec3(Double.NaN,0,0)),"NaN denied");check(!a.allows(f,p,new Vec3(0,0,Double.POSITIVE_INFINITY)),"infinite denied");check(!a.allows(Direction.UP,p,plane),"vertical malformed facing denied");
            }
        }
        check(DoorAccess.STAFF_FRONT.getSerializedName().equals("staff_front"),"front schema");check(DoorAccess.STAFF_BACK.getSerializedName().equals("staff_back"),"back schema");check(DoorAccess.PUBLIC.getSerializedName().equals("public"),"default schema");
        System.out.println("PASS: "+count+" staff-side assertions; actual closed-panel plane, all orientations, negative world positions, malformed coordinates, public compatibility.");
    }
}
