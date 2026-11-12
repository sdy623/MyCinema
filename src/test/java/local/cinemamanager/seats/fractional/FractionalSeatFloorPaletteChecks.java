package local.cinemamanager.seats.fractional;
import net.minecraft.nbt.CompoundTag;
public final class FractionalSeatFloorPaletteChecks {
    static int checks;
    static void check(boolean b) { if(!b) throw new AssertionError(); checks++; }
    static CompoundTag good() { var t=new CompoundTag();t.putInt("schema_version",1);t.putString("profile","wool_deepslate_v1");t.putString("layout","selected150_372_1814");return t; }
    static void refuses(CompoundTag t) { try {FractionalSeatFloorPalette.parse(t);throw new AssertionError("Accepted malformed contract");}catch(IllegalArgumentException expected){checks++;} }
    public static void main(String[] args) {
        check(FractionalSeatFloorPalette.parse(new CompoundTag())==FractionalSeatFloorPalette.Profile.LEGACY);
        check(FractionalSeatFloorPalette.parse(good())==FractionalSeatFloorPalette.Profile.WOOL_DEEPSLATE_V1);
        var t=good();t.putString("profile","wool_surfaces_v2");check(FractionalSeatFloorPalette.parse(t)==FractionalSeatFloorPalette.Profile.WOOL_SURFACES_V2);
        t=good();t.putString("profile","arbitrary_slab");refuses(t);
        t=good();t.putInt("schema_version",2);refuses(t);
        t=good();t.putString("schema_version","1");refuses(t);
        t=good();t.putString("layout","other_world");refuses(t);
        t=good();t.remove("layout");refuses(t);
        t=good();t.putBoolean("allow_any_full_cube",true);refuses(t);
        t=good();t.putString("profile","legacy");refuses(t);
        check(FractionalSeatFloorPalette.parse(new CompoundTag())==FractionalSeatFloorPalette.Profile.LEGACY);
        System.out.println("PASS "+checks+" explicit profile selection and malformed-contract rejection checks");
    }
}
