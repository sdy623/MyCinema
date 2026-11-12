package local.cinemamanager.library;

import local.cinemamanager.media.CinemaSources;
import org.watermedia.api.player.MediaPlayer;
import java.util.UUID;

/** Adapter boundary: a placed but unconfigured TMS has an epoch and a null URL. */
public final class FilmPlaybackAdapterChecks {
    public static void main(String[] args) {
        UUID terminal=UUID.randomUUID(),epoch=UUID.randomUUID();
        var empty=new CinemaSources.SessionView(terminal,epoch,null,0,-1,MediaPlayer.Status.STOPPED,0,0,false,false,null,false,"");
        var before=FilmPlayback.session(empty,true);
        if(!before.source().isEmpty()||!before.epoch().equals(epoch)||!before.pausedIntent())throw new AssertionError("fresh TMS snapshot");
        var source=new CinemaSources.SessionView(terminal,epoch,"water://adapter",0,3,MediaPlayer.Status.PAUSED,123,6000,false,true,UUID.randomUUID(),true,"");
        var held=FilmPlayback.session(source,false);
        if(!held.source().equals(source.url())||held.timeMs()!=123||held.pausedIntent()||held.playback()!=FilmProgramState.Playback.PAUSED)throw new AssertionError("actual held clock versus play intent");
        var clockless=new CinemaSources.SessionView(terminal,epoch,"water://adapter",0,-1,MediaPlayer.Status.WAITING,0,0,false,false,source.evidenceId(),false,"");
        var waiting=FilmPlayback.session(clockless,true);
        if(waiting.playback()!=FilmProgramState.Playback.WAITING||waiting.revision()!=-1||!waiting.pausedIntent())throw new AssertionError("cold clock wait is never mapped to Stop");
        if(!waiting.epoch().equals(epoch)||!waiting.source().equals(source.url())||waiting.metadataReady()||waiting.ready())throw new AssertionError("cold clock wait retains exact source without inventing readiness");
        if(FilmPlayback.session(null,true)!=null)throw new AssertionError("unavailable session");
        System.out.println("PASS: 15 film playback adapter assertions; fresh null-source TMS, cold clock wait, retained epoch, real source and independent pause intent.");
    }
}
