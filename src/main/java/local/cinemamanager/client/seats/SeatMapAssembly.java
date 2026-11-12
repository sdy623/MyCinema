package local.cinemamanager.client.seats;

import java.util.*;
import local.cinemamanager.seats.map.PublicSeatMap;

/** Bounded, atomic client assembly. A failed refresh never replaces the last complete view. */
public final class SeatMapAssembly {
    public static final int MAX_BYTES=1024*1024,MAX_PAGES=64,MAX_PACKET_BYTES=48*1024;
    private PublicSeatMap.View complete;
    private final List<PublicSeatMap.Page> pending=new ArrayList<>();
    private int bytes;
    private boolean stale=true;
    public void begin(){pending.clear();bytes=0;stale=true;}
    public void fail(){begin();}
    public PublicSeatMap.View complete(){return complete;}
    public boolean stale(){return stale;}
    public int nextIndex(){return pending.size();}
    public String snapshot(){return pending.isEmpty()?"":pending.getFirst().header().snapshotId();}
    public boolean accept(PublicSeatMap.Page page,int wireBytes){
        try {
            if(page==null||wireBytes<0||wireBytes>MAX_PACKET_BYTES||bytes>MAX_BYTES-wireBytes||page.pageCount()>MAX_PAGES||page.index()!=pending.size())throw new IllegalArgumentException("page_limit");
            if(!pending.isEmpty()){
                var first=pending.getFirst();
                if(!first.header().equals(page.header())||first.totalSeats()!=page.totalSeats()||first.pageSize()!=page.pageSize())throw new IllegalArgumentException("mixed_snapshot");
            }
            bytes+=wireBytes;pending.add(page);
            if(pending.size()!=page.pageCount())return false;
            PublicSeatMap.View verified=PublicSeatMap.assemble(pending);
            complete=verified;stale=false;pending.clear();bytes=0;return true;
        }catch(RuntimeException invalid){fail();throw invalid;}
    }
}
