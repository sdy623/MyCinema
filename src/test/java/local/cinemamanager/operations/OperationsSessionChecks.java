package local.cinemamanager.operations;

/** Exact acknowledgement and timeout behavior; no game, media or world needed. */
public final class OperationsSessionChecks {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        var session=new OperationsSession();
        check(session.fresh(0),"initial server snapshot is fresh");
        check(session.begin("refresh-a",20,false),"start refresh");
        check(!session.begin("refresh-b",21,false),"do not queue another refresh");
        check(session.begin("control-a",22,true),"a user command may supersede a pending refresh");
        check(!session.accept("refresh-a",23)&&session.pending(),"late refresh never acknowledges command");
        check(!session.begin("control-b",24,true),"repeated click cannot issue a second pending command");
        check(!session.accept("control-b",25),"unrelated or stale reply is ignored");
        check(session.accept("control-a",26)&&!session.pending(),"exact ACK releases the command");
        check(!session.accept("control-a",27),"duplicate ACK cannot refresh authority");
        check(session.begin("control-c",30,true),"next explicit command starts");
        check(!session.tick(130)&&session.pending(),"timeout boundary is inclusive");
        check(session.tick(131)&&!session.pending()&&!session.fresh(131),"timeout clears pending and revokes fresh controls");
        check(!session.accept("control-c",132),"late timeout reply is not replayed or applied");
        check(!session.begin("control-d",133,true),"timed-out view cannot control");
        check(session.begin("refresh-c",134,false)&&session.accept("refresh-c",135),"explicit refresh recovers");
        check(session.fresh(335)&&!session.fresh(336),"authority view expires after 200 ticks");
        check(session.begin("refresh-d",400,false),"stale view still refreshes");
        check(!session.tick(501)&&!session.fresh(501),"refresh timeout revokes freshness without a command failure claim");
        check(session.begin("refresh-e",502,false),"a timed-out refresh can be replaced");
        session.close();
        check(!session.accept("refresh-e",503)&&!session.fresh(503),"closed screen rejects late refresh");
        check(!session.begin("",504,false),"empty request IDs are not tracked");
        System.out.println("OperationsSessionChecks passed "+checks+" checks");
    }
}
