package local.cinemamanager.client;

import java.io.IOException;

/** User-facing categories carry only safe scalar facts; never URI tokens or local paths. */
public final class PosterFailure extends IOException {
    public enum Code {
        LOCAL_MISSING, LOCAL_DENIED, HTTP_NOT_FOUND, HTTP_DENIED, HTTP_STATUS,
        TIMEOUT, NETWORK, DIMENSIONS, DOWNLOAD_BUDGET, MEMORY_BUDGET,
        UNSUPPORTED_FORMAT, CODEC_LIMIT, CORRUPT_IMAGE, REDIRECT_LOOP, REDIRECT_LIMIT,
        REDIRECT_DISALLOWED, PERMISSION, INVALID_REFERENCE, CANCELLED, GPU, UNAVAILABLE, CAPACITY, SOURCE_CHANGED, BUSY
    }
    private final Code code;
    private final Object[] args;
    public PosterFailure(Code code,Object...args){
        super(code.name());this.code=code;this.args=args.clone();
        for(Object arg:args)if(!(arg instanceof Number)&&!(arg instanceof Boolean))throw new IllegalArgumentException("Poster errors accept only safe scalar facts");
    }
    public Code code(){return code;}
    public Object[] args(){return args.clone();}
    static Code assetCode(String reason){
        return switch(reason==null?"":reason){
            case "asset_permission"->Code.PERMISSION;
            case "asset_cancelled","asset_stale"->Code.CANCELLED;
            case "asset_timeout"->Code.TIMEOUT;
            case "asset_hash_mismatch","asset_bad_length","asset_bad_sequence"->Code.CORRUPT_IMAGE;
            case "asset_invalid_reference"->Code.INVALID_REFERENCE;
            case "asset_missing"->Code.UNAVAILABLE;
            case "asset_busy"->Code.BUSY;
            default->Code.NETWORK;
        };
    }
}
