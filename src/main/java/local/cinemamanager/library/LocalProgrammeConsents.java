package local.cinemamanager.library;

import com.google.gson.Gson;
import local.cinemamanager.catalog.CatalogException;
import local.cinemamanager.catalog.FilmCatalog;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-owned consent for one exact owner/controller/hall/TMS/film revision and ordered evidence. */
public final class LocalProgrammeConsents {
    public static final int MAX_RECORDS=128, MAX_BYTES=1_048_576;
    private static final Gson JSON=new Gson();
    public record Source(String url,UUID evidence,long durationMs) {
        public Source {
            if(url==null||url.isBlank()||url.length()>2048||!url.equals(url.trim())||url.chars().anyMatch(c->c<32||c==127)
                    ||evidence==null||durationMs<=0)throw error("local_consent_invalid");
        }
    }
    public record Consent(UUID owner,UUID controller,UUID terminal,String hall,String film,long revision,List<Source> sources) {
        public Consent {
            if(owner==null||controller==null||terminal==null||revision<1||sources==null||sources.isEmpty()||sources.size()>FilmCatalog.MAX_SEGMENTS)
                throw error("local_consent_invalid");
            canonical(hall);canonical(film);sources=List.copyOf(sources);
        }
        String key(){return hall+"/"+film+"/"+revision;}
    }
    private record Saved(int version,List<Consent> consents){}
    private final LinkedHashMap<String,Consent> consents=new LinkedHashMap<>();
    private static CatalogException error(String key){return new CatalogException(key);}
    private static void canonical(String value){try{if(value==null||!UUID.fromString(value).toString().equals(value))throw error("local_consent_invalid");}catch(IllegalArgumentException e){throw error("local_consent_invalid");}}
    private static List<Source> sources(FilmCatalog.Film film){
        return film.segments().stream().map(s->{
            var o=s.observation();if(o==null||o.live()||o.durationMs()<=0||o.provenance().evidenceId()==null)throw error("owner_evidence_required");
            return new Source(s.sourceKey(),UUID.fromString(o.provenance().evidenceId()),o.durationMs());
        }).toList();
    }
    public void authorize(UUID owner,UUID controller,UUID terminal,String hall,FilmCatalog.Film film){
        Consent value=new Consent(owner,controller,terminal,hall,film.id(),film.revision(),sources(film));
        var next=new LinkedHashMap<>(consents);next.put(value.key(),value);
        encode(next.values());consents.clear();consents.putAll(next);
    }
    public boolean permits(UUID owner,UUID controller,UUID terminal,String hall,FilmCatalog.Film film,String source){
        try{
            Consent saved=consents.get(hall+"/"+film.id()+"/"+film.revision());
            return saved!=null&&saved.owner().equals(owner)&&saved.controller().equals(controller)&&saved.terminal().equals(terminal)
                    &&saved.sources().equals(sources(film))&&saved.sources().stream().anyMatch(s->s.url().equals(source));
        }catch(RuntimeException malformed){return false;}
    }
    public int revokeHall(String hall){canonical(hall);int before=consents.size();consents.values().removeIf(c->c.hall().equals(hall));return before-consents.size();}
    public int size(){return consents.size();}
    public void clear(){consents.clear();}
    public String save(){return encode(consents.values());}
    private static String encode(Collection<Consent> values){
        if(values.size()>MAX_RECORDS)throw error("local_consent_limit");
        String json=JSON.toJson(new Saved(1,List.copyOf(values)));
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw error("local_consent_limit");return json;
    }
    public static LocalProgrammeConsents load(String json){
        var result=new LocalProgrammeConsents();if(json==null||json.isBlank())return result;
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw error("local_consent_limit");
        Saved saved=JSON.fromJson(json,Saved.class);
        if(saved==null||saved.version()!=1||saved.consents()==null||saved.consents().size()>MAX_RECORDS)throw error("local_consent_invalid");
        for(Consent c:saved.consents())if(c==null||result.consents.putIfAbsent(c.key(),c)!=null)throw error("local_consent_invalid");
        result.save();return result;
    }
}
