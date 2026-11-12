package local.cinemamanager.mirror;

import java.util.UUID;

/** State added to an unmodified WaterFrames display by MyCinema's pinned mixin. */
public interface CinemaMirrorStateAccess {
    UUID mycinema$mirrorIdentity();
    UUID mycinema$mirrorClaimant();
    void mycinema$mirrorClaimant(UUID claimant, boolean rotateIdentity);
    void mycinema$rotateMirrorIdentity();
}
