package local.cinemamanager.panels.assets;

import local.cinemamanager.ManagerTile;
import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.UUID;

/** Exact loaded manager, owner and revision binding; checked before each chunk and after validation. */
public final class AssetFilmLease {
    private final ManagerTile manager;
    private final UUID identity,owner;
    private final AssetNetwork.FilmTarget target;
    public AssetFilmLease(ManagerTile manager,AssetNetwork.FilmTarget target){
        this.manager=manager;this.target=target;identity=manager.identity;owner=manager.owner;
    }
    public boolean uploadCurrent(ServerPlayer actor){
        if(actor==null||!actor.isAlive()||actor.getServer()==null||!actor.getServer().isSameThread()
                ||owner==null||manager.getLevel()!=actor.level()||manager.isRemoved()
                ||!actor.level().hasChunkAt(manager.getBlockPos())||actor.level().getBlockEntity(manager.getBlockPos())!=manager
                ||!manager.getBlockPos().equals(target.pos())||!identity.toString().equals(target.identity())
                ||!identity.equals(manager.identity)||!Objects.equals(owner,manager.owner)||!manager.controlAllowed(actor))return false;
        try{
            manager.library.catalog().requireRevision(target.catalogRevision());
            return target.filmId().isEmpty()?target.filmRevision()==0:
                    manager.library.catalog().film(target.filmId()).revision()==target.filmRevision();
        }catch(RuntimeException stale){return false;}
    }
}
