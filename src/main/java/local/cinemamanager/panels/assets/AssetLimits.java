package local.cinemamanager.panels.assets;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server resource limits. No HTTP service or publicly reachable file endpoint is created. */
public final class AssetLimits {
    public static final int CHUNK_BYTES=32*1024, ABSOLUTE_IMAGE_BYTES=256*1024*1024;
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue IMAGE_MIB,WORLD_MIB;
    static{
        var b=new ModConfigSpec.Builder();b.push("privatePanelAssets");
        IMAGE_MIB=b.comment("Maximum compressed PNG/JPEG/WebP bytes per uploaded picture; axes are independently limited to 8192.").defineInRange("imageBudgetMiB",64,1,256);
        WORLD_MIB=b.comment("Maximum persistent picture bytes for this world. Bounded in-progress uploads also reserve this quota.","Existing immutable pictures are never automatically removed when the limit is lowered.").defineInRange("worldBudgetMiB",512,1,65536);
        b.pop();SPEC=b.build();
    }
    public static int imageBytes(){return (SPEC.isLoaded()?IMAGE_MIB.get():64)*1024*1024;}
    public static long worldBytes(){return (SPEC.isLoaded()?WORLD_MIB.get():512)*1024L*1024;}
    private AssetLimits(){}
}
