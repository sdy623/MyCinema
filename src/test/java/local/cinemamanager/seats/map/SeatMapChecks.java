package local.cinemamanager.seats.map;

import java.lang.reflect.RecordComponent;
import java.util.*;
import local.cinemamanager.seats.GuidePreset;
import local.cinemamanager.seats.RowBatch;
import local.cinemamanager.seats.SeatRegistry;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;
import static local.cinemamanager.seats.map.SeatMapSnapshot.*;
import static local.cinemamanager.seats.map.PublicSeatMap.*;

/** Source-only acceptance checks. Run explicitly after the resource gate permits a JVM. */
public final class SeatMapChecks {
    private static final String CONTROLLER=uuid(1),HALL=uuid(2),SHOW=uuid(3);
    private static final Scope SCOPE=new Scope(CONTROLLER,HALL,"minecraft:overworld");
    private static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    private static void reject(Runnable run,String reason) { try{run.run();throw new AssertionError(reason);}catch(IllegalArgumentException expected){} }
    private static String uuid(int value) { return new UUID(0,value).toString(); }
    private static Polygon rectangle(double minX,double minZ,double maxX,double maxZ) {
        return new Polygon(List.of(new Point(minX,minZ),new Point(maxX,minZ),new Point(maxX,maxZ),new Point(minX,maxZ)));
    }
    private static DisplayIds ids() {
        Map<String,String> scoped=new HashMap<>();
        return (kind,internal)->scoped.computeIfAbsent(kind+":"+internal,k->"display_"+(scoped.size()+1));
    }
    private static Layout layout() {
        return new Layout(7,new Bounds(-20,0,-20,20,20,20),
            List.of(new Floor("lower","Lower floor",0,10),new Floor("upper","Balcony",11,20)),
            List.of(new Zone("lower_zone","lower","Lower",rectangle(-20,-20,20,20)),new Zone("upper_zone","upper","Upper",rectangle(-20,-20,20,20))),
            List.of(new Entrance("rear","Rear entrance",new Position(-12,2,9),"lower","lower_zone",List.of(),List.of(new RowGroup("lower","lower_zone",1)))),
            List.of(new Aisle("centre","Centre aisle","lower","",rectangle(-6,-10,-4,10))));
    }
    private static SourceSeat seat(int id,int x,int y,int z,int row,int number,String floor,GuidePreset.RowScheme scheme) {
        String zone=floor.equals("upper")?"Upper":"Lower";Position position=new Position(x,y,z);
        SeatRegistry.Seat inventory=new SeatRegistry.Seat(uuid(100+id),HALL,zone,row,number,position.packed(),SeatRegistry.Status.AVAILABLE,
            SeatRegistry.PHYSICAL_BLOCK,uuid(10000+id),scheme);
        return new SourceSeat(inventory,floor,floor+"_zone",new Observation(position,ObservedBlock.CHAIR,inventory.seatUid(),Orientation.NORTH,true,Occupancy.EMPTY),TicketAvailability.AVAILABLE);
    }
    private static List<SourceSeat> irregular() {
        return List.of(seat(1,-10,2,-7,1,1,"lower",GuidePreset.RowScheme.LETTERS),seat(2,-8,2,-7,1,2,"lower",GuidePreset.RowScheme.LETTERS),
            seat(3,-2,2,-7,1,3,"lower",GuidePreset.RowScheme.LETTERS),seat(4,-9,2,-3,2,1,"lower",GuidePreset.RowScheme.NUMERIC),
            seat(5,1,2,-3,2,2,"lower",GuidePreset.RowScheme.NUMERIC));
    }
    private static SeatMapSnapshot snapshot(List<SourceSeat> seats,Orientation orientation) {
        return new SeatMapSnapshot("snapshot_example",new Capture(SCOPE,10,22,new ShowSelection(SHOW,8),seats),layout(),new Transform(new Point(-15,-15),orientation,2));
    }
    private static double squaredDistance(Point a,Point b) { return (a.x()-b.x())*(a.x()-b.x())+(a.z()-b.z())*(a.z()-b.z()); }

