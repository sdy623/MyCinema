package local.cinemamanager.access.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.watermedia.api.player.MediaPlayer;

/**
 * WaterMedia 3.0.0 pauses a follower before correcting a large authority-clock
 * drift. Its local clock can therefore freeze at an old PTS and never display
 * the server's paused seek target. Seek first, let decoding settle, then allow
 * the next follower tick to pause at the authoritative frame.
 */
@Mixin(value = MediaPlayer.class, remap = false)
public abstract class MediaPlayerPauseSyncMixin {
    @Unique private boolean mycinema$pauseSeekIssued;

    @Redirect(
            method = "follow",
            at = @At(value = "INVOKE", target = "Lorg/watermedia/api/player/MediaPlayer;pause()Z"),
            require = 1
    )
    private boolean mycinema$seekBeforeAuthoritativePause(MediaPlayer player) {
        var authority = player.authority();
        if (authority != null && authority.status() == MediaPlayer.Status.PAUSED) {
            long target = player.authorityTime();
            if (mycinema$pauseSeekIssued) {
                if (Math.abs(target - player.time()) > 250) return true;
                mycinema$pauseSeekIssued = false;
                return player.pause();
            }
            if (Math.abs(target - player.time()) > player.tolerance() && player.seekQuick(target)) {
                mycinema$pauseSeekIssued = true;
                return true;
            }
        }
        mycinema$pauseSeekIssued = false;
        return player.pause();
    }
}
