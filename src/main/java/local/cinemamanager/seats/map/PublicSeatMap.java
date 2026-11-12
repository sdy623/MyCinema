package local.cinemamanager.seats.map;

import java.util.*;
import local.cinemamanager.seats.SeatRegistry;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;
import static local.cinemamanager.seats.map.SeatMapSnapshot.*;

/**
 * Explicit public allowlist. Only these view records may enter a future audience packet.
 * IDs identify display objects, never permission, physical authentication, or ticket ownership.
 * Projection does not authorize refresh: the adapter must enforce nearby/loaded/bound panel checks.
 */
public final class PublicSeatMap {
    public static final int MAX_PAGE_SEATS=256;
    public enum ObjectKind { SEAT, FLOOR, ZONE, ENTRANCE, AISLE, SHOW }
    /** Inject a scoped, stable, opaque mapping. Never return inventory/physical/controller/holder IDs or secrets. */
    @FunctionalInterface public interface DisplayIds { String displayKey(ObjectKind kind,String internalKey); }
    public record Selection(String floorId,String zoneId) {
        public Selection { floorId=optionalKey(floorId);zoneId=optionalKey(zoneId); }
        public static Selection all() { return new Selection("",""); }
    }
    public record Revisions(long registry,long geometry,long observations,long show) {
        public Revisions { revision(registry);revision(geometry);revision(observations);revision(show); }
    }
    /** origin is map-local (0,0); absolute world origin/dimension are deliberately not public. */
    public record Frame(Point origin,Orientation orientation,double scale,MapBounds bounds) {
        public Frame {
            Objects.requireNonNull(origin);Objects.requireNonNull(orientation);Objects.requireNonNull(bounds);
            if(origin.x()!=0||origin.z()!=0||!bounds.contains(origin)||!Double.isFinite(scale)||scale<1.0/64||scale>64)throw invalid("frame");
            if(bounds.maxU()-bounds.minU()>MAX_SPAN*scale||bounds.maxV()-bounds.minV()>MAX_SPAN*scale)throw invalid("frame");
        }
    }
    public record Availability(SeatRegistry.Status serviceability,ProofStatus binding,TicketAvailability tickets,Occupancy occupancy) {
        public Availability { Objects.requireNonNull(serviceability);Objects.requireNonNull(binding);Objects.requireNonNull(tickets);Objects.requireNonNull(occupancy); }
        public Availability(SeatRegistry.Status serviceability,ProofStatus binding,TicketAvailability tickets) { this(serviceability,binding,tickets,Occupancy.UNKNOWN); }
        /** Display hint only. A later booking operation must recheck the ledger and loaded-world facts. */
        public boolean selectable() { return serviceability==SeatRegistry.Status.AVAILABLE&&binding==ProofStatus.VALID&&tickets==TicketAvailability.AVAILABLE; }
    }
    public record SeatView(String displayKey,String floorKey,String zoneKey,String zone,int row,String rowLabel,int number,
                           Point point,Point facing,Availability availability) {
        public SeatView {
            displayKey=key(displayKey);floorKey=optionalKey(floorKey);zoneKey=optionalKey(zoneKey);zone=SeatRegistry.label(zone,32,false);
            if(row<1||row>SeatRegistry.MAX_NUMBER||number<1||number>SeatRegistry.MAX_NUMBER)throw invalid("seat_label");
            if(!Integer.toString(row).equals(rowLabel)&&!SeatRegistry.rowLabel(row,local.cinemamanager.seats.GuidePreset.RowScheme.LETTERS).equals(rowLabel))throw invalid("seat_label");
            Objects.requireNonNull(point);Objects.requireNonNull(availability);
        }
    }
    public record FloorView(String displayKey,String label) {
        public FloorView { displayKey=key(displayKey);label=caption(label); }
    }
    public record ZoneView(String displayKey,String floorKey,String label,List<Point> boundary) {
        public ZoneView { displayKey=key(displayKey);floorKey=key(floorKey);label=caption(label);boundary=bounded(boundary,MAX_VERTICES,"vertices");new Polygon(boundary); }
    }
    public record EntranceView(String displayKey,String label,Point point,String floorKey,String zoneKey,List<String> seatKeys) {
        public EntranceView {
            displayKey=key(displayKey);label=caption(label);Objects.requireNonNull(point);floorKey=optionalKey(floorKey);zoneKey=optionalKey(zoneKey);
            seatKeys=bounded(seatKeys,MAX_REFERENCES,"references");Set<String> unique=new HashSet<>();for(String id:seatKeys)SeatMapGeometry.unique(unique,key(id));
        }
    }
    public record AisleView(String displayKey,String label,String floorKey,String zoneKey,List<Point> boundary) {
        public AisleView { displayKey=key(displayKey);label=caption(label);floorKey=key(floorKey);zoneKey=optionalKey(zoneKey);boundary=bounded(boundary,MAX_VERTICES,"vertices");new Polygon(boundary); }
    }
    public enum OverlapCode { SELECT_FLOOR, OVERLAPPING_SEATS }
    public record Overlap(Point point,List<String> seatKeys,OverlapCode code) {
        public Overlap {
            Objects.requireNonNull(point);Objects.requireNonNull(code);seatKeys=bounded(seatKeys,SeatRegistry.MAX_SEATS,"overlap");
            if(seatKeys.size()<2)throw invalid("overlap");Set<String> unique=new HashSet<>();for(String id:seatKeys)SeatMapGeometry.unique(unique,key(id));
        }
    }
    /** The whole header is repeated/compared on every page; no per-page coordinate normalization. */
    public record Header(String snapshotId,Revisions revisions,Frame frame,String showKey,String floorKey,String zoneKey,
                         List<FloorView> floors,List<ZoneView> zones,List<EntranceView> entrances,List<AisleView> aisles,List<Overlap> overlaps) {
        public Header {
            snapshotId=key(snapshotId);Objects.requireNonNull(revisions);Objects.requireNonNull(frame);
            showKey=optionalKey(showKey);floorKey=optionalKey(floorKey);zoneKey=optionalKey(zoneKey);
            floors=bounded(floors,MAX_FLOORS,"floors");zones=bounded(zones,MAX_ZONES,"zones");
            entrances=bounded(entrances,MAX_ENTRANCES,"entrances");aisles=bounded(aisles,MAX_AISLES,"aisles");overlaps=bounded(overlaps,SeatRegistry.MAX_SEATS/2,"overlaps");
            Set<String> ids=new HashSet<>(),floorIds=new HashSet<>();Map<String,String> zoneFloors=new HashMap<>();
            for(FloorView f:floors){unique(ids,f.displayKey);floorIds.add(f.displayKey);}
            if(!floorKey.isEmpty()&&!floorIds.contains(floorKey))throw invalid("floor_reference");
            int vertices=0,references=0,overlapSeats=0;
            for(ZoneView z:zones){unique(ids,z.displayKey);if(!floorIds.contains(z.floorKey))throw invalid("floor_reference");zoneFloors.put(z.displayKey,z.floorKey);vertices+=z.boundary.size();for(Point p:z.boundary)inside(frame,p);}
            if(!zoneKey.isEmpty()&&(!zoneFloors.containsKey(zoneKey)||!zoneFloors.get(zoneKey).equals(floorKey)))throw invalid("zone_reference");
            for(EntranceView e:entrances){unique(ids,e.displayKey);publicScope(e.floorKey,e.zoneKey,floorIds,zoneFloors);inside(frame,e.point);references+=e.seatKeys.size();}
            for(AisleView a:aisles){unique(ids,a.displayKey);publicScope(a.floorKey,a.zoneKey,floorIds,zoneFloors);vertices+=a.boundary.size();for(Point p:a.boundary)inside(frame,p);}
            for(Overlap o:overlaps){inside(frame,o.point);overlapSeats+=o.seatKeys.size();}
            if(!showKey.isEmpty())unique(ids,showKey);
            if(vertices>MAX_TOTAL_VERTICES||references>MAX_REFERENCES||overlapSeats>SeatRegistry.MAX_SEATS)throw invalid("geometry_limit");
        }
    }
    public record Page(Header header,int index,int pageSize,int totalSeats,List<SeatView> seats) {
        public Page {
            Objects.requireNonNull(header);
            if(pageSize<1||pageSize>MAX_PAGE_SEATS||totalSeats<0||totalSeats>SeatRegistry.MAX_SEATS)throw invalid("page_limit");
            int count=Math.max(1,(totalSeats+pageSize-1)/pageSize);
            if(index<0||index>=count)throw invalid("page_index");
            seats=bounded(seats,MAX_PAGE_SEATS,"page_limit");
            if(seats.size()!=Math.min(pageSize,totalSeats-index*pageSize))throw invalid("page_length");
            Set<String> ids=new HashSet<>();for(SeatView seat:seats){unique(ids,seat.displayKey);inside(header.frame,seat.point);}
        }
        public int pageCount() { return Math.max(1,(totalSeats+pageSize-1)/pageSize); }
    }
    public record View(Header header,List<SeatView> seats) {
        public View {
            Objects.requireNonNull(header);seats=bounded(seats,SeatRegistry.MAX_SEATS,"seat_limit");
            validateReferences(header,seats);
        }
        public List<Page> pages(int pageSize) {
            if(pageSize<1||pageSize>MAX_PAGE_SEATS)throw invalid("page_limit");
            List<Page> pages=new ArrayList<>();int count=Math.max(1,(seats.size()+pageSize-1)/pageSize);
            for(int i=0;i<count;i++)pages.add(new Page(header,i,pageSize,seats.size(),seats.subList(i*pageSize,Math.min(seats.size(),(i+1)*pageSize))));
            return List.copyOf(pages);
        }
    }