    private static void coordinatesAndRotations() {
        List<SourceSeat> source=irregular();
        for(Orientation orientation:Orientation.values()){
            SeatMapSnapshot snapshot=snapshot(source,orientation);View view=project(snapshot,Selection.all(),ids());
            check(view.seats().size()==5,"no regular-grid fill");
            for(int i=0;i<source.size();i++)for(int j=i+1;j<source.size();j++)
                check(squaredDistance(view.seats().get(i).point(),view.seats().get(j).point())==4*squaredDistance(source.get(i).observation().position().xz(),source.get(j).observation().position().xz()),"all four rotations preserve every squared distance at uniform scale");
            check(squaredDistance(view.seats().get(1).point(),view.seats().get(2).point())==144,"six-block gap stays six blocks");
            check(view.header().entrances().getFirst().point().equals(snapshot.transform().project(new Point(-12,9))),"entrance shares actual transform");
            check(view.header().aisles().getFirst().boundary().getFirst().equals(snapshot.transform().project(new Point(-6,-10))),"aisle shares actual transform");
            check(view.header().entrances().getFirst().seatKeys().size()==3,"explicit row group resolves only actual registered seats");
            check(view.seats().getFirst().facing().equals(snapshot.transform().project(new Point(-15,-16))),"chair facing rotates with same transform");
            check(view.header().frame().bounds().equals(snapshot.transform().project(layout().bounds())),"one shared auditorium bounds");
        }
        Point relative=new Point(-10,-7),origin=new Point(-15,-15);
        check(new Transform(origin,Orientation.NORTH,1).project(relative).equals(new Point(5,8)),"north top");
        check(new Transform(origin,Orientation.EAST,1).project(relative).equals(new Point(8,-5)),"east top");
        check(new Transform(origin,Orientation.SOUTH,1).project(relative).equals(new Point(-5,-8)),"south top");
        check(new Transform(origin,Orientation.WEST,1).project(relative).equals(new Point(-8,5)),"west top");
        check(new Transform(new Point(30_000_000,30_000_000),Orientation.EAST,1).vector(new Point(1,0)).equals(new Point(0,-1)),"facing vectors do not need an out-of-bounds world point");
        for(Position position:List.of(new Position(-123,-64,-456),new Position(-30_000_000,-2048,30_000_000),new Position(0,2047,0)))
            check(Position.unpack(position.packed()).equals(position),"signed BlockPos roundtrip");
        View view=project(snapshot(source,Orientation.NORTH),Selection.all(),ids());
        check(view.header().frame().bounds().minU()==-10&&view.header().frame().bounds().minV()==-10,"negative local bounds preserved");
        reject(()->new Transform(new Point(0,0),Orientation.NORTH,Double.NaN),"NaN scale");
        reject(()->new Transform(new Point(0,0),Orientation.NORTH,0),"zero scale");
        reject(()->new Point(Double.POSITIVE_INFINITY,0),"infinite coordinate");
        reject(()->new Bounds(-30_000_001,0,0,0,10,10),"world bounds");
        reject(()->new Bounds(0,0,0,8193,10,10),"bounded auditorium extent");
        reject(()->new SeatMapSnapshot("bad_origin",snapshot(source,Orientation.NORTH).capture(),layout(),new Transform(new Point(100,100),Orientation.NORTH,1)),"origin outside auditorium");
    }

    private static void floorsAndOverlap() {
        List<SourceSeat> source=new ArrayList<>(irregular());source.add(seat(6,-10,12,-7,1,1,"upper",GuidePreset.RowScheme.LETTERS));
        SeatMapSnapshot snapshot=snapshot(source,Orientation.EAST);View all=project(snapshot,Selection.all(),ids());
        check(all.header().overlaps().size()==1&&all.header().overlaps().getFirst().code()==OverlapCode.SELECT_FLOOR,"balcony overlap visibly requires floor selection");
        check(all.seats().getFirst().point().equals(all.seats().getLast().point()),"overlapping chairs never moved to fake positions");
        View upper=project(snapshot,new Selection("upper","upper_zone"),ids());
        check(upper.seats().size()==1&&upper.header().overlaps().isEmpty(),"explicit balcony selector resolves levels");
        check(upper.header().frame().equals(all.header().frame()),"floor selection retains origin, scale and bounds");
        check(upper.header().aisles().isEmpty()&&upper.header().entrances().isEmpty(),"lower-floor geometry not shown on balcony");
        reject(()->project(snapshot,new Selection("lower","upper_zone"),ids()),"mismatched floor-zone selector");
        List<SourceSeat> sameFloor=new ArrayList<>(irregular());sameFloor.add(seat(7,-10,3,-7,3,1,"lower",GuidePreset.RowScheme.NUMERIC));
        View overlapping=project(snapshot(sameFloor,Orientation.NORTH),new Selection("lower",""),ids());
        check(overlapping.header().overlaps().getFirst().code()==OverlapCode.OVERLAPPING_SEATS,"same-floor different-height overlap stays visible");
    }

