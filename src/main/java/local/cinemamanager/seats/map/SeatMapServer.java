package local.cinemamanager.seats.map;

import java.nio.charset.StandardCharsets;
import java.util.*;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.seats.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;
import static local.cinemamanager.seats.map.SeatMapSnapshot.*;

/** Only enumerates bounded registry entries. No world scan, chunk request, path finding or row grid. */
public final class SeatMapServer {
    private static long observationRevision;
    private static final String DISPLAY_SALT=UUID.randomUUID().toString();
    public static String displayKey(UUID controller,String hall,PublicSeatMap.ObjectKind kind,String internal){
        return UUID.nameUUIDFromBytes((DISPLAY_SALT+"|"+controller+"|"+hall+"|"+kind+"|"+internal).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static SeatMapSnapshot capture(ServerPlayer actor,ManagerTile manager,String hall,Optional<SeatMapLayoutDocument> authored){
        if(manager.getLevel()!=actor.level()||manager.state.halls.stream().noneMatch(h->h.id.equals(hall)))throw invalid("hall_missing");
        ServerLevel level=(ServerLevel)actor.level();SeatStorage store=SeatStorage.get(level,manager.identity);store.requireEditable();
        List<SeatRegistry.Seat> inventory=store.registry().seats().stream().filter(s->s.hall().equals(hall)).toList();
        Layout layout;Transform transform;Map<Long,SeatMapLayoutDocument.Assignment> assignments=new HashMap<>();
        if(authored.isPresent()){
            SeatMapLayoutDocument doc=authored.get();layout=doc.layout();transform=doc.transform();
            for(var assignment:doc.assignments())assignments.put(assignment.position(),assignment);
            if(!doc.scope().equals(new Scope(manager.identity.toString(),hall,level.dimension().location().toString())))throw invalid("layout_scope");
            for(var seat:inventory){
                var assignment=assignments.get(seat.position());
                if(assignment==null)throw invalid("assignment_missing");
                if(!seat.verifiedIdentity()||!assignment.seatUid().equals(seat.seatUid()))throw invalid("assignment_replaced");
            }
        }else{
            double minX=manager.getBlockPos().getX(),maxX=minX,minZ=manager.getBlockPos().getZ(),maxZ=minZ;
            if(!inventory.isEmpty()){
                Position first=Position.unpack(inventory.getFirst().position());minX=maxX=first.x();minZ=maxZ=first.z();
                for(var seat:inventory){Position p=Position.unpack(seat.position());minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());}
            }
            // Bounding box is a viewport only; it is not an authored floor, zone or aisle.
            Bounds bounds=new Bounds(Math.max(-WORLD_LIMIT,minX-1),-2048,Math.max(-WORLD_LIMIT,minZ-1),Math.min(WORLD_LIMIT,maxX+1),2047,Math.min(WORLD_LIMIT,maxZ+1));
            layout=new Layout(0,bounds,List.of(),List.of(),List.of(),List.of());
            transform=new Transform(new Point((minX+maxX)/2,(minZ+maxZ)/2),Orientation.NORTH,1);
        }
        List<SourceSeat> sources=new ArrayList<>();
        for(SeatRegistry.Seat seat:inventory){
            var assignment=assignments.get(seat.position());
            sources.add(new SourceSeat(seat,assignment==null?"":assignment.floorId(),assignment==null?"":assignment.zoneId(),observe(level,manager.identity,seat),TicketAvailability.UNKNOWN));
        }
        if(authored.isPresent()){
            // Authored targets may precede registration. Resolve only explicitly assigned current entries.
            Set<String> present=new HashSet<>();for(SourceSeat seat:sources)present.add(seat.inventory().id());
            List<Entrance> entrances=new ArrayList<>();for(Entrance entrance:layout.entrances()){
                List<String> ids=entrance.seatIds().stream().filter(present::contains).toList();
                List<RowGroup> rows=entrance.rows().stream().filter(row->sources.stream().anyMatch(seat->seat.floorId().equals(row.floorId())&&seat.zoneId().equals(row.zoneId())&&seat.inventory().row()==row.row())).toList();
                entrances.add(new Entrance(entrance.id(),entrance.label(),entrance.position(),entrance.floorId(),entrance.zoneId(),ids,rows));
            }
            layout=new Layout(layout.revision(),layout.bounds(),layout.floors(),layout.zones(),entrances,layout.aisles());
        }
        Capture capture=new Capture(new Scope(manager.identity.toString(),hall,level.dimension().location().toString()),store.registry().revision(),++observationRevision,ShowSelection.none(),sources);
        return new SeatMapSnapshot(UUID.randomUUID().toString(),capture,layout,transform);
    }
    static Observation observe(ServerLevel level,UUID controller,SeatRegistry.Seat seat){
        Position position=Position.unpack(seat.position());BlockPos pos=BlockPos.of(seat.position());
        if(!level.hasChunkAt(pos))return Observation.unloaded(position);
        var state=level.getBlockState(pos);
        if(state.isAir())return Observation.missing(position);
        if(!(state.getBlock() instanceof CinemaSeatBlock)||!(level.getBlockEntity(pos) instanceof CinemaSeatTile tile)||tile.isRemoved())return Observation.other(position);
        if(!level.hasChunkAt(pos.below()))return Observation.unloaded(position);
        // A chair claimed by another controller is not proof for this hall's registry.
        if(tile.boundController().filter(controller::equals).isEmpty())return Observation.other(position);
        Orientation facing=Orientation.valueOf(state.getValue(CinemaSeatBlock.FACING).name());
        return new Observation(position,ObservedBlock.CHAIR,tile.seatUid().toString(),facing,state.canSurvive(level,pos),tile.occupied()?Occupancy.OCCUPIED:Occupancy.EMPTY);
    }
    public static PublicSeatMap.View project(SeatMapSnapshot snapshot,UUID controller,String hall,String floorKey,String zoneKey){
        String floor="",zone="";
        if(!floorKey.isEmpty())floor=snapshot.layout().floors().stream().filter(f->displayKey(controller,hall,PublicSeatMap.ObjectKind.FLOOR,f.id()).equals(floorKey)).findFirst().orElseThrow(()->invalid("selection")).id();
        if(!zoneKey.isEmpty())zone=snapshot.layout().zones().stream().filter(z->displayKey(controller,hall,PublicSeatMap.ObjectKind.ZONE,z.id()).equals(zoneKey)).findFirst().orElseThrow(()->invalid("selection")).id();
        return PublicSeatMap.project(snapshot,new PublicSeatMap.Selection(floor,zone),(kind,internal)->displayKey(controller,hall,kind,internal));
    }
    private SeatMapServer(){}
}
