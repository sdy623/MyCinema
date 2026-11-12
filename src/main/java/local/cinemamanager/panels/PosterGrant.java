package local.cinemamanager.panels;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.InfoTile;
import local.cinemamanager.policy.CinemaPermissions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.Objects;
import java.util.UUID;

/** Server-private authorship, never a client-supplied permission or a cached OP decision. */
public record PosterGrant(UUID author, String authorName, UUID panelIdentity, UUID panelOwner, String source) {
    public PosterGrant {
        if(author==null||authorName==null||authorName.isBlank()||authorName.length()>64||authorName.chars().anyMatch(c->c<32||c==127)
                ||panelIdentity==null||source==null||source.isBlank()||!Objects.equals(source,PosterPolicy.normalize(source)))
            throw new IllegalArgumentException("Invalid poster grant");
    }
    public static PosterGrant issue(ServerPlayer actor,InfoTile tile,String source){
        if(!(tile.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()||actor.level()!=level
                ||!(local.cinemamanager.panels.assets.AssetReference.isAsset(source)?local.cinemamanager.panels.assets.PanelAssets.mayUseAsset(actor,tile,source):PosterPolicy.maySave(actor,source)))throw new IllegalArgumentException("mycinema.panel.poster_denied");
        if(source.isBlank()||PosterPolicy.resource(source))return null;
        return new PosterGrant(actor.getUUID(),actor.getGameProfile().getName(),tile.identity,tile.owner,source);
    }
    public boolean current(InfoTile tile){
        if(!(tile.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()||tile.isRemoved()
                ||!level.hasChunkAt(tile.getBlockPos())||level.getBlockEntity(tile.getBlockPos())!=tile
                ||!panelIdentity.equals(tile.identity)||!Objects.equals(panelOwner,tile.owner)||!source.equals(PosterPolicy.normalize(tile.panel.poster())))return false;
        if(local.cinemamanager.panels.assets.AssetReference.isAsset(source))return local.cinemamanager.panels.assets.PanelAssets.current(tile,author,source);
        return CinemaPermissions.canSavePosterOffline(level.getServer(),new GameProfile(author,authorName),source);
    }
    /** A redirect never inherits a cached bypass. Recheck the author's current target policy. */
    public boolean currentTarget(InfoTile tile,String target){
        return current(tile) && tile.getLevel() instanceof ServerLevel level
                && CinemaPermissions.canSavePosterOffline(level.getServer(),new GameProfile(author,authorName),target);
    }
    /** Same-owner placement extends an existing authorized column, without inventing an author. */
    public PosterGrant extend(InfoTile previous,InfoTile added){
        return current(previous)&&Objects.equals(previous.owner,added.owner)&&Objects.equals(previous.panel,added.panel)
                ?new PosterGrant(author,authorName,added.identity,added.owner,source):null;
    }
    public CompoundTag save(){CompoundTag t=new CompoundTag();t.putInt("version",1);t.putUUID("author",author);t.putString("author_name",authorName);t.putUUID("panel",panelIdentity);if(panelOwner!=null)t.putUUID("owner",panelOwner);t.putString("source",source);return t;}
    public static PosterGrant read(CompoundTag t){try{return t.getInt("version")==1?new PosterGrant(t.getUUID("author"),t.getString("author_name"),t.getUUID("panel"),t.hasUUID("owner")?t.getUUID("owner"):null,t.getString("source")):null;}catch(RuntimeException ignored){return null;}}
}