    private static SourceSeat change(SourceSeat seat,Observation observation,SeatRegistry.Status status,String physicalUid,TicketAvailability ticket) {
        SeatRegistry.Seat old=seat.inventory();
        return new SourceSeat(new SeatRegistry.Seat(old.id(),old.hall(),old.zone(),old.row(),old.number(),old.position(),status,old.block(),physicalUid,old.rowScheme()),seat.floorId(),seat.zoneId(),observation,ticket);
    }
    private static void proofAndAvailability() {
        SourceSeat original=irregular().getFirst();Position position=original.observation().position();
        List<Observation> facts=List.of(Observation.missing(position),Observation.other(position),Observation.unloaded(position),
            new Observation(position,ObservedBlock.CHAIR,uuid(9090),Orientation.NORTH,true,Occupancy.OCCUPIED),
            new Observation(position,ObservedBlock.CHAIR,original.inventory().seatUid(),Orientation.WEST,false,Occupancy.EMPTY));
        List<ProofStatus> expected=List.of(ProofStatus.MISSING,ProofStatus.REPLACED,ProofStatus.UNLOADED,ProofStatus.REPLACED,ProofStatus.UNSUPPORTED);
        for(int i=0;i<facts.size();i++){
            SourceSeat changed=change(original,facts.get(i),SeatRegistry.Status.AVAILABLE,original.inventory().seatUid(),TicketAvailability.AVAILABLE);
            SeatMapSnapshot snapshot=snapshot(List.of(changed),Orientation.NORTH);
            check(snapshot.seats().getFirst().proof()==expected.get(i),"explicit loaded-world diagnosis");
            check(snapshot.seats().getFirst().occupancy()==Occupancy.UNKNOWN,"invalid binding does not inherit occupant");
            SeatView view=project(snapshot,Selection.all(),ids()).seats().getFirst();
            check(view.availability().tickets()==TicketAvailability.BLOCKED&&!view.availability().selectable(),"unverified physical target cannot look bookable");
        }
        SourceSeat legacy=change(original,original.observation(),SeatRegistry.Status.AVAILABLE,"",TicketAvailability.AVAILABLE);
        check(snapshot(List.of(legacy),Orientation.NORTH).seats().getFirst().proof()==ProofStatus.LEGACY_UNVERIFIED,"schema-one identity never promoted by observation");
        SourceSeat occupied=change(original,new Observation(position,ObservedBlock.CHAIR,original.inventory().seatUid(),Orientation.NORTH,true,Occupancy.OCCUPIED),SeatRegistry.Status.AVAILABLE,original.inventory().seatUid(),TicketAvailability.AVAILABLE);
        SeatMapSnapshot occupiedSnapshot=snapshot(List.of(occupied),Orientation.NORTH);
        check(occupiedSnapshot.seats().getFirst().occupancy()==Occupancy.OCCUPIED,"separate occupancy fact retained");
        check(project(occupiedSnapshot,Selection.all(),ids()).seats().getFirst().availability().selectable(),"sitting is not inferred to be a ticket claim");
        SourceSeat maintenance=change(original,original.observation(),SeatRegistry.Status.MAINTENANCE,original.inventory().seatUid(),TicketAvailability.AVAILABLE);
        SeatView maintenanceView=project(snapshot(List.of(maintenance),Orientation.NORTH),Selection.all(),ids()).seats().getFirst();
        check(maintenanceView.availability().binding()==ProofStatus.VALID&&maintenanceView.availability().serviceability()==SeatRegistry.Status.MAINTENANCE&&!maintenanceView.availability().selectable(),"serviceability is independent of physical existence");
        SourceSeat reserved=change(original,original.observation(),SeatRegistry.Status.AVAILABLE,original.inventory().seatUid(),TicketAvailability.RESERVED);
        check(!project(snapshot(List.of(reserved),Orientation.NORTH),Selection.all(),ids()).seats().getFirst().availability().selectable(),"reservation independent of an empty chair");
        reject(()->new SourceSeat(original.inventory(),"lower","lower_zone",Observation.unloaded(new Position(-11,2,-7)),TicketAvailability.AVAILABLE),"proof at changed position cannot validate inventory");
        reject(()->new SeatMapSnapshot("no_show",new Capture(SCOPE,1,1,ShowSelection.none(),List.of(original)),layout(),new Transform(new Point(0,0),Orientation.NORTH,1)),"ticket availability needs explicit show scope");
        SourceSeat unknown=change(original,original.observation(),SeatRegistry.Status.AVAILABLE,original.inventory().seatUid(),TicketAvailability.UNKNOWN);
        SeatMapSnapshot noShow=new SeatMapSnapshot("no_show",new Capture(SCOPE,1,1,ShowSelection.none(),List.of(unknown)),layout(),new Transform(new Point(0,0),Orientation.NORTH,1));
        check(project(noShow,Selection.all(),ids()).header().showKey().isEmpty(),"no selected show does not invent availability");
    }

