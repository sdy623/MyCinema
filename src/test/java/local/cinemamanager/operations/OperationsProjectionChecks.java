package local.cinemamanager.operations;

import local.cinemamanager.model.ManagerState;
import java.time.Instant;
import java.util.*;

/** Source-free public wall payload and cinema-day boundaries, independent of media execution. */
public final class OperationsProjectionChecks {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejects(Runnable run){try{run.run();throw new AssertionError("invalid projection accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        for(String unsafe:List.of("file:/private/a.mkv","https://host/a?token=secret","C:\\private\\a.mp4","water:private","film.mkv","token = secret","owner=secret","a\nb"))
            check(PublicOperationsSnapshot.label(unsafe,"safe").equals("safe"),"private label rejected: "+unsafe);
        check(PublicOperationsSnapshot.label("星际旅行","").equals("星际旅行"),"ordinary Unicode titles survive");
        check(PublicOperationsSnapshot.code("PRIVATE/path").equals("UNAVAILABLE"),"status cannot carry a path");
        check(PublicOperationsSnapshot.zone("invalid/zone").isEmpty(),"unknown time zone has no invented date");
        long now=Instant.parse("2026-10-07T12:00:00Z").toEpochMilli();
        var show=new ManagerState.Show();show.start=Instant.parse("2026-10-06T23:00:00Z").toEpochMilli();show.end=Instant.parse("2026-10-07T01:00:00Z").toEpochMilli();
        check(PublicOperationsSnapshot.today(show,"UTC",now),"cross-midnight show overlaps today");
        show.end=Instant.parse("2026-10-07T00:00:00Z").toEpochMilli();check(!PublicOperationsSnapshot.today(show,"UTC",now),"a show ending at midnight is outside today");
        show.start=Instant.parse("2026-10-08T00:00:00Z").toEpochMilli();show.end=show.start+1000;check(!PublicOperationsSnapshot.today(show,"UTC",now),"tomorrow boundary excluded");
        show.start=Instant.parse("2026-03-08T05:00:00Z").toEpochMilli();show.end=Instant.parse("2026-03-09T04:00:00Z").toEpochMilli();
        check(PublicOperationsSnapshot.today(show,"America/New_York",Instant.parse("2026-03-08T12:00:00Z").toEpochMilli()),"DST day uses local midnight, not a fixed 24 hours");
        check(!PublicOperationsSnapshot.today(show,"",now),"no time zone means no schedule guess");
        var data=new PublicOperationsSnapshot("Cinema","UTC",now,"AVAILABLE",List.of(new PublicOperationsSnapshot.Hall("Hall","PLAYING","Film",1,2)),List.of(new PublicOperationsSnapshot.Plan("Hall","Film",now,now+1000,"PENDING")));
        check(PublicOperationsSnapshot.read(data.save()).equals(data),"public snapshot NBT roundtrip");
        String json=OperationsNetwork.JSON.toJson(data);
        for(String secret:List.of("owner","controller","grant","source","token","url","identity","filmSnapshot","hallId"))check(!json.contains(secret),"wall projection excludes "+secret);
        var later=new PublicOperationsSnapshot(data.cinema(),data.timezone(),now+1000,data.state(),data.halls(),data.plans());
        check(data.sameContent(later),"same-day refresh does not republish unchanged walls");
        var tomorrow=new PublicOperationsSnapshot(data.cinema(),data.timezone(),now+86400000,data.state(),data.halls(),data.plans());
        check(!data.sameContent(tomorrow),"date rollover refreshes walls");
        rejects(()->new PublicOperationsSnapshot("","UTC",now,"AVAILABLE",Collections.nCopies(ManagerState.MAX_HALLS+1,data.halls().getFirst()),List.of()));
        rejects(()->new PublicOperationsSnapshot.Plan("","",10,10,"PENDING"));
        rejects(()->new PublicOperationsSnapshot.Hall("","","",2,1));
        System.out.println("OperationsProjectionChecks passed "+checks+" checks");
    }
}
