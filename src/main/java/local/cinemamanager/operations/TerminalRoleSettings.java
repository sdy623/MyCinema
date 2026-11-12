package local.cinemamanager.operations;

import com.google.gson.*;
import local.cinemamanager.panels.PanelScope;
import java.util.Set;

/** Strict role contract shared by saved information panels and the operations adapter. */
public record TerminalRoleSettings(int schema,Role role,View view) {
    public enum Role { AUDIENCE, OPERATIONS }
    public enum View { PROGRAMME, SEAT_MAP, DAY_SCHEDULE, OPERATIONS }
    public static final TerminalRoleSettings LEGACY=new TerminalRoleSettings(1,Role.AUDIENCE,View.PROGRAMME);
    public static final TerminalRoleSettings OPERATIONS=new TerminalRoleSettings(1,Role.OPERATIONS,View.OPERATIONS);
    private static final Set<String> INFO_BLOCKS=Set.of("mycinema:information_panel","mycinema:wall_information_panel");
    public TerminalRoleSettings {
        if(schema!=1||role==null||view==null||((role==Role.OPERATIONS)!=(view==View.OPERATIONS)))throw invalid();
    }
    public boolean validFor(String block,PanelScope scope){
        if(block==null||!INFO_BLOCKS.contains(block)||scope==null)return false;
        if(role==Role.OPERATIONS)return scope!=PanelScope.MANUAL;
        if(view==View.SEAT_MAP)return scope==PanelScope.HALL;
        if(view==View.DAY_SCHEDULE)return scope!=PanelScope.MANUAL;
        return true;
    }
    public String encode(){JsonObject json=new JsonObject();json.addProperty("schema",schema);json.addProperty("role",role.name());json.addProperty("view",view.name());return json.toString();}
    /** null alone means an absent legacy field. Malformed/newer data must be retained, not reset. */
    public static TerminalRoleSettings decode(String raw){
        if(raw==null)return LEGACY;
        try{
            if(raw.length()>1024)throw invalid();JsonObject json=JsonParser.parseString(raw).getAsJsonObject();
            if(!json.keySet().equals(Set.of("schema","role","view")))throw invalid();
            JsonElement version=json.get("schema");if(!version.isJsonPrimitive()||!version.getAsJsonPrimitive().isNumber())throw invalid();
            for(String key:Set.of("role","view"))if(!json.get(key).isJsonPrimitive()||!json.getAsJsonPrimitive(key).isString())throw invalid();
            return new TerminalRoleSettings(version.getAsBigDecimal().intValueExact(),Role.valueOf(json.get("role").getAsString()),View.valueOf(json.get("view").getAsString()));
        }catch(RuntimeException failure){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("mycinema.operations.invalid_role");}
}