    private static void malformedGeometryAndIds() {
        reject(()->new Polygon(List.of(new Point(0,0),new Point(1,1),new Point(2,2))),"zero area");
        reject(()->new Polygon(List.of(new Point(0,0),new Point(4,4),new Point(0,4),new Point(4,0))),"crossed polygon");
        reject(()->new Polygon(List.of(new Point(0,0),new Point(4,0),new Point(2,0),new Point(4,4),new Point(0,4))),"backtracking boundary");
        reject(()->new Polygon(List.of(new Point(0,0),new Point(1,0),new Point(0,0))),"duplicate vertex");
        Layout base=layout();
        reject(()->new Layout(8,base.bounds(),List.of(base.floors().getFirst(),base.floors().getFirst()),base.zones(),base.entrances(),base.aisles()),"duplicate geometry IDs");
        reject(()->new Layout(8,base.bounds(),base.floors(),base.zones(),base.entrances(),List.of(new Aisle("bad","Bad","absent","",rectangle(0,0,1,1)))),"missing floor reference");
        reject(()->new Layout(8,base.bounds(),base.floors(),base.zones(),base.entrances(),List.of(new Aisle("outside","Bad","lower","",rectangle(0,0,50,50)))),"polygon out of bounds");
        SourceSeat source=irregular().getFirst();
        reject(()->snapshot(List.of(source,source),Orientation.NORTH),"duplicate inventory ID");
        SourceSeat duplicatePosition=seat(20,-10,2,-7,4,1,"lower",GuidePreset.RowScheme.NUMERIC);
        reject(()->snapshot(List.of(source,duplicatePosition),Orientation.NORTH),"duplicate canonical position");
        SourceSeat other=irregular().get(1);SourceSeat duplicateUid=change(other,other.observation(),other.inventory().status(),source.inventory().seatUid(),TicketAvailability.AVAILABLE);
        reject(()->snapshot(List.of(source,duplicateUid),Orientation.NORTH),"duplicate physical UID");
        SourceSeat duplicateObserved=change(other,new Observation(other.observation().position(),ObservedBlock.CHAIR,source.inventory().seatUid(),Orientation.NORTH,true,Occupancy.EMPTY),other.inventory().status(),other.inventory().seatUid(),TicketAvailability.AVAILABLE);
        reject(()->snapshot(List.of(source,duplicateObserved),Orientation.NORTH),"duplicate observed physical UID");
        reject(()->project(snapshot(irregular(),Orientation.NORTH),Selection.all(),(kind,key)->"duplicate"),"duplicate public ID");
        reject(()->project(snapshot(irregular(),Orientation.NORTH),Selection.all(),(kind,key)->key),"raw inventory identity never public");
        Layout dangling=new Layout(8,base.bounds(),base.floors(),base.zones(),List.of(new Entrance("dangling","Dangling",new Position(0,2,0),"lower","",List.of(uuid(888)),List.of())),List.of());
        reject(()->new SeatMapSnapshot("dangling",snapshot(irregular(),Orientation.NORTH).capture(),dangling,new Transform(new Point(0,0),Orientation.NORTH,1)),"dangling seat reference");
    }

