package local.cinemamanager.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Visible client resource policy. These are budgets, not accepted source dimensions. */
public final class PosterResources {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue INPUT_MIB, WORK_MIB, TEXTURE_SIDE, TEXTURE_MIB;
    static {
        var b = new ModConfigSpec.Builder();
        b.push("posters");
        INPUT_MIB = b.comment("Maximum compressed poster input per request, in MiB. The former fixed 8 MiB ceiling is removed.")
                .defineInRange("downloadBudgetMiB", 64, 16, 256);
        WORK_MIB = b.comment("Maximum estimated decode working memory, in MiB; effective budget also respects the current JVM heap.",
                "Large lossless WebP may need a higher budget and a larger JVM heap even when dimensions fit 8192.")
                .defineInRange("decodeBudgetMiB", 256, 64, 768);
        TEXTURE_SIDE = b.comment("Poster GPU texture edge target. Source images may be up to 8192 on each axis, with any aspect ratio.",
                "Textures are proportionally reduced to this target, the pixel budget and the GPU's actual texture limit.")
                .defineInRange("textureMaxSide", 2048, 512, 8192);
        TEXTURE_MIB = b.comment("Maximum RGBA bytes per poster texture, in MiB. At most four textures are cached.")
                .defineInRange("textureBudgetMiB", 16, 4, 64);
        b.pop(); SPEC = b.build();
    }
    private PosterResources() {}
    private static int value(ModConfigSpec.IntValue v, int fallback) { return SPEC.isLoaded() ? v.get() : fallback; }
    public static int inputBytes() { return value(INPUT_MIB,64) * 1024 * 1024; }
    public static long workBytes() { return value(WORK_MIB,256) * 1024L * 1024; }
    public static int textureSide() { return value(TEXTURE_SIDE,2048); }
    public static long textureBytes() { return value(TEXTURE_MIB,16) * 1024L * 1024; }
}
