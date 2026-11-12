package local.cinemamanager.seats.fractional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
public final class FractionalSeatLayoutVersionChecks {
    static int checks;
    static void check(boolean b) { if(!b) throw new AssertionError("check " + checks); checks++; }
    static CompoundTag marker(String profile, String layout) { var t=new CompoundTag();t.putInt("schema_version",1);t.putString("profile",profile);t.putString("layout",layout);return t; }
    static CompoundTag smooth() { return marker("wool_smooth_y_v3","selected150_y_smooth_v1"); }
    static void refuses(CompoundTag t) { try {FractionalSeatLayoutVersion.parse(t);throw new AssertionError("Accepted malformed contract");}catch(IllegalArgumentException expected){checks++;} }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var original=FractionalSeatLayoutVersion.Version.ORIGINAL;var smooth=FractionalSeatLayoutVersion.Version.Y_SMOOTH_V1;
        check(FractionalSeatLayoutVersion.parse(new CompoundTag())==original);
        for(String p:new String[]{"wool_deepslate_v1","wool_surfaces_v2"}) check(FractionalSeatLayoutVersion.parse(marker(p,"selected150_372_1814"))==original);
        check(FractionalSeatLayoutVersion.parse(smooth())==smooth);
        refuses(marker("wool_smooth_y_v3","selected150_372_1814"));
        refuses(marker("wool_surfaces_v2","selected150_y_smooth_v1"));
        refuses(marker("unknown","selected150_y_smooth_v1"));
        var t=smooth(); t.putInt("schema_version",2); refuses(t);
        t=smooth(); t.putString("schema_version","1"); refuses(t);
        t=smooth(); t.remove("layout"); refuses(t);
        t=smooth(); t.putInt("layout",1); refuses(t);
        t=smooth(); t.putBoolean("allow_any_host",true); refuses(t);
        var saved=new CompoundTag();
        check(FractionalSeatIntegration.savedLayoutMatches(saved,original));
        check(!FractionalSeatIntegration.savedLayoutMatches(saved,smooth));
        saved.putString("layout",smooth.id);check(FractionalSeatIntegration.savedLayoutMatches(saved,smooth));check(!FractionalSeatIntegration.savedLayoutMatches(saved,original));
        saved.putInt("layout",1);check(!FractionalSeatIntegration.savedLayoutMatches(saved,original));check(!FractionalSeatIntegration.savedLayoutMatches(saved,smooth));
        var old=FractionalSeatLayout.get(original); var fresh=FractionalSeatLayout.get(smooth);
        check(!old.isSmoothed());check(fresh.isSmoothed());check(old.cells.size()==1814);check(fresh.cells.size()==1624);
        check(old.seats.size()==372);check(fresh.seats.size()==372);check(old.seats.keySet().equals(fresh.seats.keySet()));check(old.labels.keySet().equals(fresh.labels.keySet()));
        check(old==FractionalSeatLayout.get(original));check(fresh==FractionalSeatLayout.get(smooth));
        check(old.labels.get("A01").anchor().equals(fresh.labels.get("A01").anchor()));
        check(!old.labels.get("L01").anchor().equals(fresh.labels.get("L01").anchor()));
        for(var uid:old.seats.keySet()) check(old.seats.get(uid).label().equals(fresh.seats.get(uid).label()));
        System.out.println("PASS "+checks+" exact world dispatch, session compatibility, immutable cache, resource identity checks");
    }
}