    private static Header withRevisions(Header header,Revisions revisions) {
        return new Header(header.snapshotId(),revisions,header.frame(),header.showKey(),header.floorKey(),header.zoneKey(),header.floors(),header.zones(),header.entrances(),header.aisles(),header.overlaps());
    }
    private static void pagination() {
        List<SourceSeat> seats=new ArrayList<>();
        for(int i=0;i<SeatRegistry.MAX_SEATS;i++)seats.add(seat(i+1,i%64-32,2,i/64-32,i/999+1,i%999+1,"lower",GuidePreset.RowScheme.NUMERIC));
        Layout large=new Layout(99,new Bounds(-100,0,-100,100,10,100),List.of(new Floor("lower","Lower floor",0,10)),
            List.of(new Zone("lower_zone","lower","Lower",rectangle(-100,-100,100,100))),List.of(),List.of());
        SeatMapSnapshot snapshot=new SeatMapSnapshot("large_snapshot",new Capture(SCOPE,45,60,new ShowSelection(SHOW,9),seats),large,new Transform(new Point(-50,-50),Orientation.WEST,1));
        View view=project(snapshot,Selection.all(),ids());List<Page> pages=view.pages(256);
        check(pages.size()==16&&pages.stream().allMatch(page->page.seats().size()==256&&page.header().equals(view.header())),"4096 actual seats in bounded stable pages");
        List<Page> reversed=new ArrayList<>(pages);Collections.reverse(reversed);check(assemble(reversed).equals(view),"out-of-order arrival reassembles same order");
        Collections.reverse(seats);SeatMapSnapshot reordered=new SeatMapSnapshot("large_snapshot",new Capture(SCOPE,45,60,new ShowSelection(SHOW,9),seats),large,snapshot.transform());
        check(project(reordered,Selection.all(),ids()).equals(view),"source iteration order cannot move seats between pages");
        reject(()->view.pages(257),"hard page cap");reject(()->view.pages(0),"zero page size");
        reject(()->assemble(pages.subList(0,15)),"partial assembly");
        List<Page> duplicate=new ArrayList<>(pages);duplicate.set(15,pages.getFirst());reject(()->assemble(duplicate),"duplicate page");
        List<Page> duplicateSeats=new ArrayList<>(pages);duplicateSeats.set(15,new Page(view.header(),15,256,4096,pages.getFirst().seats()));reject(()->assemble(duplicateSeats),"duplicate public seat across pages");
        Revisions r=view.header().revisions();
        for(Revisions changed:List.of(new Revisions(r.registry()+1,r.geometry(),r.observations(),r.show()),new Revisions(r.registry(),r.geometry()+1,r.observations(),r.show()),new Revisions(r.registry(),r.geometry(),r.observations()+1,r.show()),new Revisions(r.registry(),r.geometry(),r.observations(),r.show()+1))){
            List<Page> mixed=new ArrayList<>(pages);Page last=pages.getLast();mixed.set(15,new Page(withRevisions(last.header(),changed),last.index(),last.pageSize(),last.totalSeats(),last.seats()));
            reject(()->assemble(mixed),"mixed revision rejected even with same snapshot token");
        }
        Header old=view.header();Header otherSnapshot=new Header("other_snapshot",old.revisions(),old.frame(),old.showKey(),old.floorKey(),old.zoneKey(),old.floors(),old.zones(),old.entrances(),old.aisles(),old.overlaps());
        List<Page> mixed=new ArrayList<>(pages);Page last=pages.getLast();mixed.set(15,new Page(otherSnapshot,15,256,4096,last.seats()));reject(()->assemble(mixed),"mixed snapshot rejected");
        Frame changedFrame=new Frame(old.frame().origin(),Orientation.EAST,old.frame().scale(),old.frame().bounds());
        Header otherFrame=new Header(old.snapshotId(),old.revisions(),changedFrame,old.showKey(),old.floorKey(),old.zoneKey(),old.floors(),old.zones(),old.entrances(),old.aisles(),old.overlaps());
        List<Page> mixedFrame=new ArrayList<>(pages);mixedFrame.set(15,new Page(otherFrame,15,256,4096,last.seats()));reject(()->assemble(mixedFrame),"mixed transform rejected");
        View empty=new View(view.header(),List.of());check(assemble(empty.pages(256)).equals(empty),"empty map has one explicit empty page");
        View small=project(snapshot(irregular(),Orientation.NORTH),Selection.all(),ids());check(small.pages(2).getLast().seats().size()==1,"bounded final partial page");
    }

