package local.cinemamanager.seats.map;

import java.util.*;
import local.cinemamanager.seats.SeatRegistry;

/** Authored auditorium geometry only. No path finding, world access, or chair relocation. */
public final class SeatMapGeometry {
    public static final int MAX_FLOORS=32, MAX_ZONES=128, MAX_ENTRANCES=64, MAX_AISLES=128;
    public static final int MAX_VERTICES=64, MAX_TOTAL_VERTICES=8192, MAX_REFERENCES=4096;
    public static final double WORLD_LIMIT=30_000_000, MAX_SPAN=8192;

    /** The named world direction appears at the top of the map; screen v increases downward. */
    public enum Orientation { NORTH, EAST, SOUTH, WEST }
    public record Point(double x,double z) {
        public Point { coordinate(x);coordinate(z);if(x==0)x=0;if(z==0)z=0; }
    }
    public record Position(int x,int y,int z) {
        public Position { coordinate(x);coordinate(z);if(y < -2048 || y > 2047)throw invalid("position"); }
        /** Minecraft's canonical signed BlockPos packing, without importing Minecraft. */
        public static Position unpack(long packed) {
            return new Position((int)(packed>>38),(int)(packed<<52>>52),(int)(packed<<26>>38));
        }
        public long packed() { return ((long)x & 0x3ffffffL)<<38 | ((long)z & 0x3ffffffL)<<12 | ((long)y & 0xfffL); }
        public Point xz() { return new Point(x,z); }
    }
    public record Bounds(double minX,int minY,double minZ,double maxX,int maxY,double maxZ) {
        public Bounds {
            coordinate(minX);coordinate(maxX);coordinate(minZ);coordinate(maxZ);
            if(minX>=maxX||minZ>=maxZ||maxX-minX>MAX_SPAN||maxZ-minZ>MAX_SPAN
                    ||minY < -2048||maxY > 2047||minY>maxY)throw invalid("bounds");
        }
        public boolean contains(Point p) { return p.x>=minX&&p.x<=maxX&&p.z>=minZ&&p.z<=maxZ; }
        public boolean contains(Position p) { return contains(p.xz())&&p.y>=minY&&p.y<=maxY; }
    }
    public record MapBounds(double minU,double minV,double maxU,double maxV) {
        public MapBounds {
            finite(minU);finite(minV);finite(maxU);finite(maxV);
            if(minU>=maxU||minV>=maxV)throw invalid("map_bounds");
        }
        public boolean contains(Point p) { return p.x>=minU&&p.x<=maxU&&p.z>=minV&&p.z<=maxV; }
    }
    public record Transform(Point worldOrigin,Orientation orientation,double scale) {
        public Transform {
            Objects.requireNonNull(worldOrigin);Objects.requireNonNull(orientation);
            coordinate(worldOrigin.x);coordinate(worldOrigin.z);
            if(!Double.isFinite(scale)||scale < 1.0/64 || scale > 64)throw invalid("scale");
        }
        public Point project(Point world) {
            double x=world.x-worldOrigin.x,z=world.z-worldOrigin.z;
            return vector(new Point(x,z));
        }
        /** Transform a direction/distance without inventing an absolute point outside world bounds. */
        public Point vector(Point vector) {
            double x=vector.x,z=vector.z;
            Point turned=switch(orientation){
                case NORTH->new Point(x,z);case EAST->new Point(z,-x);
                case SOUTH->new Point(-x,-z);case WEST->new Point(-z,x);
            };
            return new Point(turned.x*scale,turned.z*scale);
        }
        public MapBounds project(Bounds bounds) {
            List<Point> corners=List.of(project(new Point(bounds.minX,bounds.minZ)),project(new Point(bounds.minX,bounds.maxZ)),
                project(new Point(bounds.maxX,bounds.minZ)),project(new Point(bounds.maxX,bounds.maxZ)));
            return new MapBounds(corners.stream().mapToDouble(Point::x).min().orElseThrow(),corners.stream().mapToDouble(Point::z).min().orElseThrow(),
                corners.stream().mapToDouble(Point::x).max().orElseThrow(),corners.stream().mapToDouble(Point::z).max().orElseThrow());
        }
    }
    public record Floor(String id,String label,int minY,int maxY) {
        public Floor {
            id=key(id);label=caption(label);
            if(minY < -2048||maxY > 2047||minY>maxY)throw invalid("floor");
        }
        public boolean contains(Position p) { return p.y>=minY&&p.y<=maxY; }
    }
    public record Polygon(List<Point> vertices) {
        public Polygon {
            vertices=bounded(vertices,MAX_VERTICES,"vertices");
            if(vertices.size()<3||new HashSet<>(vertices).size()!=vertices.size())throw invalid("polygon");
            double twiceArea=0;
            Point origin=vertices.getFirst();
            for(int i=0;i<vertices.size();i++){
                Point a=vertices.get(i),b=vertices.get((i+1)%vertices.size());
                Point c=vertices.get((i+2)%vertices.size());
                if(cross(a,b,c)==0&&(b.x-a.x)*(c.x-b.x)+(b.z-a.z)*(c.z-b.z)<0)throw invalid("polygon_backtrack");
                twiceArea+=(a.x-origin.x)*(b.z-origin.z)-(b.x-origin.x)*(a.z-origin.z);
                for(int j=i+1;j<vertices.size();j++){
                    if(j==i+1||i==0&&j==vertices.size()-1)continue;
                    if(intersects(a,b,vertices.get(j),vertices.get((j+1)%vertices.size())))throw invalid("self_intersection");
                }
            }
            if(twiceArea==0)throw invalid("polygon");
        }
        public boolean contains(Point point) {
            boolean inside=false;
            for(int i=0,j=vertices.size()-1;i<vertices.size();j=i++){
                Point a=vertices.get(j),b=vertices.get(i);
                if(onSegment(a,b,point))return true;
                if((a.z>point.z)!=(b.z>point.z)&&point.x<(b.x-a.x)*(point.z-a.z)/(b.z-a.z)+a.x)inside=!inside;
            }
            return inside;
        }
    }
    public record Zone(String id,String floorId,String label,Polygon boundary) {
        public Zone { id=key(id);floorId=key(floorId);label=caption(label);Objects.requireNonNull(boundary); }
    }
    public record RowGroup(String floorId,String zoneId,int row) {
        public RowGroup { floorId=key(floorId);zoneId=key(zoneId);if(row<1||row>SeatRegistry.MAX_NUMBER)throw invalid("row"); }
    }
    public record Entrance(String id,String label,Position position,String floorId,String zoneId,
                           List<String> seatIds,List<RowGroup> rows) {
        public Entrance {
            id=key(id);label=caption(label);Objects.requireNonNull(position);
            floorId=optionalKey(floorId);zoneId=optionalKey(zoneId);
            seatIds=bounded(seatIds,MAX_REFERENCES,"entrance_seats");rows=bounded(rows,MAX_REFERENCES,"entrance_rows");
            for(String seatId:seatIds)SeatRegistry.uuid(seatId);
            if(new HashSet<>(seatIds).size()!=seatIds.size()||new HashSet<>(rows).size()!=rows.size())throw invalid("duplicate_reference");
        }
    }
    public record Aisle(String id,String label,String floorId,String zoneId,Polygon boundary) {
        public Aisle { id=key(id);label=caption(label);floorId=key(floorId);zoneId=optionalKey(zoneId);Objects.requireNonNull(boundary); }
    }
    public record Layout(long revision,Bounds bounds,List<Floor> floors,List<Zone> zones,List<Entrance> entrances,List<Aisle> aisles) {
        public Layout {
            SeatMapGeometry.revision(revision);Objects.requireNonNull(bounds);
            floors=bounded(floors,MAX_FLOORS,"floors");zones=bounded(zones,MAX_ZONES,"zones");
            entrances=bounded(entrances,MAX_ENTRANCES,"entrances");aisles=bounded(aisles,MAX_AISLES,"aisles");
            if(floors.isEmpty()&&(!zones.isEmpty()||!entrances.isEmpty()||!aisles.isEmpty()))throw invalid("unconfigured_geometry");
            Set<String> ids=new HashSet<>();Map<String,Floor> floorById=new HashMap<>();Map<String,Zone> zoneById=new HashMap<>();
            for(Floor f:floors){unique(ids,f.id);floorById.put(f.id,f);if(f.minY<bounds.minY||f.maxY>bounds.maxY)throw invalid("floor_bounds");}
            int vertices=0,references=0;
            for(Zone z:zones){
                unique(ids,z.id);if(!floorById.containsKey(z.floorId))throw invalid("floor_reference");zoneById.put(z.id,z);
                checkPolygon(bounds,z.boundary);vertices+=z.boundary.vertices.size();
            }
            for(Aisle a:aisles){
                unique(ids,a.id);checkScope(a.floorId,a.zoneId,floorById,zoneById);checkPolygon(bounds,a.boundary);vertices+=a.boundary.vertices.size();
            }
            for(Entrance e:entrances){
                unique(ids,e.id);checkScope(e.floorId,e.zoneId,floorById,zoneById);
                if(!bounds.contains(e.position)||!e.floorId.isEmpty()&&!floorById.get(e.floorId).contains(e.position))throw invalid("entrance_bounds");
                for(RowGroup r:e.rows)checkScope(r.floorId,r.zoneId,floorById,zoneById);
                references+=e.seatIds.size()+e.rows.size();
            }
            if(vertices>MAX_TOTAL_VERTICES||references>MAX_REFERENCES)throw invalid("geometry_limit");
        }
    }
    static void checkScope(String floor,String zone,Map<String,Floor> floors,Map<String,Zone> zones) {
        if(!floor.isEmpty()&&!floors.containsKey(floor))throw invalid("floor_reference");
        if(!zone.isEmpty()&&(!zones.containsKey(zone)||floor.isEmpty()||!zones.get(zone).floorId.equals(floor)))throw invalid("zone_reference");
    }
    private static void checkPolygon(Bounds bounds,Polygon polygon) {
        for(Point point:polygon.vertices)if(!bounds.contains(point))throw invalid("polygon_bounds");
    }
    private static double cross(Point a,Point b,Point c) { return (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x); }
    private static boolean onSegment(Point a,Point b,Point c) {
        return cross(a,b,c)==0&&c.x>=Math.min(a.x,b.x)&&c.x<=Math.max(a.x,b.x)&&c.z>=Math.min(a.z,b.z)&&c.z<=Math.max(a.z,b.z);
    }
    private static boolean intersects(Point a,Point b,Point c,Point d) {
        double abC=cross(a,b,c),abD=cross(a,b,d),cdA=cross(c,d,a),cdB=cross(c,d,b);
        return (abC>0&&abD<0||abC<0&&abD>0)&&(cdA>0&&cdB<0||cdA<0&&cdB>0)
            ||onSegment(a,b,c)||onSegment(a,b,d)||onSegment(c,d,a)||onSegment(c,d,b);
    }
    static String key(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_-]{1,64}"))throw invalid("key");return value;
    }
    static String optionalKey(String value) { if(value==null)throw invalid("key");return value.isEmpty()?value:key(value); }
    static String caption(String value) { return SeatRegistry.label(value,64,false); }
    static void revision(long value) { if(value<0||value==Long.MAX_VALUE)throw invalid("revision"); }
    static void unique(Set<String> ids,String id) { if(!ids.add(id))throw invalid("duplicate_id"); }
    static <T> List<T> bounded(List<T> input,int max,String code) {
        if(input==null||input.size()>max||input.stream().anyMatch(Objects::isNull))throw invalid(code);return List.copyOf(input);
    }
    private static void finite(double value) { if(!Double.isFinite(value))throw invalid("coordinate"); }
    private static void coordinate(double value) { finite(value);if(value < -WORLD_LIMIT||value > WORLD_LIMIT)throw invalid("coordinate"); }
    static IllegalArgumentException invalid(String code) { return new IllegalArgumentException("mycinema.seats.map."+code); }
    private SeatMapGeometry() {}
}
