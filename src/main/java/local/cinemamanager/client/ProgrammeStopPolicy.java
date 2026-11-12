package local.cinemamanager.client;

/** Client presentation only. Every request still uses the server's exact programme/authority guards. */
public final class ProgrammeStopPolicy {
    private static final String STOP_PENDING = "mycinema.show.stop_pending";
    private static final String PENDING_NOTICE = "{tr:mycinema.message.m060}{tr:mycinema.show.stop_pending}";
    private ProgrammeStopPolicy() {}
    public static boolean enabled(boolean writable,boolean singleHall,boolean fresh,boolean pending,String status) {
        if(!writable||!singleHall||!fresh||pending||status==null)return false;
        return switch(status){
            case "PREPARING","READY","RUNNING","PLAYING","PAUSED","GAP","COMPLETED","STOPPED" -> true;
            default -> false;
        };
    }

    /** A null status represents a hall with no current programme. */
    public record HallStatus(String status,String failureCode,String issue) {}

    /** Hide only this obsolete footer; retain uncertain, active and other-hall pending states. */
    public static boolean hideObsoletePendingNotice(String notice,Iterable<HallStatus> halls,boolean pendingShow) {
        if(!PENDING_NOTICE.equals(notice)||halls==null||pendingShow)return false;
        boolean hasHall=false;
        for(HallStatus hall:halls){
            if(hall==null||!"STOPPED".equals(hall.status())||!"".equals(hall.failureCode())
                    ||STOP_PENDING.equals(hall.issue()))return false;
            hasHall=true;
        }
        return hasHall;
    }
}
