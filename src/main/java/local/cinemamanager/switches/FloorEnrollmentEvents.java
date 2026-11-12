package local.cinemamanager.switches;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
final class FloorEnrollmentEvents {
 static void at(ServerLevel l,BlockPos at,String why){
  var r=FloorPositionEnrollment.read(l);if(r==null)return;
  for(var p:AuxiliaryPlan.PLANS)if(p.floor())for(var c:p.cells())if(at.equals(IeCircuitValidation.pos(c.pos())))
   r.invalidate(p.id(),c.pos().equals(p.controller())||c.pos().equals(p.support()),why);
 }
 static void piston(ServerLevel l,BlockPos at){
  var r=FloorPositionEnrollment.read(l);if(r==null)return;
  for(var p:AuxiliaryPlan.PLANS)if(p.floor())for(var c:p.cells())if(IeCircuitValidation.pos(c.pos()).distManhattan(at)<=14)
   r.invalidate(p.id(),c.pos().equals(p.controller())||c.pos().equals(p.support()),"near piston; inspect and manually re-enroll");
 }
 private FloorEnrollmentEvents(){}
}
