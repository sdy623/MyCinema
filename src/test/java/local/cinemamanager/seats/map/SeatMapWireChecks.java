package local.cinemamanager.seats.map;

import java.util.*;
import local.cinemamanager.seats.*;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;
import static local.cinemamanager.seats.map.SeatMapSnapshot.*;
import static local.cinemamanager.seats.map.PublicSeatMap.*;

/** Source-only regression entry point; no JVM was started while writing these checks. */
public final class SeatMapWireChecks {
    private static String uuid(int n){return new UUID(0,n).toString();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
    private static View sample(){
        List<SourceSeat> seats=new ArrayList<>();
        for(int i=0;i<444;i++){
            Position pos=new Position(10+(i%37)*2,70+i/37,20+(i/37)*3);
            SeatRegistry.Seat seat=new SeatRegistry.Seat(uuid(100+i),uuid(2),"实际座椅区域",i/37+1,i%37+1,pos.packed(),SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,uuid(1000+i),GuidePreset.RowScheme.LETTERS);
            seats.add(new SourceSeat(seat,"","",new Observation(pos,ObservedBlock.CHAIR,seat.seatUid(),Orientation.NORTH,true,i%2==0?Occupancy.EMPTY:Occupancy.OCCUPIED),TicketAvailability.UNKNOWN));
        }
        var layout=new Layout(0,new Bounds(0,60,0,100,100,100),List.of(),List.of(),List.of(),List.of());
        var snapshot=new SeatMapSnapshot("snapshot",new Capture(new Scope(uuid(1),uuid(2),"minecraft:overworld"),1,1,ShowSelection.none(),seats),layout,new Transform(new Point(0,0),Orientation.NORTH,1));
        Map<String,String> keys=new HashMap<>();
        return project(snapshot,Selection.all(),(kind,id)->keys.computeIfAbsent(kind+":"+id,k->"display"+keys.size()));
    }
    public static void run(){
        View view=sample();List<String> wire=SeatMapWire.pages(view,false);
        check(wire.size()==7,"444 actual seats fit seven bounded pages");
        List<Page> pages=wire.stream().map(SeatMapWire::decode).map(SeatMapWire.Data::page).toList();
        check(assemble(pages).equals(view),"full serialization and assembly preserve coordinates and labels");
        check(view.header().floors().isEmpty()&&view.header().zones().isEmpty(),"no invented unconfigured floors or boundaries");
        check(view.seats().stream().allMatch(s->s.floorKey().isEmpty()&&s.zoneKey().isEmpty()&&s.availability().tickets()==TicketAvailability.UNKNOWN),"unconfigured seats cannot invent floor assignments or admission");
        check(view.seats().stream().anyMatch(s->s.availability().occupancy()==Occupancy.OCCUPIED),"occupancy survives public projection independently");
        String all=String.join("",wire);for(int i=0;i<444;i++){check(!all.contains(uuid(100+i)),"private registry ID absent");check(!all.contains(uuid(1000+i)),"physical UID absent");}
        check(wire.stream().allMatch(s->SeatMapWire.bytes(s)<=SeatMapWire.MAX_JSON_BYTES),"actual UTF8 packet budget");
        reject(()->assemble(pages.subList(0,6)),"no partial display");
        reject(()->new Selection("bad/key",""),"bad selection keys fail");
        var first=view.seats().getFirst();
        reject(()->new View(view.header(),List.of(new SeatView(first.displayKey(),"madeup","",first.zone(),first.row(),first.rowLabel(),first.number(),first.point(),first.facing(),first.availability()))),"unknown nonempty floor fails");
        List<AisleView> aisles=new ArrayList<>();List<Point> ring=new ArrayList<>();
        for(int i=0;i<64;i++){double a=i*Math.PI*2/64;ring.add(new Point(50+40*Math.cos(a),50+40*Math.sin(a)));}
        for(int i=0;i<128;i++)aisles.add(new AisleView("aisle"+i,"人工通道", "floor","",ring));
        Header huge=new Header("large",new Revisions(0,0,0,0),view.header().frame(),"","","",List.of(new FloorView("floor","Authored")),List.of(),List.of(),aisles,List.of());
        reject(()->SeatMapWire.pages(new View(huge,List.of()),true),"oversized header rejected before first page");
        reject(()->SeatMapWire.decode("中".repeat(SeatMapWire.MAX_JSON_BYTES/2)),"UTF8 byte limit differs from character count");
        var bounds=new Bounds(0,0,0,10,10,10);var poly=new Polygon(List.of(new Point(0,0),new Point(10,0),new Point(10,10),new Point(0,10)));
        var doc=new SeatMapLayoutDocument(1,new Scope(uuid(1),uuid(2),"minecraft:overworld"),new Layout(1,bounds,List.of(new Floor("f","Authored floor",1,10),new Floor("lobby","Lower entrance",0,0)),List.of(new Zone("z","f","Zone",poly),new Zone("other","f","Other explicit section",poly)),List.of(new Entrance("entry","Front",new Position(1,0,1),"lobby","",List.of(),List.of(new RowGroup("f","z",1)))),List.of()),new Transform(new Point(0,0),Orientation.NORTH,1),List.of(new SeatMapLayoutDocument.Assignment(new Position(1,1,1).packed(),uuid(42),"f","z")));
        check(SeatMapLayoutDocument.decode(doc.encode()).equals(doc),"authored entrance plus canonical assignment round trip");
        var physical=new SeatRegistry.Seat(uuid(50),uuid(2),"Whole numbered row",1,1,new Position(1,1,1).packed(),SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,uuid(42),GuidePreset.RowScheme.NUMERIC);
        var source=new SourceSeat(physical,"f","z",new Observation(new Position(1,1,1),ObservedBlock.CHAIR,uuid(42),Orientation.NORTH,true,Occupancy.EMPTY),TicketAvailability.UNKNOWN);
        var crossFloor=new SeatMapSnapshot("cross_floor",new Capture(doc.scope(),1,1,ShowSelection.none(),List.of(source)),doc.layout(),doc.transform());
        Map<String,String> display=new HashMap<>();var selected=project(crossFloor,new Selection("f","z"),(kind,id)->display.computeIfAbsent(kind+":"+id,k->"choice"+display.size()));
        check(selected.header().zones().size()==2&&selected.seats().size()==1,"selected spatial section retains all floor section choices");
        check(crossFloor.entranceSeats().get("entry").equals(List.of(uuid(50))),"explicit lower entrance to upper row linkage retained without path inference");
        var foreign=new SeatRegistry.Seat(uuid(51),uuid(999),physical.zone(),1,1,physical.position(),physical.status(),physical.block(),physical.seatUid(),physical.rowScheme());
        reject(()->new SeatMapSnapshot("foreign",new Capture(doc.scope(),1,1,ShowSelection.none(),List.of(new SourceSeat(foreign,"f","z",source.observation(),TicketAvailability.UNKNOWN))),doc.layout(),doc.transform()),"foreign hall registry reference rejected");
        reject(()->SeatMapLayoutDocument.decode(doc.encode().replace("\"floorId\":\"lobby\"","\"floorId\":\"absent\"")),"unknown entrance floor rejected");
        reject(()->SeatMapLayoutDocument.decode(doc.encode().replace("\"x\":0.0","\"x\":\"0.0\"")),"numeric coordinate string rejected");
        reject(()->SeatMapLayoutDocument.decode(doc.encode()+" {}"),"trailing JSON rejected");
        reject(()->SeatMapLayoutDocument.decode(doc.encode().replace("\"version\":1","\"version\":1,\"unknown\":0")),"unknown field rejected");
        reject(()->SeatMapLayoutDocument.decode(doc.encode().replace("\"version\":1","\"version\":1,\"version\":1")),"duplicate field rejected");
        reject(()->SeatMapLayoutDocument.decode(doc.encode().replace("\"version\":1","\"version\":2")),"future format fail closed");
        reject(()->SeatMapLayoutDocument.decode(doc.encode().replace("\"minY\":0","\"minY\":0.5")),"fractional authored integer rejected");
    }
    public static void main(String[] args){run();System.out.println("SeatMapWireChecks passed");}
}
