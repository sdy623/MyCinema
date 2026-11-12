package local.cinemamanager.panels.assets;

import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.PanelGroup;
import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.UUID;

/** Captured live target binding, rechecked for every transfer step. Network entry also requires a real connected player. */
public final class AssetPanelLease {
    private final InfoTile tile;private final UUID identity,owner;private final String signature,posterPublication;
    public AssetPanelLease(InfoTile tile,String signature){this.tile=tile;identity=tile.identity;owner=tile.owner;this.signature=signature;posterPublication=tile.approvedPosterToken();}
    private boolean current(ServerPlayer actor,double distance){
        return actor!=null&&actor.isAlive()&&actor.getServer()!=null&&actor.getServer().isSameThread()
                &&tile.getLevel()==actor.level()&&!tile.isRemoved()&&actor.level().hasChunkAt(tile.getBlockPos())
                &&actor.level().getBlockEntity(tile.getBlockPos())==tile&&tile.identity.equals(identity)&&Objects.equals(owner,tile.owner)
                &&tile.panelDistanceSquared(actor)<=distance*distance&&actor.level().mayInteract(actor,tile.getBlockPos())
                &&PanelGroup.of(tile).signature().equals(signature);
    }
    public boolean uploadCurrent(ServerPlayer actor){
        if(!current(actor,8)||!tile.permits(actor,8))return false;
        for(InfoTile member:PanelGroup.of(tile).members())if(!member.permits(actor,Double.POSITIVE_INFINITY))return false;return true;
    }
    public boolean downloadCurrent(ServerPlayer actor,String reference){return reference!=null&&!reference.isBlank()&&current(actor,64)&&reference.equals(tile.approvedPoster())&&posterPublication.equals(tile.approvedPosterToken());}
}
