package local.cinemamanager;

import local.cinemamanager.panels.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import java.nio.file.*;
import me.srrapero720.waterui.format.*;
import net.minecraft.resources.ResourceLocation;

public final class PanelChecks {
    private static final Set<String> GENERIC_PROPERTIES=Set.of("id","width","height","weight","margin","padding","border","align","justify","anchor","outsideAnchor","shadow","enabled","visible","hidden","elevation","scale","tooltip","face","outline","scrollX","scrollY");
    static int n;
    static void check(boolean c,String s){n++;if(!c)throw new AssertionError(s);}
    static void rejects(Runnable r,String s){try{r.run();throw new AssertionError(s);}catch(IllegalArgumentException e){n++;}}
    public static void main(String[] args)throws Exception{
        PanelSettings p=new PanelSettings(PanelSettings.Layout.POSTER_TOP,"Hall 1","Title","20:00–22:00","Next film","Footer","resource:minecraft:textures/block/stone.png",0xffffff,0,0x123456,160,63);
        check(PanelSettings.read(p.save()).equals(p),"actual NBT roundtrip");check(PanelSettings.savedOrDefault(new CompoundTag()).equals(PanelSettings.DEFAULT),"old saved panel defaults");
        rejects(()->new PanelSettings(p.layout(),"x".repeat(81),p.title(),p.time(),p.next(),p.footer(),p.poster(),p.foreground(),p.background(),p.accent(),p.textPercent(),p.visible()),"hall length bounded");
        rejects(()->new PanelSettings(p.layout(),"","","","","","",0,0,0,59,63),"unreadable scale rejected");
        rejects(()->new PanelSettings(p.layout(),"","","","","","",0,0,0,100,256),"undefined flags rejected");
        check(PanelSettings.color("#66eEeE")==0x66eeee,"mixed case colour");rejects(()->PanelSettings.color("#ffffff00"),"alpha injection rejected");
        for(String s:List.of("https://example.com/poster.png","http://127.0.0.1:8080/poster.jpg","file:/tmp/poster%20one.png","media/poster.webp","resource:minecraft:textures/block/stone.png","resource:mycinema:textures/poster.jpeg","resource:mycinema:textures/poster_without_extension"))check(PosterPolicy.valid(s),"supported poster source "+s);
        for(String s:List.of("https://name:password@example.com/a.png","file://remote/a.png","jar:file:/x!/a.png","water://a","resource:minecraft:textures/../a.png","resource:minecraft:models/a.json","x".repeat(2049)))check(!PosterPolicy.valid(s),"invalid poster source "+s.substring(0,Math.min(60,s.length())));
        for(int width:List.of(1,2,8,16,32))for(int height=1;height<=32;height++)for(int scale:List.of(60,100,160))for(var layout:PanelSettings.Layout.values()){
            float w=(width-.08f)/(.0064f*scale/100),h=(height-.08f)/(.0064f*scale/100);var areas=PanelLayout.areas(w,h,layout);
            for(var rect:List.of(areas.text(),areas.poster()))check(rect.x()>=0&&rect.y()>=0&&rect.x()+rect.width()<=w+.001&&rect.y()+rect.height()<=h+.001,"layout stays within actual panel");
            if(layout!=PanelSettings.Layout.TEXT)for(int[] size:List.of(new int[]{1000,1500},new int[]{2048,1},new int[]{1,2048})){var fit=PanelLayout.contain(areas.poster(),size[0],size[1]);check(fit.x()>=areas.poster().x()-.001&&fit.y()>=areas.poster().y()-.001&&fit.x()+fit.width()<=areas.poster().x()+areas.poster().width()+.001&&fit.y()+fit.height()<=areas.poster().y()+areas.poster().height()+.001,"poster containment");check(Math.abs(fit.width()/fit.height()-(float)size[0]/size[1])<.02,"poster preserves aspect");}
        }
        for(String odd:List.of("&","<","\\","\"","\u2028","\u0001")){
            var maximum=new PanelSettings(PanelSettings.Layout.POSTER_LEFT,odd.repeat(80),odd.repeat(160),odd.repeat(120),odd.repeat(240),odd.repeat(240),odd.repeat(2048),0xffffff,0xffffff,0xffffff,160,63);
            String json=PanelNetwork.JSON.toJson(maximum);check(PanelNetwork.JSON.fromJson(json,PanelSettings.class).equals(maximum),"maximum escaped settings roundtrip");
            var request=new PanelNetwork.Edit(BlockPos.ZERO,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"save",PanelNetwork.JSON.toJson(new PanelNetwork.Configuration(maximum,PanelScope.HALL)),UUID.randomUUID().toString());var buffer=io.netty.buffer.Unpooled.buffer();PanelNetwork.Edit.CODEC.encode(buffer,request);check(PanelNetwork.Edit.CODEC.decode(buffer).equals(request),"bounded legal Edit codec");buffer.release();
            var view=new PanelNetwork.View(BlockPos.ZERO,request.identity(),request.signature(),PanelNetwork.JSON.toJson(new PanelNetwork.Data(maximum,List.of(odd.repeat(1000),odd.repeat(1000),odd.repeat(1000),odd.repeat(1000),odd.repeat(1000),odd.repeat(1000)),8,true,"",PanelScope.HALL)),request.request(),false);buffer=io.netty.buffer.Unpooled.buffer();PanelNetwork.View.CODEC.encode(buffer,view);check(PanelNetwork.View.CODEC.decode(buffer).equals(view),"bounded legal View codec");buffer.release();
        }
        UIRegistry.bootstrap();
        for(int i=0;i<3;i++){String name="src/main/resources/assets/mycinema/ui/panel_"+i+".ui";var doc=UIParser.parse(UILexer.tokenize(Files.readString(Path.of(name)),name),ResourceLocation.fromNamespaceAndPath("mycinema","panel_"+i));check(doc!=null,"panel UI parsed "+i);schema(doc,doc.tree());}
        String ad="src/main/resources/assets/mycinema/ui/advertising.ui";var adDoc=UIParser.parse(UILexer.tokenize(Files.readString(Path.of(ad)),ad),ResourceLocation.fromNamespaceAndPath("mycinema","advertising"));check(adDoc!=null,"advertising UI parsed");schema(adDoc,adDoc.tree());
        PanelLayoutStyleChecks.run();
        GuestPanelChecks.run();
        ActualProgrammeGuestChecks.run();
        local.cinemamanager.client.PanelHeaderChecks.run();
        PosterPolicyChecks.run();
        System.out.println("PASS: "+n+" panel checks: settings/URI, NBT migration, real pixel-area layout/aspect bounds, registered UI schema.");
    }
    static void schema(UIDocument doc,List<UINode> nodes){for(UINode node:nodes){check(UIRegistry.factory(node.tag())!=null,"registered "+node.tag());for(String prop:node.props().keySet())check(GENERIC_PROPERTIES.contains(prop)||UIRegistry.tagProperty(node.tag(),prop)!=null,"registered property "+node.tag()+"."+prop);for(var entry:node.props().entrySet())if(entry.getValue() instanceof UIValue.Id id&&Set.of("heading","editable","can_reload","notice","preview","layout","scope","hall_editable","dimensions","step_label").contains(id.name()))check(UIRegistry.binding(node.tag(),entry.getKey())!=null,"real inflater live binding "+node.tag()+"."+entry.getKey());schema(doc,node.children());}}
}
