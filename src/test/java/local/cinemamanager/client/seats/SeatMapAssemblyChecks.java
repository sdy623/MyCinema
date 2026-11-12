package local.cinemamanager.client.seats;

import java.util.*;
import local.cinemamanager.seats.SeatRegistry;
import local.cinemamanager.seats.map.*;
import static local.cinemamanager.seats.map.PublicSeatMap.*;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;
import static local.cinemamanager.seats.map.SeatMapSnapshot.*;

/** Source-only until the shared resource gate permits execution. */
public final class SeatMapAssemblyChecks {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("accepted invalid refresh");}catch(IllegalArgumentException expected){}}
    private static View view(String snapshot){
        Header header=new Header(snapshot,new Revisions(1,0,2,0),new Frame(new Point(0,0),Orientation.NORTH,1,new MapBounds(-10,-10,10,10)),"","","",List.of(),List.of(),List.of(),List.of(),List.of());
        Availability unknown=new Availability(SeatRegistry.Status.AVAILABLE,ProofStatus.UNLOADED,TicketAvailability.UNKNOWN,Occupancy.UNKNOWN);
        return new View(header,List.of(new SeatView("chair_a","","","QA",1,"A",1,new Point(-7,-2),null,unknown),new SeatView("chair_b","","","QA",2,"2",4,new Point(6,3),null,unknown)));
    }
    public static void main(String[] args){
        SeatMapAssembly assembly=new SeatMapAssembly();List<Page> old=view("old").pages(1),fresh=view("fresh").pages(1);
        assembly.begin();check(!assembly.accept(old.get(0),200),"partial must remain hidden");check(assembly.complete()==null,"partial leaked");
        check(assembly.accept(old.get(1),200),"complete accepted");check(!assembly.stale(),"fresh flag");View complete=assembly.complete();
        check(complete.seats().get(0).point().equals(new Point(-7,-2))&&complete.seats().get(1).point().equals(new Point(6,3)),"irregular coordinates retained");
        assembly.begin();check(assembly.stale()&&assembly.complete()==complete,"refresh retains stale complete");assembly.accept(fresh.get(0),200);reject(()->assembly.accept(old.get(1),200));check(assembly.complete()==complete&&assembly.stale(),"mixed snapshot replaced complete");
        assembly.begin();reject(()->assembly.accept(fresh.get(1),200));check(assembly.complete()==complete,"out of order replaced complete");
        assembly.begin();assembly.accept(fresh.get(0),200);reject(()->assembly.accept(fresh.get(0),200));
        assembly.begin();reject(()->assembly.accept(fresh.get(0),SeatMapAssembly.MAX_PACKET_BYTES+1));
        assembly.begin();assembly.accept(fresh.get(0),200);assembly.fail();check(assembly.complete()==complete&&assembly.stale(),"timeout lost old view");
        assembly.begin();assembly.accept(fresh.get(0),200);check(assembly.accept(fresh.get(1),200)&&assembly.complete().header().snapshotId().equals("fresh"),"recovery failed");
        View recovered=assembly.complete();
        List<SeatView> many=new ArrayList<>();for(int i=0;i<65;i++)many.add(new SeatView("seat_"+i,"","","QA",1,"1",i+1,new Point(-9+i*.25,5),null,new Availability(SeatRegistry.Status.AVAILABLE,ProofStatus.UNLOADED,TicketAvailability.UNKNOWN,Occupancy.UNKNOWN)));
        View large=new View(view("large").header(),many);
        assembly.begin();reject(()->assembly.accept(large.pages(1).getFirst(),200));check(assembly.complete()==recovered,"page count limit lost complete");
        List<Page> budget=new View(view("budget").header(),many.subList(0,24)).pages(1);
        assembly.begin();for(int i=0;i<21;i++)check(!assembly.accept(budget.get(i),SeatMapAssembly.MAX_PACKET_BYTES),"budget partial leaked");
        reject(()->assembly.accept(budget.get(21),SeatMapAssembly.MAX_PACKET_BYTES));check(assembly.complete()==recovered&&assembly.stale(),"snapshot byte budget lost complete");
        System.out.println("Seat map atomic assembly checks passed");
    }
}
