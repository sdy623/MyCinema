package local.cinemamanager;

import local.cinemamanager.panels.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Syntax and private record roundtrips only: no network, player, registry or config mutation. */
public final class PosterPolicyChecks {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void run(){
        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("https://IMGUR.COM./image?id=123&sig=a%2Fb%2Bz", "https://imgur.com/image?id=123&sig=a%2Fb%2Bz");
        cases.put("https://i.imgur.com/poster one.png?size=large", "https://i.imgur.com/poster%20one.png?size=large");
        cases.put("HTTPS://I.IMGUR.COM/%2fsecret?x=%2F&y=%26#preview", "https://i.imgur.com/%2fsecret?x=%2F&y=%26#preview");
        cases.put("https://example.com/render?id=1", "https://example.com/render?id=1");
        cases.put("http://192.168.1.10:8080/image", "http://192.168.1.10:8080/image");
        cases.put("http://[2001:db8::1]:8080/image", "http://[2001:db8::1]:8080/image");
        cases.put("file://localhost/tmp/poster%20one.png", "file:/tmp/poster%20one.png");
        cases.put("C:\\posters\\one two.png", "file:/C:/posters/one%20two.png");
        cases.put("media/poster one.webp", "media/poster one.webp");
        for(var c:cases.entrySet()){
            check(c.getValue().equals(PosterPolicy.normalize(c.getKey())),"normalized source: "+c.getKey());
            check(c.getValue().equals(PosterPolicy.normalize(c.getValue())),"normalization idempotent");
        }
        for(String s:List.of("https://user:secret@example.com/a.png","file://remote/share/a.png","\\\\host\\a.png","water://movie","jar:file:/a!/b","https://example.com/a\n","https://example.com:0/a","resource:minecraft:textures/../a.png","x".repeat(2049)))
            check(PosterPolicy.normalize(s)==null,"unsafe/unsupported source rejected");
        check(PosterPolicy.normalize("https://example.com/片名.png").contains("%E7%89%87"),"Unicode path is encoded without a server filesystem probe");
        UUID author=UUID.randomUUID(),panel=UUID.randomUUID(),owner=UUID.randomUUID();
        var grant=new PosterGrant(author,"QA_Author",panel,owner,"https://example.com/image?id=1");
        check(PosterGrant.read(grant.save()).equals(grant),"private NBT roundtrip");
        check(PosterGrant.read(new CompoundTag())==null,"legacy missing grant has no authority");
        var malformed=grant.save();malformed.putString("source","https://user:secret@example.com/a.png");
        check(PosterGrant.read(malformed)==null,"malformed stored grant fails closed");
        var settings=PanelSettings.DEFAULT.withPoster(grant.source());
        check(settings.publicOnly(PanelScope.HALL,grant.source()).poster().equals(grant.source()),"server-authorized source reaches guest projection");
        check(settings.publicOnly(PanelScope.HALL,"https://other.example/image").poster().isEmpty(),"unrelated approved URL cannot replace saved source");
        check(settings.publicOnly(PanelScope.HALL,"").poster().isEmpty(),"server revocation withdraws guest source");
        check(!PanelNetwork.JSON.toJson(settings.publicOnly(PanelScope.HALL,grant.source())).contains(author.toString()),"grant author never part of public DTO");
        System.out.println("PASS: "+checks+" poster policy normalization and private-grant checks.");
    }
}
