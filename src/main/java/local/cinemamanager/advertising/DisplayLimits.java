package local.cinemamanager.advertising;
import net.neoforged.neoforge.common.ModConfigSpec;
public final class DisplayLimits {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue PANEL_SIDE,PANEL_AREA,AD_SIDE;
    static{var b=new ModConfigSpec.Builder();b.push("displays");PANEL_SIDE=b.comment("Maximum rectangular information-screen side in blocks; no chunks are loaded.").defineInRange("informationMaxSide",16,1,32);PANEL_AREA=b.comment("Maximum information-screen rectangle area in blocks.").defineInRange("informationMaxArea",256,1,1024);AD_SIDE=b.comment("Maximum advertising side in whole blocks; editable steps remain 1/8 block.").defineInRange("advertisingMaxSide",16,1,16);b.pop();SPEC=b.build();}
    public static int panelSide(){return SPEC.isLoaded()?PANEL_SIDE.get():16;}public static int panelArea(){return SPEC.isLoaded()?PANEL_AREA.get():256;}public static int adSide(){return SPEC.isLoaded()?AD_SIDE.get():16;}
}
