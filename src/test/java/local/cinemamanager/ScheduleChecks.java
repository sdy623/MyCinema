package local.cinemamanager;

import local.cinemamanager.model.ManagerState;
import local.cinemamanager.model.ManagerState.*;
import local.cinemamanager.model.ScheduleBrowse;
import java.time.Instant;
import java.util.*;

/** Deterministic management contracts; no network, files, decoder or server is opened. */
public final class ScheduleChecks {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    private static void rejects(Runnable action,String key){try{action.run();throw new AssertionError("Expected rejection: "+key);}catch(IllegalArgumentException e){check(e.getMessage().equals(key),key);}}
    private static Hall hall(ManagerState m,String name){Hall h=new Hall();h.name=name;h.terminalIdentity=UUID.randomUUID().toString();m.halls.add(h);return h;}
    public static void main(String[] args){
        long now=Instant.parse("2030-01-01T00:00:00Z").toEpochMilli();
        ManagerState m=new ManagerState();m.setTimezone("Asia/Tokyo");Hall a=hall(m,"A"),b=hall(m,"B");
        Show first=m.addShow(a.id,"First","water://first","2030-01-01 10:00",30,now);
        Show second=m.addShow(a.id,"Second","water://second","2030-01-01 11:00",30,now);
        Show other=m.addShow(b.id,"Other hall","water://other","2030-01-01 10:00",30,now);
        String id=first.id;long expected=m.revision;String before=StateCodec.encode(m);
        rejects(()->m.editShow(id,expected-1,"Stale","water://x","2030-01-01 12:00",30,now),"mycinema.schedule.stale");check(before.equals(StateCodec.encode(m)),"stale edit changes nothing");
        rejects(()->m.editShow(id,expected,"Overlap","water://x","2030-01-01 11:10",30,now),MessageText.key("message.m123"));check(before.equals(StateCodec.encode(m)),"overlap rejection is atomic");
        m.editShow(id,expected,"更新影片","C:\\Cinema\\影片 01.mkv","2030-01-01 12:00",45,now);
        check(first.id.equals(id)&&m.shows.indexOf(first)>m.shows.indexOf(second),"time edit reorders without replacing stable identity");
        check(first.hallId.equals(a.id)&&first.status==Status.PENDING&&first.end-first.start==2700000,"edit retains hall and pending state");
        check(!m.schedulerEnabled&&!a.override&&a.transport==Transport.STOPPED,"editing never starts media or takes control");
        check(ScheduleBrowse.selected(m,id,a.id)==first&&ScheduleBrowse.selected(m,id,b.id)==null,"selection stays on exact ID and cannot borrow another hall source");
        check(ScheduleBrowse.selected(m,"missing",a.id)==null,"missing selection never substitutes an adjacent row");
        ManagerState copy=StateCodec.decode(StateCodec.encode(m));check(copy.show(id).url.equals(first.url)&&copy.inputTime(first.start).equals("2030-01-01 12:00")&&copy.revision==m.revision,"edited source, revision, timezone and ID survive persistence");
        check(StateCodec.decode(StateCodec.publicView(m)).show(id).url.isEmpty(),"public view still redacts planned sources");
        for(Status status:List.of(Status.RUNNING,Status.STOP_PENDING)){
            first.status=status;long rev=m.revision;String active=StateCodec.encode(m);
            rejects(()->m.editShow(id,rev,"New","water://new","2030-01-01 13:00",30,now),"mycinema.schedule.active_edit");
            rejects(()->m.deleteShow(id,rev),"mycinema.schedule.active_delete");check(active.equals(StateCodec.encode(m)),"active edit/delete preserve all state "+status);
        }
        for(Status status:List.of(Status.CANCELLED,Status.COMPLETED,Status.MISSED,Status.OVERRIDDEN,Status.FAILED)){
            first.status=status;long rev=m.revision;rejects(()->m.editShow(id,rev,"New","water://new","2030-01-01 13:00",30,now),"mycinema.schedule.history_edit");
        }
        first.status=Status.PENDING;long revision=m.revision;before=StateCodec.encode(m);
        rejects(()->m.deleteShow(id,revision-1),"mycinema.schedule.stale");check(before.equals(StateCodec.encode(m)),"stale delete changes nothing");
        m.deleteShow(id,revision);check(ScheduleBrowse.selected(m,id,a.id)==null&&m.shows.contains(second)&&m.shows.contains(other),"delete removes only exact selected show");
        rejects(()->m.deleteShow(id,m.revision),"mycinema.schedule.missing");
        ManagerState pages=new ManagerState();pages.setTimezone("UTC");Hall p=hall(pages,"One"),q=hall(pages,"Two");
        for(int i=0;i<128;i++){
            Show show=new Show();show.id=String.format(Locale.ROOT,"show-%03d",i);show.hallId=i%2==0?p.id:q.id;show.title="Film "+i;show.url="water://film-"+i;show.start=now+i*60000L;show.end=show.start+60000;pages.shows.add(show);
        }
        List<Show> all=ScheduleBrowse.filtered(pages,"");check(ScheduleBrowse.pages(all)==26,"128 shows fit 26 bounded pages");
        List<String> seen=new ArrayList<>();for(int page=0;page<ScheduleBrowse.pages(all);page++){
            var rows=ScheduleBrowse.page(all,page);check(rows.size()<=ScheduleBrowse.PAGE_SIZE,"page capacity");seen.addAll(rows.stream().map(s->s.id).toList());
        }
        check(new HashSet<>(seen).size()==128&&seen.getLast().equals("show-127"),"every show is reachable once including final partial page");
        check(ScheduleBrowse.filtered(pages,p.id).size()==64&&ScheduleBrowse.filtered(pages,q.id).size()==64,"hall filter is exhaustive and isolated");
        Collections.reverse(pages.shows);check(ScheduleBrowse.filtered(pages,"").stream().map(s->s.id).toList().equals(seen),"refresh storage order cannot shuffle stable time/ID order");
        check(ScheduleBrowse.clampPage(List.of(),40)==0&&ScheduleBrowse.page(List.of(),40).isEmpty(),"empty/delete-last page clamps safely");
        check(ScheduleBrowse.page(all,-1).getFirst()==all.getFirst()&&ScheduleBrowse.page(all,100).getLast()==all.getLast(),"page bounds retain first and last records");
        for(Show show:pages.shows){show.url="https://example.com/"+"a".repeat(2028);show.title="片".repeat(80);}
        String maximum=StateCodec.encode(pages);check(maximum.length()<=512000,"128 maximum source/title rows remain within snapshot character bound");
        var packet=new ManagerNetwork.Snapshot(new net.minecraft.core.BlockPos(1,2,3),UUID.randomUUID().toString(),false,false,false,maximum);
        var buffer=io.netty.buffer.Unpooled.buffer();ManagerNetwork.Snapshot.CODEC.encode(buffer,packet);var decoded=ManagerNetwork.Snapshot.CODEC.decode(buffer);buffer.release();
        check(decoded.equals(packet)&&StateCodec.decode(decoded.json()).shows.size()==128,"maximum owner snapshot survives actual UTF-8 packet and state codecs");
        System.out.println("PASS: "+checks+" schedule management assertions; atomic edits, stale revisions, active/history protection, persistence, stable hall selection and 128-show pagination.");
    }
}
