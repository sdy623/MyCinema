package local.cinemamanager.panels;
import local.cinemamanager.policy.CinemaPermissions;

import java.net.URI;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import me.srrapero720.waterframes.*;
import local.cinemamanager.media.MediaSource;
import net.minecraft.world.entity.player.Player;

/** Syntax and current server policy only. Never probes a server-local client file. */
public final class PosterPolicy {
    private PosterPolicy(){}
    public static boolean resource(String s){return s!=null&&s.startsWith("resource:");}
    public static String normalize(String input){
        if(input==null||input.length()>2048||input.chars().anyMatch(c->c<32||c==127))return null;
        String s=input.strip();if(s.isEmpty())return "";
        if(s.startsWith("mycinema-asset:"))return local.cinemamanager.panels.assets.AssetReference.normalize(s);
        if(resource(s)){String id=s.substring(9);ResourceLocation r=ResourceLocation.tryParse(id);return r!=null&&!r.getPath().contains("..")&&r.getPath().startsWith("textures/")?"resource:"+r:null;}
        try{
            String normalized=MediaSource.normalize(s);if(normalized==null)return null;
            if(MediaSource.relative(normalized))return normalized;
            URI u=URI.create(normalized);String scheme=u.getScheme();
            if(scheme.equals("file"))return normalized;
            if(!scheme.equals("http")&&!scheme.equals("https"))return null;
            String host=u.getHost();if(host==null||u.getUserInfo()!=null)return null;
            host=host.toLowerCase(Locale.ROOT);if(host.endsWith("."))host=host.substring(0,host.length()-1);
            // Keep raw path/query bytes, including signed CDN parameters and encoded slashes.
            String result=scheme+"://"+host+(u.getPort()==-1?"":":"+u.getPort())+(u.getRawPath()==null?"":u.getRawPath())
                    +(u.getRawQuery()==null?"":"?"+u.getRawQuery())+(u.getRawFragment()==null?"":"#"+u.getRawFragment());
            return result.length()<=2048?result:null;
        }catch(RuntimeException e){return null;}
    }
    public static boolean valid(String s){return normalize(s)!=null;}
    public static boolean maySave(Player p,String s){String n=normalize(s);return n!=null&&(n.isEmpty()||resource(n)||CinemaPermissions.canSave(p,n));}
    /** Conservative legacy/no-author path. A bypass requires the server-only saved grant. */
    public static boolean mayPublish(String s){String n=normalize(s);if(local.cinemamanager.panels.assets.AssetReference.isAsset(n))return false;return n!=null&&(n.isEmpty()||resource(n)||CinemaPermissions.isWhitelisted(n));}
}
