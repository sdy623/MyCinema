package local.cinemamanager.client.seats;

import java.nio.charset.StandardCharsets;
import java.util.*;
import local.cinemamanager.seats.SeatRegistry;
import local.cinemamanager.seats.map.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import static local.cinemamanager.seats.map.PublicSeatMap.*;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;

/** Read-only actual-coordinate map. Local navigation opens it; packets never open screens. */
public final class SeatMapScreen extends Screen {
    private final Screen parent;
    private final BlockPos source;
    private final String identity,hall;
    private final SeatMapAssembly assembly=new SeatMapAssembly();
    private String request="",snapshot="",floor="",zone="",notice="mycinema.seats.map.loading";
    private boolean started,configured,pendingConfigured,closed;
    private int age,sentAt,left,top,right,bottom,selected=-1,detailScroll;
    private double scale=1,centerX,centerZ;
    private Button floorButton,zoneButton;
    private SeatMapScreen(Screen parent,BlockPos source,String identity,String hall){super(tr("title"));this.parent=parent;this.source=source.immutable();this.identity=identity;this.hall=hall;}
    public static void open(Screen parent,BlockPos source,String identity,String hall){Minecraft.getInstance().setScreen(new SeatMapScreen(parent,source,identity,hall));}
    private static Component tr(String key,Object... args){return Component.translatable("mycinema.seats.map."+key,args);}
    @Override protected void init(){
        left=8;right=width-8;top=62;bottom=Math.max(top+24,height-92);
        int w=Math.max(28,(width-24)/5);
        button(tr("refresh"),8,23,w,this::refresh);
        button(tr("fit"),12+w,23,w,this::fit);
        button(Component.literal("−"),16+w*2,23,w,()->zoom(1/1.4));
        button(Component.literal("+"),20+w*3,23,w,()->zoom(1.4));
        button(tr("back"),24+w*4,23,Math.max(20,width-32-w*4),this::onClose);
        floorButton=button(tr("all_floors"),8,43,(width-20)/2,this::cycleFloor);
        zoneButton=button(tr("all_zones"),12+(width-20)/2,43,(width-20)/2,this::cycleZone);
        updateFilters();if(!started){started=true;refresh();}
    }
    private Button button(Component text,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(text,b->action.run()).bounds(x,y,w,18).build());}
    private SeatMapNetwork.Args args(){SeatMapNetwork.Args args=new SeatMapNetwork.Args();args.hall=hall;args.floorKey=floor;args.zoneKey=zone;args.snapshot=snapshot;args.index=assembly.nextIndex();return args;}
    private void send(String action){if(Minecraft.getInstance().getConnection()==null)return;PacketDistributor.sendToServer(new SeatMapNetwork.Request(source,identity,request,action,SeatRegistry.JSON.toJson(args())));sentAt=age;}
    private void cancel(){if(!request.isEmpty()){send("cancel");request="";}}
    private void refresh(){cancel();assembly.begin();snapshot="";request=UUID.randomUUID().toString();notice="mycinema.seats.map.loading";send("open");updateFilters();}
    public static void receive(SeatMapNetwork.View packet){
        if(!(Minecraft.getInstance().screen instanceof SeatMapScreen screen)||screen.closed||screen.request.isEmpty()
                ||!screen.source.equals(packet.source())||!screen.identity.equals(packet.identity())||!screen.request.equals(packet.request()))return;
        try{
            int bytes=packet.json().getBytes(StandardCharsets.UTF_8).length;
            if(bytes>SeatMapAssembly.MAX_PACKET_BYTES)throw new IllegalArgumentException("wire_limit");
            SeatMapWire.Data data=SeatMapWire.decode(packet.json());
            if(data==null||!"ok".equals(data.status())||data.page()==null){screen.fail(data==null?"mycinema.seats.map.invalid":data.notice());return;}
            if(screen.assembly.nextIndex()>0&&screen.pendingConfigured!=data.configured())throw new IllegalArgumentException("mixed_configuration");
            screen.pendingConfigured=data.configured();screen.snapshot=data.page().header().snapshotId();
            if(screen.assembly.accept(data.page(),bytes)){
                screen.configured=screen.pendingConfigured;screen.notice=data.notice()==null?"":data.notice();screen.cancel();screen.selected=-1;screen.detailScroll=0;screen.fit();screen.updateFilters();
            }else screen.send("page");
        }catch(RuntimeException invalid){screen.fail("mycinema.seats.map.invalid");}
    }
    private void fail(String key){cancel();assembly.fail();notice=key==null||key.isBlank()?"mycinema.seats.map.invalid":key;var last=assembly.complete();if(last!=null){floor=last.header().floorKey();zone=last.header().zoneKey();}updateFilters();}
    private void updateFilters(){
        if(floorButton==null)return;var view=assembly.complete();
        floorButton.setMessage(view==null||floor.isEmpty()?tr("all_floors"):Component.literal(view.header().floors().stream().filter(f->f.displayKey().equals(floor)).map(FloorView::label).findFirst().orElse("?")));
        zoneButton.setMessage(view==null||zone.isEmpty()?tr("all_zones"):Component.literal(view.header().zones().stream().filter(z->z.displayKey().equals(zone)).map(ZoneView::label).findFirst().orElse("?")));
        floorButton.active=view!=null&&!view.header().floors().isEmpty()&&request.isEmpty();
        zoneButton.active=view!=null&&!floor.isEmpty()&&!view.header().zones().isEmpty()&&request.isEmpty();
    }
    private void cycleFloor(){var view=assembly.complete();if(view==null)return;List<String> ids=new ArrayList<>();ids.add("");view.header().floors().forEach(f->ids.add(f.displayKey()));floor=ids.get((ids.indexOf(floor)+1)%ids.size());zone="";refresh();}
    private void cycleZone(){var view=assembly.complete();if(view==null)return;List<String> ids=new ArrayList<>();ids.add("");view.header().zones().stream().filter(z->z.floorKey().equals(floor)).forEach(z->ids.add(z.displayKey()));zone=ids.get((ids.indexOf(zone)+1)%ids.size());refresh();}
    private void fit(){var view=assembly.complete();if(view==null)return;var b=view.header().frame().bounds();centerX=(b.minU()+b.maxU())/2;centerZ=(b.minV()+b.maxV())/2;scale=Math.max(.001,Math.min((right-left-24)/(b.maxU()-b.minU()),(bottom-top-24)/(b.maxV()-b.minV())));}
    private void zoom(double amount){scale=Math.max(.001,Math.min(4096,scale*amount));}
    private double px(Point p){return (left+right)/2.0+(p.x()-centerX)*scale;}
    private double py(Point p){return (top+bottom)/2.0+(p.z()-centerZ)*scale;}
    private boolean inMap(double x,double y){return x>=left&&x<right&&y>=top&&y<bottom;}
    private void polygon(GuiGraphics g,List<Point> points,int color){for(int i=0;i<points.size();i++)line(g,px(points.get(i)),py(points.get(i)),px(points.get((i+1)%points.size())),py(points.get((i+1)%points.size())),color);}
    /** Clip before rasterizing: extreme zoom and large authored boundaries have bounded work. */
    private void line(GuiGraphics g,double x,double y,double x2,double y2,int color){
        double dx=x2-x,dy=y2-y,t0=0,t1=1;double[] p={-dx,dx,-dy,dy},q={x-left,right-1-x,y-top,bottom-1-y};
        for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return;}else{double t=q[i]/p[i];if(p[i]<0)t0=Math.max(t0,t);else t1=Math.min(t1,t);}}if(t0>t1)return;
        double ax=x+dx*t0,ay=y+dy*t0,bx=x+dx*t1,by=y+dy*t1;int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(bx-ax),Math.abs(by-ay))));
        for(int i=0;i<=steps;i++){int sx=(int)Math.round(ax+(bx-ax)*i/steps),sy=(int)Math.round(ay+(by-ay)*i/steps);g.fill(sx,sy,sx+1,sy+1,color);}
    }
    private static int fill(SeatView seat){return switch(seat.availability().serviceability()){case AVAILABLE->0xff54869a;case MAINTENANCE->0xffc68f33;case DISABLED->0xff75495f;};}
    private static int proof(SeatView seat){return seat.availability().binding()==SeatMapSnapshot.ProofStatus.VALID?0xffbfe2d1:0xffff6666;}
    private String label(SeatView seat){return tr("seat",seat.rowLabel(),seat.number(),seat.zone()).getString();}
    private List<Component> details(){
        List<Component> lines=new ArrayList<>();lines.add(tr("legend_service"));lines.add(tr("legend_physical"));lines.add(tr("legend_occupancy"));lines.add(tr("tickets_unknown"));lines.add(tr("keys"));
        var view=assembly.complete();if(view!=null&&selected>=0&&selected<view.seats().size()){
            var s=view.seats().get(selected);lines.add(0,tr("detail",label(s),tr("binding."+s.availability().binding()).getString(),Component.translatable("mycinema.seats.status."+s.availability().serviceability()).getString(),tr("occupancy."+s.availability().occupancy()).getString()));
            lines.add(1,tr("coordinates",String.format(Locale.ROOT,"%.2f",s.point().x()),String.format(Locale.ROOT,"%.2f",s.point().z()),tr("orientation."+view.header().frame().orientation()).getString()));
        }return lines;
    }
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float partial){renderTransparentBackground(g);g.fill(0,0,width,height,0xf014202b);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        super.render(g,mx,my,partial);g.drawString(font,title,8,5,0xffffff,false);
        String state=assembly.stale()&&assembly.complete()!=null?tr("stale").getString()+" · "+Component.translatable(notice).getString():Component.translatable(notice).getString();
        g.drawString(font,font.plainSubstrByWidth(state,width-16),8,14,0xffcc77,false);
        g.fill(left,top,right,bottom,0xff0a1119);var view=assembly.complete();
        if(view!=null){g.enableScissor(left,top,right,bottom);
            for(var z:view.header().zones()){polygon(g,z.boundary(),0xff485c80);if(!z.boundary().isEmpty())g.drawString(font,z.label(),(int)px(z.boundary().getFirst()),(int)py(z.boundary().getFirst()),0xff91a7d2,false);}
            for(var a:view.header().aisles()){polygon(g,a.boundary(),0xff888888);if(!a.boundary().isEmpty())g.drawString(font,a.label(),(int)px(a.boundary().getFirst()),(int)py(a.boundary().getFirst()),0xffaaaaaa,false);}
            for(var e:view.header().entrances()){int x=(int)px(e.point()),y=(int)py(e.point());g.fill(x-3,y-3,x+4,y+4,0xff65d9b4);g.drawString(font,e.label(),x+5,y,0xff65d9b4,false);}
            int radius=(int)Math.max(2,Math.min(12,scale*view.header().frame().scale()*.3));
            for(int i=0;i<view.seats().size();i++){var s=view.seats().get(i);double sx=px(s.point()),sy=py(s.point());if(sx<left-30||sx>right+30||sy<top-30||sy>bottom+30)continue;int x=(int)sx,y=(int)sy;
                g.fill(x-radius-1,y-radius-1,x+radius+2,y+radius+2,i==selected?0xffffffff:proof(s));g.fill(x-radius,y-radius,x+radius+1,y+radius+1,fill(s));
                if(s.availability().occupancy()==SeatMapSnapshot.Occupancy.OCCUPIED)g.fill(x-1,y-1,x+2,y+2,0xff111111);
                else if(s.availability().occupancy()==SeatMapSnapshot.Occupancy.UNKNOWN)g.drawString(font,"?",x-2,y-3,0xffffffff,false);
                if(s.facing()!=null){double len=Math.hypot(s.facing().x(),s.facing().z());if(len>0)line(g,x,y,x+s.facing().x()/len*(radius+4),y+s.facing().z()/len*(radius+4),0xffffffff);}
                if(scale*view.header().frame().scale()>=32)g.drawString(font,label(s),x+radius+4,y-4,0xffe1e9ed,false);
            }g.disableScissor();
            String warning=!view.header().overlaps().isEmpty()?tr("overlap").getString():!configured?tr("unconfigured").getString():"";
            g.drawString(font,font.plainSubstrByWidth(warning,width-16),8,bottom+2,0xffcc77,false);
        }
        List<net.minecraft.util.FormattedCharSequence> lines=new ArrayList<>();for(Component line:details())lines.addAll(font.split(line,Math.max(40,width-30)));
        int detailTop=bottom+14,visible=Math.max(1,(height-detailTop-3)/font.lineHeight);detailScroll=Math.max(0,Math.min(detailScroll,Math.max(0,lines.size()-visible)));
        for(int i=0;i<visible&&i+detailScroll<lines.size();i++)g.drawString(font,lines.get(i+detailScroll),8,detailTop+i*font.lineHeight,0xffc6d0dc,false);
        if(lines.size()>visible)g.drawString(font,"↕",width-15,detailTop,0xffffffff,false);
        if(my>=13&&my<23&&!state.isEmpty())g.renderTooltip(font,font.split(Component.literal(state),Math.max(100,width-30)),mx,my);
        if(inMap(mx,my)&&view!=null){int hover=hit(mx,my);if(hover>=0){var s=view.seats().get(hover);g.renderTooltip(font,font.split(Component.literal(label(s)).append("\n").append(tr("binding."+s.availability().binding())).append(" · ").append(Component.translatable("mycinema.seats.status."+s.availability().serviceability())).append(" · ").append(tr("occupancy."+s.availability().occupancy())),Math.max(100,width-30)),mx,my);}}
    }
    private int hit(double x,double y){var view=assembly.complete();if(view==null)return -1;int best=-1;double distance=100;for(int i=0;i<view.seats().size();i++){var p=view.seats().get(i).point();double d=Math.pow(px(p)-x,2)+Math.pow(py(p)-y,2);if(d<distance){distance=d;best=i;}}return best;}
    @Override public boolean mouseClicked(double x,double y,int button){if(button==0&&inMap(x,y)){selected=hit(x,y);detailScroll=0;return true;}return super.mouseClicked(x,y,button);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if((button==0||button==1)&&inMap(x,y)){centerX-=dx/scale;centerZ-=dy/scale;return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(inMap(x,y)){zoom(Math.pow(1.2,vertical));return true;}if(y>=bottom){detailScroll-= (int)Math.signum(vertical)*3;return true;}return super.mouseScrolled(x,y,horizontal,vertical);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        switch(key){case GLFW.GLFW_KEY_LEFT->centerX-=20/scale;case GLFW.GLFW_KEY_RIGHT->centerX+=20/scale;case GLFW.GLFW_KEY_UP->centerZ-=20/scale;case GLFW.GLFW_KEY_DOWN->centerZ+=20/scale;
            case GLFW.GLFW_KEY_HOME->fit();case GLFW.GLFW_KEY_EQUAL,GLFW.GLFW_KEY_KP_ADD->zoom(1.4);case GLFW.GLFW_KEY_MINUS,GLFW.GLFW_KEY_KP_SUBTRACT->zoom(1/1.4);
            case GLFW.GLFW_KEY_PAGE_UP->detailScroll-=3;case GLFW.GLFW_KEY_PAGE_DOWN->detailScroll+=3;
            case GLFW.GLFW_KEY_LEFT_BRACKET,GLFW.GLFW_KEY_RIGHT_BRACKET->{var view=assembly.complete();if(view!=null&&!view.seats().isEmpty()){selected=Math.floorMod(selected+(key==GLFW.GLFW_KEY_RIGHT_BRACKET?1:-1),view.seats().size());var s=view.seats().get(selected);centerX=s.point().x();centerZ=s.point().z();detailScroll=0;}}
            default->{return super.keyPressed(key,scan,modifiers);}}return true;
    }
    @Override public Component getNarrationMessage(){Component text=title.copy();for(Component line:details())text=text.copy().append(". ").append(line);return text;}
    @Override public void tick(){super.tick();age++;if(!request.isEmpty()&&age-sentAt>100)fail("mycinema.seats.map.timeout");updateFilters();}
    @Override public void removed(){closed=true;cancel();super.removed();}
    @Override public void onClose(){closed=true;cancel();if(minecraft!=null)minecraft.setScreen(minecraft.getConnection()==null?null:parent);}
    @Override public boolean isPauseScreen(){return false;}
}
