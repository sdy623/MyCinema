package local.cinemamanager.client;

import java.util.List;

/** Pure availability regression; actual authorization remains server-side. */
public final class ProgrammeStopPolicyChecks {
    private static int checks;
    public static void main(String[] args) {
        for(String status:new String[]{"PREPARING","READY","RUNNING","PLAYING","PAUSED","GAP","COMPLETED","STOPPED"}) {
            check(ProgrammeStopPolicy.enabled(true,true,true,false,status),"explicit Stop available for "+status);
            check(!ProgrammeStopPolicy.enabled(false,true,true,false,status),"read-only remains blocked for "+status);
            check(!ProgrammeStopPolicy.enabled(true,false,true,false,status),"multi-hall remains blocked for "+status);
            check(!ProgrammeStopPolicy.enabled(true,true,false,false,status),"stale state remains blocked for "+status);
            check(!ProgrammeStopPolicy.enabled(true,true,true,true,status),"pending request remains blocked for "+status);
        }
        for(String status:new String[]{null,"FAILED","OVERRIDDEN","STOP_PENDING","","completed","UNKNOWN"})
            check(!ProgrammeStopPolicy.enabled(true,true,true,false,status),"unavailable or unrecognized state fails closed");
        pendingNoticeChecks();
        System.out.println("PASS: "+checks+" programme Stop presentation checks");
    }
    private static void pendingNoticeChecks(){
        String pending="{tr:mycinema.message.m060}{tr:mycinema.show.stop_pending}";
        var stopped=new ProgrammeStopPolicy.HallStatus("STOPPED","","");
        check(ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(stopped),false),"single stopped hall hides only obsolete pending footer");
        check(ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(stopped,stopped),false),"all stopped halls allow obsolete footer suppression");
        check(!ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(),false),"empty hall list proves no completed stop");
        check(!ProgrammeStopPolicy.hideObsoletePendingNotice(pending,null,false),"missing halls remain uncertain");
        check(!ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(stopped),true),"pending scheduled stop remains visible");
        for(String status:new String[]{null,"PREPARING","READY","PLAYING","PAUSED","GAP","COMPLETED","FAILED","OVERRIDDEN","STOP_PENDING","UNKNOWN"})
            check(!ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(stopped,new ProgrammeStopPolicy.HallStatus(status,"","")),false),"another unknown, restoring or non-stopped hall preserves pending footer: "+status);
        for(String failure:new String[]{null,"stop_unconfirmed_clock_initializing","source_permission"})
            check(!ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(new ProgrammeStopPolicy.HallStatus("STOPPED",failure,"")),false),"missing or failed Stop evidence preserves pending footer");
        check(!ProgrammeStopPolicy.hideObsoletePendingNotice(pending,List.of(stopped,new ProgrammeStopPolicy.HallStatus("STOPPED","","mycinema.show.stop_pending")),false),"another hall's pending issue remains visible");
        for(String notice:new String[]{null,"","mycinema.show.stop_pending","mycinema.catalog.programme_stopped",pending+" ","{tr:mycinema.message.m060}{tr:mycinema.catalog.source_permission}"})
            check(!ProgrammeStopPolicy.hideObsoletePendingNotice(notice,List.of(stopped),false),"unrelated or non-exact notice is never suppressed");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