    private static void labelsAndPublicAllowlist() {
        for(int row:List.of(1,26,27,702,703,999)){
            check(SeatRegistry.rowLabel(row,GuidePreset.RowScheme.NUMERIC).equals(Integer.toString(row)),"legacy numeric identity");
            String label=SeatRegistry.rowLabel(row,GuidePreset.RowScheme.LETTERS);
            check(RowBatch.parseRowLabel(label.toLowerCase(Locale.ROOT),GuidePreset.RowScheme.LETTERS)==row,"strict letters preserve identity");
        }
        check(SeatRegistry.rowLabel(27,GuidePreset.RowScheme.LETTERS).equals("AA"),"AA label has no ROW prefix");
        reject(()->RowBatch.parseRowLabel("Ａ",GuidePreset.RowScheme.LETTERS),"full-width lookalike");
        reject(()->RowBatch.parseRowLabel("ß",GuidePreset.RowScheme.LETTERS),"case expansion");
        SourceSeat source=irregular().getFirst();SeatRegistry.Seat old=source.inventory();
        SeatRegistry.Seat legacy=new SeatRegistry.Seat(old.id(),old.hall(),old.zone(),old.row(),old.number(),old.position(),old.status(),old.block());
        check(legacy.rowLabel().equals("1")&&!legacy.verifiedIdentity(),"legacy constructor keeps numeric display and unverified binding");
        View view=project(snapshot(irregular(),Orientation.NORTH),Selection.all(),ids());
        String json=SeatRegistry.JSON.toJson(view.pages(256).getFirst());
        for(String privateValue:List.of(CONTROLLER,HALL,SHOW,source.inventory().id(),source.inventory().seatUid(),"minecraft:overworld"))check(!json.contains(privateValue),"no internal identity or world scope in public JSON");
        for(String forbidden:List.of("owner","playerUUID","holder","physicalUid","seatUid","inventory","token","privateUrl","worldOrigin"))check(!json.contains("\""+forbidden+"\""),"no private source fields in projection");
        components(SeatView.class,"displayKey floorKey zoneKey zone row rowLabel number point facing availability");
        components(Availability.class,"serviceability binding tickets occupancy");components(Header.class,"snapshotId revisions frame showKey floorKey zoneKey floors zones entrances aisles overlaps");
        components(Frame.class,"origin orientation scale bounds");components(Page.class,"header index pageSize totalSeats seats");
        components(FloorView.class,"displayKey label");components(ZoneView.class,"displayKey floorKey label boundary");
        components(EntranceView.class,"displayKey label point floorKey zoneKey seatKeys");components(AisleView.class,"displayKey label floorKey zoneKey boundary");
        components(Overlap.class,"point seatKeys code");components(Revisions.class,"registry geometry observations show");
        check(view.seats().getFirst().row()==1&&view.seats().getFirst().rowLabel().equals("A"),"public labels reuse strict inventory display policy");
    }
    private static void components(Class<?> type,String names) {
        Set<String> actual=new HashSet<>();for(RecordComponent component:type.getRecordComponents())actual.add(component.getName());
        check(actual.equals(Set.of(names.split(" "))),"strict public DTO allowlist for "+type.getSimpleName());
    }
    public static void run() {
        coordinatesAndRotations();floorsAndOverlap();proofAndAvailability();malformedGeometryAndIds();pagination();labelsAndPublicAllowlist();
    }
    public static void main(String[] args) { run();System.out.println("SeatMapChecks passed"); }
    private SeatMapChecks() {}
}
