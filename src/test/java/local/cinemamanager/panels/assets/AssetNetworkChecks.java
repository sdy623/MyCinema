package local.cinemamanager.panels.assets;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.UUID;

/** Exercises the actual codecs, including allocation bounds before malicious lengths are read. */
public final class AssetNetworkChecks {
    private static int checks;
    public static void main(String[] args){
        ingress();
        filmTarget();
        String token=UUID.randomUUID().toString();byte[] bytes=new byte[32768];Arrays.fill(bytes,(byte)37);
        var buffer=Unpooled.buffer();try{AssetNetwork.UploadChunk.CODEC.encode(buffer,new AssetNetwork.UploadChunk(token,0,bytes));var decoded=AssetNetwork.UploadChunk.CODEC.decode(buffer);check(decoded.token().equals(token)&&decoded.sequence()==0&&Arrays.equals(decoded.bytes(),bytes),"32 KiB upload chunk round trip");}finally{buffer.release();}
        for(int invalid:new int[]{-1,0,32769,Integer.MAX_VALUE}){var packet=Unpooled.buffer();try{net.minecraft.network.codec.ByteBufCodecs.stringUtf8(36).encode(packet,token);packet.writeInt(0);packet.writeInt(invalid);rejects(()->AssetNetwork.DownloadChunk.CODEC.decode(packet),"malicious declared length rejected before allocation");}finally{packet.release();}}
        var truncated=Unpooled.buffer();try{net.minecraft.network.codec.ByteBufCodecs.stringUtf8(36).encode(truncated,token);truncated.writeInt(0);truncated.writeInt(32768);truncated.writeByte(1);rejects(()->AssetNetwork.UploadChunk.CODEC.decode(truncated),"truncated chunk rejected before allocation");}finally{truncated.release();}
        var tooLarge=Unpooled.buffer();try{rejects(()->AssetNetwork.UploadChunk.CODEC.encode(tooLarge,new AssetNetwork.UploadChunk(token,0,new byte[32769])),"encoder cannot produce oversized payload");}finally{tooLarge.release();}
        System.out.println("Private asset network checks passed: "+checks);
    }
    private static void filmTarget(){
        String identity=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
        var target=new AssetNetwork.FilmTarget(new net.minecraft.core.BlockPos(1,64,-3),identity,"",12,0);
        var value=new AssetNetwork.FilmBegin(target,request,1024,"a".repeat(64));
        var buffer=Unpooled.buffer();
        try{AssetNetwork.FilmBegin.CODEC.encode(buffer,value);check(value.equals(AssetNetwork.FilmBegin.CODEC.decode(buffer)),"new-film manager upload round trip retains expected revision and real manager identity");}
        finally{buffer.release();}
        var oversized=Unpooled.buffer();
        try{rejects(()->AssetNetwork.FilmTarget.CODEC.encode(oversized,new AssetNetwork.FilmTarget(target.pos(),identity,"x".repeat(37),12,1)),"film target ID is bounded independently of JSON");}
        finally{oversized.release();}
        var old=Unpooled.buffer();
        var panel=new AssetNetwork.Begin(target.pos(),identity,UUID.randomUUID().toString(),request,1024,"a".repeat(64));
        try{AssetNetwork.Begin.CODEC.encode(old,panel);check(panel.equals(AssetNetwork.Begin.CODEC.decode(old)),"existing panel upload wire format remains intact");}
        finally{old.release();}
    }
    private static void ingress(){
        var gate=new AssetIngress(2,3,4,100);Object a=new Object(),b=new Object();
        var one=gate.acquire(a,0);var two=gate.acquire(a,0);check(one!=null&&two!=null,"two queued packets admitted for one connection");
        check(gate.acquire(a,0)==null,"connection queue bound applies before any token lookup");
        var other=gate.acquire(b,0);check(other!=null&&gate.acquire(b,0)==null,"global queue bound includes independent connections");
        one.close();one.close();var replacement=gate.acquire(a,0);check(replacement!=null&&gate.acquire(b,0)==null,"permit release is exactly once");
        two.close();replacement.close();other.close();var last=gate.acquire(a,0);check(last!=null,"queue completion restores capacity");last.close();
        check(gate.acquire(a,99)==null,"completed packets still count toward ingress rate window");
        var reset=gate.acquire(a,100);check(reset!=null,"new window restores rate capacity");reset.close();
    }
    private static void rejects(Runnable action,String reason){boolean rejected=false;try{action.run();}catch(RuntimeException e){rejected=true;}check(rejected,reason);}
    private static void check(boolean okay,String reason){checks++;if(!okay)throw new AssertionError(reason);}
}
