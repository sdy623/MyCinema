package local.cinemamanager.access.mixin;

import java.util.UUID;
import local.cinemamanager.mirror.CinemaMirrorStateAccess;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Persists MyCinema publication identity without replacing or redistributing WaterFrames. */
@Mixin(DisplayTile.class)
public abstract class DisplayMirrorStateMixin implements CinemaMirrorStateAccess {
    @Unique private UUID mycinema$mirrorIdentity = UUID.randomUUID();
    @Unique private UUID mycinema$mirrorClaimant;

    @Override public UUID mycinema$mirrorIdentity() { return mycinema$mirrorIdentity; }
    @Override public UUID mycinema$mirrorClaimant() { return mycinema$mirrorClaimant; }

    @Override
    public void mycinema$mirrorClaimant(UUID claimant, boolean rotateIdentity) {
        if (java.util.Objects.equals(mycinema$mirrorClaimant, claimant)) return;
        mycinema$mirrorClaimant = claimant;
        if (rotateIdentity) mycinema$mirrorIdentity = UUID.randomUUID();
        ((DisplayTile) (Object) this).setDirty();
    }

    @Override
    public void mycinema$rotateMirrorIdentity() {
        mycinema$mirrorIdentity = UUID.randomUUID();
        ((DisplayTile) (Object) this).setDirty();
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"), require = 1)
    private void mycinema$saveMirror(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        tag.putUUID("mycinema_mirror_identity", mycinema$mirrorIdentity);
        if (mycinema$mirrorClaimant != null) tag.putUUID("mycinema_mirror_claimant", mycinema$mirrorClaimant);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"), require = 1)
    private void mycinema$loadMirror(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        mycinema$mirrorIdentity = tag.hasUUID("mycinema_mirror_identity")
                ? tag.getUUID("mycinema_mirror_identity") : UUID.randomUUID();
        mycinema$mirrorClaimant = tag.hasUUID("mycinema_mirror_claimant")
                ? tag.getUUID("mycinema_mirror_claimant") : null;
    }
}
