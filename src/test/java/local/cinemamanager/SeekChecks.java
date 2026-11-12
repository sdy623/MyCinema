package local.cinemamanager;
import local.cinemamanager.control.*;
import java.util.*;
public final class SeekChecks {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        UUID actor=UUID.randomUUID(),terminal=UUID.randomUUID();Object clock=new Object();
        SeekTickets.Session s=new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",7,6000,false,true);
        for(long target:new long[]{0,1,2856,3611,5999,6000}){SeekTickets b=new SeekTickets();String token=b.issue(s,10);check(!token.isBlank(),"known VOD lease issued");check(b.consume(token,s,11,target),"target in bounds "+target);check(!b.consume(token,s,12,target),"one-use replay denied");}
        for(long target:new long[]{Long.MIN_VALUE,-1,6001,Long.MAX_VALUE}){SeekTickets b=new SeekTickets();check(!b.consume(b.issue(s,10),s,11,target),"untrusted target denied "+target);}
        var changes=List.of(new SeekTickets.Session(UUID.randomUUID(),"hall",terminal,clock,"url#0",7,6000,false,true),new SeekTickets.Session(actor,"other",terminal,clock,"url#0",7,6000,false,true),new SeekTickets.Session(actor,"hall",UUID.randomUUID(),clock,"url#0",7,6000,false,true),new SeekTickets.Session(actor,"hall",terminal,new Object(),"url#0",7,6000,false,true),new SeekTickets.Session(actor,"hall",terminal,clock,"url#1",7,6000,false,true),new SeekTickets.Session(actor,"hall",terminal,clock,"new#0",7,6000,false,true),new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",8,6000,false,true),new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",7,9000,false,true),new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",7,6000,true,true),new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",7,6000,false,false));
        for(var changed:changes){SeekTickets b=new SeekTickets();check(!b.consume(b.issue(s,10),changed,11,1000),"changed authority/session/metadata/control rejects stale lease");}
        for(long tick:new long[]{9,311,Long.MAX_VALUE}){SeekTickets b=new SeekTickets();check(!b.consume(b.issue(s,10),s,tick,1000),"old/future lease rejected");}
        SeekTickets limit=new SeekTickets();check(limit.consume(limit.issue(s,10),s,310,1000),"15s boundary valid");
        SeekTickets refreshed=new SeekTickets();String frozen=refreshed.issue(s,10);for(int i=11;i<30;i++)refreshed.issue(s,i);check(refreshed.consume(frozen,s,30,2000),"auto refresh does not invalidate held lease");
        for(long duration:new long[]{0,-1})check(new SeekTickets().issue(new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",7,duration,false,true),10).isBlank(),"unknown duration disabled");
        check(new SeekTickets().issue(new SeekTickets.Session(actor,"hall",terminal,clock,"url#0",7,6000,true,true),10).isBlank(),"live disabled");
        SeekDrag d=new SeekDrag();d.update(new SeekDrag.View("hall","session","one",6000,1000,true));check(d.begin(),"begin drag");d.move(3500);d.update(new SeekDrag.View("hall","session","two",6000,1500,true));check(d.preview()==3500,"refresh does not steal preview");var c=d.release();check(c!=null&&c.ticket().equals("one")&&c.target()==3500,"release uses frozen ticket once");check(d.release()==null,"second release sends no packet");check(!d.begin(),"pending request blocks duplicate drag");d.update(new SeekDrag.View("hall","session","three",6000,3500,true));check(!d.acknowledge("old-refresh"),"refresh cannot acknowledge seek");check(d.pending(),"unrelated snapshot leaves pending guard active");check(d.acknowledge("one"),"matching seek acknowledgement releases pending guard");check(d.preview()==3500&&d.begin(),"ack resumes interaction");d.move(-500);check(d.preview()==0,"negative preview clamps");d.move(Long.MAX_VALUE);check(d.preview()==6000,"preview upper bound clamps");d.cancel();check(d.release()==null,"cancel never commits");
        for(var next:List.of(new SeekDrag.View("other","session","new",6000,1000,true),new SeekDrag.View("hall","newsession","new",6000,1000,true),new SeekDrag.View("hall","session","new",7000,1000,true),new SeekDrag.View("hall","session","",6000,1000,false))){d.update(new SeekDrag.View("hall","session","one",6000,1000,true));check(d.begin(),"prepare interrupted drag");d.move(2000);d.update(next);check(!d.dragging()&&d.release()==null,"changed hall/source/duration/permission cancels without send");}
        d.update(new SeekDrag.View("hall","session","no-op",6000,1000,true));check(d.begin()&&d.release()==null&&!d.pending(),"unchanged release sends no seek and does not take over");
        d.update(null);check(!d.begin(),"missing hall cannot seek");
        System.out.println("PASS: "+checks+" seek lease and drag lifecycle checks");
    }
}
