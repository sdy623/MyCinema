package local.cinemamanager.seats.fractional;

/** New-history recovery: explicit immutable source identities, never an automatic mesh migration. */
public enum FractionalSeatGeometryProfile {
    ORIGINAL("ca470795c3ab0a30672c59fcbcf5f0d0e5570c87e6688d4d8bdb57fb23f2fd7d",1.148042350217411,.671431530840533),
    HIGHBACK_150("b4f49860b1c87f9656873a00dfe8591ce182c00f42fb04d8d900759f4dd4ae31",1.5,.8172171627614413);
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) { }
    private final String hash;
    private final Bounds bounds;
    FractionalSeatGeometryProfile(String hash,double height,double back) {
        this.hash=hash;this.bounds=new Bounds(-.5375,0,-.525,.5375,height,back);
    }
    public String modelSha256(){return hash;}
    public Bounds canonicalBounds(){return bounds;}
    public static FractionalSeatGeometryProfile fromHash(String value){
        for(var profile:values())if(profile.hash.equals(value))return profile;
        throw new IllegalArgumentException("Unknown chair geometry hash");
    }
    public Bounds facingBounds(int turns){
        return switch(turns){
            case 0 -> bounds;
            case 1 -> new Bounds(-bounds.maxZ(),0,bounds.minX(),-bounds.minZ(),bounds.maxY(),bounds.maxX());
            case 2 -> new Bounds(-bounds.maxX(),0,-bounds.maxZ(),-bounds.minX(),bounds.maxY(),-bounds.minZ());
            case 3 -> new Bounds(bounds.minZ(),0,-bounds.maxX(),bounds.maxZ(),bounds.maxY(),-bounds.minX());
            default -> throw new IllegalArgumentException("Invalid quarter-turn");
        };
    }
    public boolean validCell(FractionalSeatData.Anchor a,int x,int y,int z){
        Bounds b=facingBounds(a.turns());double e=1e-9;
        return x>=Math.floor(a.x()+b.minX()-e)&&x<=Math.floor(a.x()+b.maxX()+e)
            &&y>=Math.floor(a.y())&&y<=Math.floor(a.y()+b.maxY()+e)
            &&z>=Math.floor(a.z()+b.minZ()-e)&&z<=Math.floor(a.z()+b.maxZ()+e);
    }
}
