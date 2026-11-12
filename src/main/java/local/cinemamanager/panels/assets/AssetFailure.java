package local.cinemamanager.panels.assets;

import java.io.IOException;

/** Safe image diagnostics: a fixed error code and bounded numbers, never file paths or free text. */
public final class AssetFailure extends IOException {
    private static final long MAX_DECLARED_AXIS=0xffff_ffffL;
    private final long width,height;
    private AssetFailure(long width,long height){super("asset_dimensions");this.width=width;this.height=height;}
    public long width(){return width;}
    public long height(){return height;}
    public int limit(){return 8192;}
    static AssetFailure dimensions(int width,int height){return new AssetFailure(Integer.toUnsignedLong(width),Integer.toUnsignedLong(height));}
    private String wireDetail(){return width+"x"+height+"/"+limit();}
    static String detail(Throwable error){return error instanceof AssetFailure failure?failure.wireDetail():"";}
    /** Failure-only use of Reply.reference. Malformed or unrelated details are never reflected. */
    public static IOException fromWire(String code,String detail){
        String safe=code!=null&&code.matches("asset_[a-z_]{1,32}")?code:"asset_storage_unavailable";
        if(safe.equals("asset_dimensions")&&detail!=null&&detail.matches("[0-9]{1,10}x[0-9]{1,10}/8192")){
            int split=detail.indexOf('x'),slash=detail.indexOf('/');
            try{
                long width=Long.parseLong(detail.substring(0,split)),height=Long.parseLong(detail.substring(split+1,slash));
                AssetFailure failure=new AssetFailure(width,height);
                if(width<=MAX_DECLARED_AXIS&&height<=MAX_DECLARED_AXIS&&failure.wireDetail().equals(detail))return failure;
            }catch(NumberFormatException ignored){}
        }
        return new IOException(safe);
    }
}
