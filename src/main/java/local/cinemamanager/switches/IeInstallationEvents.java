package local.cinemamanager.switches;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
/** Critical and route invalidations are separate: missing downstream never prohibits authentic OFF. */
final class IeInstallationEvents {
 static void at(ServerLevel l,BlockPos p,String why){
  IeInstallationBinding r=IeInstallationBinding.read(l);if(r==null)return;
  for(var c:IeCircuitPlan.CIRCUITS)for(var e:c.endpoints()){
   if(p.equals(IeCircuitValidation.pos(e.pos()))||(e.support()!=null&&p.equals(IeCircuitValidation.pos(e.support()))))
    r.invalidate(c.id(),e==c.controller()||e==c.breaker(),why);
  }
 }
 static void piston(ServerLevel l,BlockPos p){
  IeInstallationBinding r=IeInstallationBinding.read(l);if(r==null)return;
  for(var c:IeCircuitPlan.CIRCUITS)for(var e:c.endpoints()){
   if(IeCircuitValidation.pos(e.pos()).distManhattan(p)<=14)
    r.invalidate(c.id(),e==c.controller()||e==c.breaker(),"near observed piston; explicit inspection/rebind required");
  }
 }
 private IeInstallationEvents(){}
}
