package local.cinemamanager.panels.assets;

/** Opaque, canonical content address. Never a path, URL, or authority token. */
public final class AssetReference {
    public static final String PREFIX="mycinema-asset:";
    public static String normalize(String value){
        if(value==null)return null;
        String candidate=value.trim();
        return candidate.matches("mycinema-asset:[0-9a-f]{64}")?candidate:null;
    }
    public static boolean isAsset(String value){return normalize(value)!=null;}
    public static String hash(String value){String normalized=normalize(value);return normalized==null?null:normalized.substring(PREFIX.length());}
    public static String ofHash(String hash){if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("asset_invalid_reference");return PREFIX+hash;}
    private AssetReference(){}
}
