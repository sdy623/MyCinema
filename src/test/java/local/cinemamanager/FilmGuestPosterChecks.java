package local.cinemamanager;

import local.cinemamanager.model.ManagerState;
import local.cinemamanager.panels.*;
import java.util.List;

/** Pure selection and publication-lifetime checks; no decoder, GUI or media access. */
public final class FilmGuestPosterChecks {
    private static int checks;
    private static final long NOW=1_900_000_000_000L;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        ManagerState state=new ManagerState();state.timezone="UTC";
        ManagerState.Hall hall=new ManagerState.Hall();hall.name="Public hall";state.halls.add(hall);
        ManagerState.Show next=show(state,hall,"Next",NOW+1000,ManagerState.Status.PENDING);
        ManagerState.Show current=show(state,hall,"Current",NOW-1000,ManagerState.Status.RUNNING);
        check(PublicSchedule.firstHallShow(state,hall.id,NOW)==current,"running programme wins over upcoming programme");
        check(PublicSchedule.project(state,PanelScope.HALL,hall.id,NOW,s->60000).rows().getFirst().id().equals(current.id),"poster selection exactly matches the first displayed row");
        current.status=ManagerState.Status.COMPLETED;
        check(PublicSchedule.firstHallShow(state,hall.id,NOW)==next,"completed programme gives way to next programme");
        next.end=NOW;
        check(PublicSchedule.firstHallShow(state,hall.id,NOW)==null,"end boundary immediately withdraws selected programme");
        next.end=NOW+60000;
        check(PublicSchedule.firstHallShow(state,"unbound",NOW)==null,"unbound hall cannot inherit another hall's film");
        state.timezone="invalid/zone";
        check(PublicSchedule.firstHallShow(state,hall.id,NOW)==null,"missing valid timezone withdraws both poster and schedule");
        state.timezone="UTC";
        String guest=PanelNetwork.JSON.toJson(PublicSchedule.project(state,PanelScope.HALL,hall.id,NOW,s->60000));
        for(String secret:List.of("PRIVATE_MEDIA_PATH","PRIVATE_SNAPSHOT","PRIVATE_FILM","filmSnapshot","filmRevision","url"))
            check(!guest.contains(secret),"guest rows omit private program information: "+secret);
        check(PublicShowRow.class.getRecordComponents().length==7,"rows retain seven bounded public fields including schedule presence, without per-row URI multiplication");

        PosterPublicationState publication=new PosterPublicationState();
        String first=publication.select("show1|snapshot1","https://i.imgur.com/same-poster");
        check(PosterPublicationState.validToken(first),"a publication has a bounded opaque UUID");
        check(publication.select("show1|snapshot1","https://i.imgur.com/same-poster").equals(first),"clock-only refresh keeps a valid cached poster");
        String replacement=publication.select("show2|snapshot2","https://i.imgur.com/same-poster");
        check(!first.equals(replacement),"different immutable snapshot invalidates callbacks even with the same poster URI");
        check(publication.select("","").isEmpty(),"permission revocation withdraws publication token");
        String restored=publication.select("show2|snapshot2","https://i.imgur.com/same-poster");
        check(!restored.equals(replacement),"reauthorization cannot revive a callback from before revocation");
        check(!publication.select("show2|snapshot2","https://i.imgur.com/changed").equals(restored),"source change invalidates in-flight image");
        String old=publication.select("show3|snapshot3","resource:minecraft:textures/block/stone.png");
        publication.clear();
        check(!publication.select("show3|snapshot3","resource:minecraft:textures/block/stone.png").equals(old),"world reload cannot revive a former publication token");
        check(!PosterPublicationState.validToken("PRIVATE_SNAPSHOT")&&!PosterPublicationState.validToken(null),"malformed wire tokens fail closed");
        System.out.println("PASS: "+checks+" automatic film guest selection, privacy and publication lifetime checks.");
    }
    private static ManagerState.Show show(ManagerState state,ManagerState.Hall hall,String title,long start,ManagerState.Status status){
        ManagerState.Show show=new ManagerState.Show();show.hallId=hall.id;show.title=title;show.start=start;show.end=start+60000;show.status=status;
        show.url="file:/PRIVATE_MEDIA_PATH";show.filmId="PRIVATE_FILM";show.filmSnapshot="PRIVATE_SNAPSHOT";
        state.shows.add(show);return show;
    }
}
