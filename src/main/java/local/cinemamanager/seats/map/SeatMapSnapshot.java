package local.cinemamanager.seats.map;

import java.util.*;
import local.cinemamanager.seats.SeatRegistry;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;

/**
 * Immutable owner/server model, NOT a wire DTO and NOT an authority token. A server adapter must
 * capture these facts in the bound controller/hall/dimension, on its thread, without loading chunks.
 * Never accept SourceSeat, Observation, or proposed ticket availability from a client as proof.
 */
public final class SeatMapSnapshot {
    public enum ProofStatus { VALID, MISSING, REPLACED, UNSUPPORTED, UNLOADED, LEGACY_UNVERIFIED }
    public enum ObservedBlock { CHAIR, MISSING, OTHER, UNLOADED }
    public enum Occupancy { EMPTY, OCCUPIED, UNKNOWN }
    /** Read-only proposal from a selected-show ledger, never a reservation or admission decision. */
    public enum TicketAvailability { UNKNOWN, AVAILABLE, RESERVED, ISSUED, CHECKED_IN, BLOCKED }
    public record Scope(String controllerId,String hallId,String dimension) {
        public Scope {
            controllerId=SeatRegistry.uuid(controllerId);hallId=SeatRegistry.uuid(hallId);
            if(dimension==null||dimension.length()>128||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw invalid("dimension");
        }
    }
    public record ShowSelection(String occurrenceId,long revision) {
        public ShowSelection {
            if(occurrenceId==null)throw invalid("show");
            if(!occurrenceId.isEmpty())occurrenceId=SeatRegistry.uuid(occurrenceId);
            SeatMapGeometry.revision(revision);
            if(occurrenceId.isEmpty()&&revision!=0)throw invalid("show");
        }
        public static ShowSelection none() { return new ShowSelection("",0); }
    }
    /** Canonical primary block only. CHAIR requires server-checked block entity UID and support. */
    public record Observation(Position position,ObservedBlock block,String seatUid,Orientation facing,
                              boolean supported,Occupancy occupancy) {
        public Observation {
            Objects.requireNonNull(position);Objects.requireNonNull(block);Objects.requireNonNull(occupancy);
            if(seatUid==null)throw invalid("observation");
            if(block==ObservedBlock.CHAIR){seatUid=SeatRegistry.uuid(seatUid);Objects.requireNonNull(facing);}
            else if(!seatUid.isEmpty()||facing!=null||supported||occupancy!=Occupancy.UNKNOWN)throw invalid("observation");
        }
        public static Observation unloaded(Position position) { return new Observation(position,ObservedBlock.UNLOADED,"",null,false,Occupancy.UNKNOWN); }
        public static Observation missing(Position position) { return new Observation(position,ObservedBlock.MISSING,"",null,false,Occupancy.UNKNOWN); }
        public static Observation other(Position position) { return new Observation(position,ObservedBlock.OTHER,"",null,false,Occupancy.UNKNOWN); }
    }
    /** floorId and zoneId are explicit authored assignments, never inferred by moving a seat. */
    public record SourceSeat(SeatRegistry.Seat inventory,String floorId,String zoneId,Observation observation,
                             TicketAvailability proposedTickets) {
        public SourceSeat {
            inventory=SeatRegistry.validated(inventory);floorId=optionalKey(floorId);zoneId=optionalKey(zoneId);
            Objects.requireNonNull(observation);Objects.requireNonNull(proposedTickets);
            if(!Position.unpack(inventory.position()).equals(observation.position))throw invalid("proof_position");
        }
    }
    /** Capture revision also changes for physical/occupancy observations, even if inventory is unchanged. */
    public record Capture(Scope scope,long registryRevision,long observationRevision,ShowSelection show,List<SourceSeat> seats) {
        public Capture {
            Objects.requireNonNull(scope);Objects.requireNonNull(show);
            SeatMapGeometry.revision(registryRevision);SeatMapGeometry.revision(observationRevision);
            seats=bounded(seats,SeatRegistry.MAX_SEATS,"seat_limit");
        }
    }
    public record Seat(SourceSeat source,ProofStatus proof,Occupancy occupancy) {
        public Seat { Objects.requireNonNull(source);Objects.requireNonNull(proof);Objects.requireNonNull(occupancy); }
        public Position position() { return source.observation.position; }
    }
    private final String snapshotId;
    private final Capture capture;
    private final Layout layout;
    private final Transform transform;
    private final List<Seat> seats;
    private final Map<String,List<String>> entranceSeats;

