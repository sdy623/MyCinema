package local.cinemamanager;

import java.util.*;
import local.cinemamanager.access.EmergencyAccessBook;
import local.cinemamanager.access.EmergencyAccessBook.*;

/** Pure state checks; the opt-in GameTests cover real world and permission integration. */
public final class EmergencyAccessChecks {
    private static int count;
    private static void check(boolean result,String name){count++;if(!result)throw new AssertionError(name);}
    private static void denied(Runnable action,String name){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,name);}
    public static void main(String[] args){
        var a=new Scope(UUID.randomUUID(),UUID.randomUUID(),12);var b=new Scope(UUID.randomUUID(),a.owner(),12);UUID actor=UUID.randomUUID();
        var book=new EmergencyAccessBook();check(!book.state(a).active()&&book.state(a).revision()==0,"defaults closed");
        check(book.change(a,actor,true,0,1000,10),"activate is a transition");check(book.state(a).active(),"override is active");
        check(!book.state(b).active(),"same owner and coordinates do not grant a different cinema");
        check(!book.state(new Scope(a.controller(),UUID.randomUUID(),a.pos())).active(),"owner transfer does not inherit override");
        check(!book.state(new Scope(a.controller(),a.owner(),a.pos()+1)).active(),"position mismatch does not inherit override");
        check(!book.change(a,actor,true,1,1001,11),"idempotent desired state adds no transition");check(book.audit().size()==1,"idempotent retries add no audit entry");
        denied(()->book.change(a,actor,false,0,1002,12),"stale restore rejected");
        check(book.change(a,actor,false,1,1003,13),"explicit restore succeeds");
        denied(()->book.change(a,actor,true,0,1004,14),"old activation cannot reopen restored access");
        check(!book.state(a).active()&&book.state(a).revision()==2,"restored revision advances");
        check(book.audit().size()==2&&book.audit().getLast().actor().equals(actor)&&!book.audit().getLast().active(),"audit contains actor and restore");
        var restored=new EmergencyAccessBook();restored.restore(book.states().entrySet(),book.audit());
        check(restored.state(a).equals(book.state(a))&&restored.audit().equals(book.audit()),"restore preserves state and audit");
        restored.change(a,actor,true,2,2000,20);var resumed=new EmergencyAccessBook();resumed.restore(restored.states().entrySet(),restored.audit());
        check(resumed.state(a).active()&&resumed.state(a).revision()==3,"active override survives reload");
        resumed.restore(List.of(Map.entry(a,new State(true,3,2000)),Map.entry(a,new State(false,2,1003))),List.of());
        check(!resumed.state(a).active(),"duplicate cinema state fails closed");
        for(int i=0;i<EmergencyAccessBook.MAX_AUDIT+3;i++)book.change(a,actor,!book.state(a).active(),book.state(a).revision(),3000+i,30+i);
        check(book.audit().size()==EmergencyAccessBook.MAX_AUDIT,"audit remains bounded");
        check(book.audit().getLast().revision()==book.state(a).revision(),"latest transition remains in audit");
        var retired=new EmergencyAccessBook();retired.change(a,actor,true,0,1000,10);
        check(retired.retire(a,1001,11),"observed replaced controller retires active scope");
        check(retired.state(a).retired()&&!retired.state(a).active(),"retired scope stays closed");
        var retiredReload=new EmergencyAccessBook();retiredReload.restore(retired.states().entrySet(),retired.audit());
        check(retiredReload.state(a).retired()&&!retiredReload.state(a).active(),"retired tombstone survives reload");
        denied(()->retiredReload.change(a,actor,true,2,1002,12),"retired identity cannot reactivate after unload/reload");
        check(retiredReload.audit().getLast().reason()==Reason.CONTROLLER_INVALID&&retiredReload.audit().getLast().actor()==null,"automatic invalidation does not impersonate a user");
        denied(()->new State(true,-1,0),"negative revision rejected");
        System.out.println("PASS: "+count+" emergency override model assertions.");
    }
}