    public static View project(SeatMapSnapshot snapshot,Selection selection,DisplayIds provider) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(selection);Objects.requireNonNull(provider);
        Layout layout=snapshot.layout();Transform transform=snapshot.transform();Capture capture=snapshot.capture();
        Map<String,Floor> floorById=new HashMap<>();for(Floor f:layout.floors())floorById.put(f.id(),f);
        Map<String,Zone> zoneById=new HashMap<>();for(Zone z:layout.zones())zoneById.put(z.id(),z);
        checkScope(selection.floorId,selection.zoneId,floorById,zoneById);
        Set<String> privateIds=new HashSet<>(List.of(capture.scope().controllerId(),capture.scope().hallId()));
        if(!capture.show().occurrenceId().isEmpty())privateIds.add(capture.show().occurrenceId());
        for(SeatMapSnapshot.Seat seat:snapshot.seats()){
            privateIds.add(seat.source().inventory().id());privateIds.add(seat.source().inventory().seatUid());privateIds.add(seat.source().observation().seatUid());
        }
        if(privateIds.contains(snapshot.snapshotId()))throw invalid("public_identity");
        Ids ids=new Ids(provider,privateIds,snapshot.snapshotId());
        List<FloorView> floors=new ArrayList<>();for(Floor floor:layout.floors())floors.add(new FloorView(ids.get(ObjectKind.FLOOR,floor.id()),floor.label()));
        List<ZoneView> zones=new ArrayList<>();for(Zone zone:layout.zones())if(selection.floorId.isEmpty()||selection.floorId.equals(zone.floorId()))
            zones.add(new ZoneView(ids.get(ObjectKind.ZONE,zone.id()),ids.get(ObjectKind.FLOOR,zone.floorId()),zone.label(),project(transform,zone.boundary())));
        List<SeatView> seats=new ArrayList<>();Map<String,String> displayed=new HashMap<>();
        Map<Point,List<SeatView>> overlaps=new LinkedHashMap<>();
        for(SeatMapSnapshot.Seat seat:snapshot.seats()){
            SourceSeat source=seat.source();if(!matches(selection,source.floorId(),source.zoneId()))continue;
            SeatRegistry.Seat inventory=source.inventory();String displayKey=ids.get(ObjectKind.SEAT,inventory.id());displayed.put(inventory.id(),displayKey);
            TicketAvailability tickets=source.proposedTickets();
            if(tickets!=TicketAvailability.UNKNOWN&&(seat.proof()!=ProofStatus.VALID||inventory.status()!=SeatRegistry.Status.AVAILABLE))tickets=TicketAvailability.BLOCKED;
            Point facing=seat.proof()==ProofStatus.VALID?facing(transform,source.observation().facing()):null;
            SeatView view=new SeatView(displayKey,ids.optional(ObjectKind.FLOOR,source.floorId()),ids.optional(ObjectKind.ZONE,source.zoneId()),inventory.zone(),
                inventory.row(),inventory.rowLabel(),inventory.number(),transform.project(seat.position().xz()),facing,new Availability(inventory.status(),seat.proof(),tickets,seat.occupancy()));
            seats.add(view);overlaps.computeIfAbsent(view.point,k->new ArrayList<>()).add(view);
        }
        List<EntranceView> entrances=new ArrayList<>();
        for(Entrance entrance:layout.entrances())if(matches(selection,entrance.floorId(),entrance.zoneId())){
            List<String> targets=snapshot.entranceSeats().get(entrance.id()).stream().filter(displayed::containsKey).map(displayed::get).toList();
            entrances.add(new EntranceView(ids.get(ObjectKind.ENTRANCE,entrance.id()),entrance.label(),transform.project(entrance.position().xz()),
                ids.optional(ObjectKind.FLOOR,entrance.floorId()),ids.optional(ObjectKind.ZONE,entrance.zoneId()),targets));
        }
        List<AisleView> aisles=new ArrayList<>();for(Aisle aisle:layout.aisles())if(matches(selection,aisle.floorId(),aisle.zoneId()))
            aisles.add(new AisleView(ids.get(ObjectKind.AISLE,aisle.id()),aisle.label(),ids.get(ObjectKind.FLOOR,aisle.floorId()),ids.optional(ObjectKind.ZONE,aisle.zoneId()),project(transform,aisle.boundary())));
        List<Overlap> warnings=new ArrayList<>();
        for(var group:overlaps.entrySet())if(group.getValue().size()>1){
            boolean multipleFloors=group.getValue().stream().map(SeatView::floorKey).distinct().count()>1;
            warnings.add(new Overlap(group.getKey(),group.getValue().stream().map(SeatView::displayKey).toList(),multipleFloors&&selection.floorId.isEmpty()?OverlapCode.SELECT_FLOOR:OverlapCode.OVERLAPPING_SEATS));
        }
        Header header=new Header(snapshot.snapshotId(),new Revisions(capture.registryRevision(),layout.revision(),capture.observationRevision(),capture.show().revision()),
            new Frame(new Point(0,0),transform.orientation(),transform.scale(),transform.project(layout.bounds())),ids.optional(ObjectKind.SHOW,capture.show().occurrenceId()),
            ids.optional(ObjectKind.FLOOR,selection.floorId),ids.optional(ObjectKind.ZONE,selection.zoneId),floors,zones,entrances,aisles,warnings);
        return new View(header,seats);
    }
    /** All-or-nothing assembly. Partial, duplicate, stale, or mixed revision/selection/transform pages fail. */
    public static View assemble(List<Page> input) {
        List<Page> pages=bounded(input,SeatRegistry.MAX_SEATS,"pages");if(pages.isEmpty())throw invalid("incomplete");
        Page first=pages.getFirst();if(pages.size()!=first.pageCount())throw invalid("incomplete");
        Page[] ordered=new Page[first.pageCount()];
        for(Page page:pages){
            if(!page.header.equals(first.header)||page.pageSize!=first.pageSize||page.totalSeats!=first.totalSeats)throw invalid("mixed_snapshot");
            if(ordered[page.index]!=null)throw invalid("duplicate_page");ordered[page.index]=page;
        }
        List<SeatView> seats=new ArrayList<>();for(Page page:ordered){if(page==null)throw invalid("incomplete");seats.addAll(page.seats);}
        return new View(first.header,seats);
    }
    private static void validateReferences(Header header,List<SeatView> seats) {
        Set<String> ids=new HashSet<>(),floors=new HashSet<>();Map<String,String> zones=new HashMap<>();Map<String,SeatView> byId=new HashMap<>();
        for(FloorView f:header.floors){unique(ids,f.displayKey);floors.add(f.displayKey);}
        for(ZoneView z:header.zones){unique(ids,z.displayKey);zones.put(z.displayKey,z.floorKey);}
        for(EntranceView e:header.entrances)unique(ids,e.displayKey);for(AisleView a:header.aisles)unique(ids,a.displayKey);
        unique(ids,header.snapshotId);if(!header.showKey.isEmpty())unique(ids,header.showKey);
        for(SeatView seat:seats){
            unique(ids,seat.displayKey);publicScope(seat.floorKey,seat.zoneKey,floors,zones);inside(header.frame,seat.point);
            if(!header.floorKey.isEmpty()&&!header.floorKey.equals(seat.floorKey)||!header.zoneKey.isEmpty()&&!header.zoneKey.equals(seat.zoneKey))throw invalid("selection_scope");
            if(header.showKey.isEmpty()&&seat.availability.tickets!=TicketAvailability.UNKNOWN)throw invalid("show_required");
            byId.put(seat.displayKey,seat);
        }
        for(EntranceView entrance:header.entrances)for(String id:entrance.seatKeys)if(!byId.containsKey(id))throw invalid("seat_reference");
        Set<String> diagnosed=new HashSet<>();
        for(Overlap overlap:header.overlaps)for(String id:overlap.seatKeys){
            SeatView seat=byId.get(id);if(seat==null||!seat.point.equals(overlap.point)||!diagnosed.add(id))throw invalid("overlap_reference");
        }
        Map<Point,Integer> at=new HashMap<>();for(SeatView seat:seats)at.merge(seat.point,1,Integer::sum);
        for(SeatView seat:seats)if(at.get(seat.point)>1&&!diagnosed.contains(seat.displayKey))throw invalid("missing_overlap");
    }
    private static void publicScope(String floor,String zone,Set<String> floors,Map<String,String> zones) {
        if(!floor.isEmpty()&&!floors.contains(floor)||!zone.isEmpty()&&(!zones.containsKey(zone)||!zones.get(zone).equals(floor)))throw invalid("public_scope");
    }
    private static void inside(Frame frame,Point point) { if(!frame.bounds.contains(point))throw invalid("public_bounds"); }
    private static boolean matches(Selection selection,String floor,String zone) {
        return (selection.floorId.isEmpty()||floor.isEmpty()||selection.floorId.equals(floor))&&(selection.zoneId.isEmpty()||zone.isEmpty()||selection.zoneId.equals(zone));
    }
    private static List<Point> project(Transform transform,Polygon polygon) { return polygon.vertices().stream().map(transform::project).toList(); }
    private static Point facing(Transform transform,Orientation facing) {
        return transform.vector(switch(facing){case NORTH->new Point(0,-1);case EAST->new Point(1,0);case SOUTH->new Point(0,1);case WEST->new Point(-1,0);});
    }
    private static final class Ids {
        private final DisplayIds provider;private final Set<String> privateIds;private final Set<String> used=new HashSet<>();private final Map<String,String> cached=new HashMap<>();
        private Ids(DisplayIds provider,Set<String> privateIds,String snapshotId) { this.provider=provider;this.privateIds=privateIds;used.add(snapshotId); }
        private String optional(ObjectKind kind,String internal) { return internal.isEmpty()?"":get(kind,internal); }
        private String get(ObjectKind kind,String internal) {
            return cached.computeIfAbsent(kind+":"+internal,k->{String id=key(provider.displayKey(kind,internal));if(privateIds.contains(id))throw invalid("public_identity");unique(used,id);return id;});
        }
    }
    private PublicSeatMap() {}
}