    public SeatMapSnapshot(String snapshotId,Capture capture,Layout layout,Transform transform) {
        this.snapshotId=key(snapshotId);this.capture=Objects.requireNonNull(capture);
        this.layout=Objects.requireNonNull(layout);this.transform=Objects.requireNonNull(transform);
        if(!layout.bounds().contains(transform.worldOrigin()))throw invalid("origin_bounds");
        Map<String,Floor> floors=new HashMap<>();for(Floor floor:layout.floors())floors.put(floor.id(),floor);
        Map<String,Zone> zones=new HashMap<>();for(Zone zone:layout.zones())zones.put(zone.id(),zone);
        Set<String> inventoryIds=new HashSet<>(),physicalIds=new HashSet<>(),observedUids=new HashSet<>(),numbers=new HashSet<>();Set<Position> positions=new HashSet<>();
        Map<String,SourceSeat> byId=new HashMap<>();List<Seat> checked=new ArrayList<>();
        for(SourceSeat source:capture.seats()){
            SeatRegistry.Seat inventory=source.inventory;
            if(!inventory.hall().equals(capture.scope.hallId))throw invalid("hall_scope");
            unique(inventoryIds,inventory.id());
            if(inventory.verifiedIdentity())unique(physicalIds,inventory.seatUid());
            if(source.observation.block==ObservedBlock.CHAIR)unique(observedUids,source.observation.seatUid);
            if(!positions.add(source.observation.position))throw invalid("duplicate_position");
            if(!numbers.add(inventory.zone().toLowerCase(Locale.ROOT)+"\u0000"+inventory.row()+"\u0000"+inventory.number()))throw invalid("duplicate_number");
            checkScope(source.floorId,source.zoneId,floors,zones);
            if(!layout.bounds().contains(source.observation.position))throw invalid("seat_bounds");
            if(layout.floors().isEmpty()) {
                if(!source.floorId.isEmpty()||!source.zoneId.isEmpty())throw invalid("unconfigured_scope");
            } else {
                Zone zone=zones.get(source.zoneId);Floor floor=floors.get(source.floorId);
                // Numbering groups and authored spatial sections are independent, explicitly assigned concepts.
                if(zone==null||floor==null)throw invalid("zone_reference");
                if(!floor.contains(source.observation.position)||!zone.boundary().contains(source.observation.position.xz()))throw invalid("seat_bounds");
            }
            if(capture.show.occurrenceId.isEmpty()&&source.proposedTickets!=TicketAvailability.UNKNOWN)throw invalid("show_required");
            ProofStatus proof=proof(source);
            checked.add(new Seat(source,proof,proof==ProofStatus.VALID?source.observation.occupancy:Occupancy.UNKNOWN));
            byId.put(inventory.id(),source);
        }
        // Stable source ordering is independent of a server's collection order or page size.
        checked.sort(Comparator.comparing(s->s.source.inventory.id()));this.seats=List.copyOf(checked);
        Map<String,List<String>> associations=new LinkedHashMap<>();
        int resolvedReferences=0;
        for(Entrance entrance:layout.entrances()){
            Set<String> targets=new TreeSet<>();
            for(String id:entrance.seatIds()){
                SourceSeat seat=byId.get(id);if(seat==null)throw invalid("seat_reference");
                targets.add(id);
            }
            for(RowGroup row:entrance.rows()){
                boolean found=false;
                for(Seat seat:checked)if(seat.source.floorId.equals(row.floorId())&&seat.source.zoneId.equals(row.zoneId())&&seat.source.inventory.row()==row.row()){
                    targets.add(seat.source.inventory.id());found=true;
                }
                if(!found)throw invalid("row_reference");
            }
            resolvedReferences+=targets.size();if(resolvedReferences>MAX_REFERENCES)throw invalid("reference_limit");
            associations.put(entrance.id(),List.copyOf(targets));
        }
        this.entranceSeats=Map.copyOf(associations);
    }
    // Explicit authored associations may cross floors, e.g. a lower lobby entrance serving a tiered hall.
    // Entrance floor/zone describes its physical location, never an inferred route or target-seat floor.
    private static ProofStatus proof(SourceSeat source) {
        if(!source.inventory.verifiedIdentity())return ProofStatus.LEGACY_UNVERIFIED;
        Observation fact=source.observation;
        return switch(fact.block){
            case UNLOADED->ProofStatus.UNLOADED;case MISSING->ProofStatus.MISSING;case OTHER->ProofStatus.REPLACED;
            case CHAIR->!source.inventory.seatUid().equals(fact.seatUid)?ProofStatus.REPLACED
                :!fact.supported?ProofStatus.UNSUPPORTED:ProofStatus.VALID;
        };
    }
    public String snapshotId() { return snapshotId; }
    public Capture capture() { return capture; }
    public Layout layout() { return layout; }
    public Transform transform() { return transform; }
    public List<Seat> seats() { return seats; }
    public Map<String,List<String>> entranceSeats() { return entranceSeats; }
}
