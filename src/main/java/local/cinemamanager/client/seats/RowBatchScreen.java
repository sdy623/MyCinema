package local.cinemamanager.client.seats;

import com.google.gson.JsonParser;
import local.cinemamanager.seats.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Explicit configuration and server preview; a client never submits an invented seat list. */
public final class RowBatchScreen extends Screen {
    private SeatNetwork.View view;
    private RowBatchController.Data data;
    private final Map<String,String> draft=new HashMap<>();
    private final List<Label> labels=new ArrayList<>();
    private String hall="",request="",action="",notice="";
    private GuidePreset.RowScheme scheme=GuidePreset.RowScheme.NUMERIC;
    private int direction=1,page,ticks,sentAt,left,top,panel,panelHeight,contentHeight,scroll;
    private long revision;
    private boolean conflict;
    private record Label(String key,int x,int y){}
    private static Component tr(String key,Object... args){return Component.translatable("mycinema.seats."+key,args);}
    public RowBatchScreen(SeatNetwork.View view,RowBatchController.Data data){super(tr("batch_title"));this.view=view;this.data=data;load();}
    public static boolean receive(SeatNetwork.View packet){
        RowBatchController.Data next;
        boolean rowPacket=false;
        try{
            var json=JsonParser.parseString(packet.json()).getAsJsonObject();
            if(!json.has("kind")||!json.get("kind").getAsString().equals("row_batch"))return false;
            rowPacket=true;next=SeatRegistry.JSON.fromJson(json,RowBatchController.Data.class);
            if(next==null||next.halls()==null||next.card()==null||next.token()==null||next.notice()==null)return true;
        }catch(RuntimeException ignored){return rowPacket;}
        Minecraft mc=Minecraft.getInstance();
        if(packet.open()){if(mc.screen==null)mc.setScreen(new RowBatchScreen(packet,next));return true;}
        if(SeatsScreen.acceptsRowBatch(packet)){mc.setScreen(new RowBatchScreen(packet,next));return true;}
        if(!(mc.screen instanceof RowBatchScreen screen)||screen.request.isEmpty()||!screen.request.equals(packet.request())
                ||!screen.view.source().equals(packet.source())||!screen.view.identity().equals(packet.identity()))return true;
        String completed=screen.action;screen.request="";screen.view=packet;screen.data=next;screen.notice=next.notice();
        if(screen.notice.equals("mycinema.seats.batch_choose_first")){screen.onClose();return true;}
        if(completed.equals("row_batch_view")||screen.notice.equals("mycinema.seats.batch_applied")||screen.notice.equals("mycinema.seats.batch_exited"))screen.load();
        else if(screen.revision!=next.revision())screen.conflict=true;
        screen.rebuildWidgets();return true;
    }
    private void load(){
        draft.clear();revision=data.revision();notice=data.notice();conflict=false;page=0;scroll=0;
        RowBatch.Request settings=data.selection()==null?null:data.selection().settings();
        hall=settings==null?(data.halls().isEmpty()?"":data.halls().getFirst().id()):settings.hall();
        scheme=settings==null?GuidePreset.RowScheme.NUMERIC:settings.rowScheme();direction=settings==null?1:settings.direction();
        draft.put("zone",settings==null?"":settings.zone());draft.put("row",label(settings==null?1:settings.row(),scheme));
        draft.put("number",Integer.toString(settings==null?1:settings.start()));draft.put("end",Integer.toString(settings==null?0:settings.end()));
    }
    @Override protected void init(){
        panel=Math.min(430,width-12);contentHeight=data.preview()==null?324:336;panelHeight=Math.min(contentHeight,Math.max(120,height-12));scroll=Math.clamp(scroll,0,Math.max(0,contentHeight-panelHeight));left=(width-panel)/2;top=Math.max(3,(height-panelHeight)/2);labels.clear();
        if(data.preview()==null)configure();else preview();
        children().forEach(c->{if(c instanceof AbstractWidget w)w.visible=w.getY()>=top+22&&w.getY()+w.getHeight()<=top+panelHeight-3;});
        if(!request.isEmpty())children().forEach(c->{if(c instanceof AbstractWidget w)w.active=false;});
        addRenderableWidget(Button.builder(Component.literal("×"),b->onClose()).bounds(left+panel-23,top+2,18,18).tooltip(Tooltip.create(tr("close"))).build());
    }
    private Button button(Component title,int x,int y,int width,Runnable run){return addRenderableWidget(Button.builder(title,b->run.run()).bounds(left+x,top+y-scroll,width,18).build());}
    private Button button(String key,int x,int y,int width,Runnable run){return button(tr(key),x,y,width,run);}
    private void field(String key,int y,int max){
        labels.add(new Label(key,left+8,top+y+5-scroll));EditBox input=new EditBox(font,left+panel/2,top+y-scroll,panel/2-8,18,tr(key));
        input.setMaxLength(max);input.setValue(draft.getOrDefault(key,""));input.setResponder(v->draft.put(key,v));addRenderableWidget(input);
    }
    private String hallName(){return data.halls().stream().filter(h->h.id().equals(hall)).map(SeatNetwork.Hall::name).findFirst().orElseGet(()->tr("hall_missing").getString());}
    private void cycleHall(){if(data.halls().isEmpty())return;int at=0;for(int i=0;i<data.halls().size();i++)if(data.halls().get(i).id().equals(hall))at=i;hall=data.halls().get((at+1)%data.halls().size()).id();rebuildWidgets();}
    private boolean ready(){return data.editable()&&!conflict&&data.card()!=null&&!data.card().isEmpty()&&!data.halls().isEmpty();}
    private void configure(){
        button(Component.literal(hallName()),8,26,panel-16,this::cycleHall);
        field("zone",50,32);field("row",74,3);field("number",98,3);field("end",122,3);
        if(children().getLast() instanceof EditBox input)input.setTooltip(Tooltip.create(tr("continuous_end_hint")));
        button(tr(direction==1?"continuous_forward":"continuous_backward"),8,146,panel-16,()->{direction=-direction;rebuildWidgets();});
        button(tr("row_scheme."+scheme.name()),8,170,panel-16,()->attempt(()->{
            int row=rowIdentity(draft.get("row"),scheme);scheme=scheme==GuidePreset.RowScheme.NUMERIC?GuidePreset.RowScheme.LETTERS:GuidePreset.RowScheme.NUMERIC;draft.put("row",label(row,scheme));rebuildWidgets();
        })).setTooltip(Tooltip.create(tr("batch_row_scheme_hint")));
        button("batch_begin",8,244,panel/2-12,()->attempt(()->{
            RowBatchController.Args args=args();args.hall=hall;args.zone=draft.get("zone");args.row=rowIdentity(draft.get("row"),scheme);
            args.number=Integer.parseInt(draft.get("number"));args.end=Integer.parseInt(draft.get("end"));args.direction=direction;args.rowScheme=scheme.name();send("row_batch_start",args);
        })).active=ready();
        button("reload",panel/2+4,244,panel/2-12,()->send("row_batch_view",args())).setTooltip(Tooltip.create(tr("reload_hint")));
        button("batch_next_row",8,268,panel/2-12,()->send("row_batch_next",args())).active=ready()&&data.selection()!=null&&data.selection().settings().row()<SeatRegistry.MAX_NUMBER;
        button("batch_exit",panel/2+4,268,panel/2-12,()->send("row_batch_exit",args())).active=!data.card().isEmpty()&&data.selection()!=null;
        button("close",8,298,panel-16,this::onClose);
    }
    private void preview(){
        RowBatch.Preview preview=data.preview();int pages=Math.max(1,(preview.candidates().size()+7)/8);page=Math.clamp(page,0,pages-1);
        button(Component.literal(hallName()),8,26,panel-16,()->{}).active=false;
        for(int i=0;i<8&&page*8+i<preview.candidates().size();i++){
            RowBatch.Candidate candidate=preview.candidates().get(page*8+i);BlockPos pos=BlockPos.of(candidate.position());
            RowBatch.Assignment assignment=preview.assignments().stream().filter(a->a.position()==candidate.position()).findFirst().orElse(null);
            List<RowBatch.Issue> issues=new ArrayList<>();preview.errors().stream().filter(x->x.position()==candidate.position()).forEach(issues::add);preview.warnings().stream().filter(x->x.position()==candidate.position()).forEach(issues::add);
            Component text=Component.literal(pos.getX()+", "+pos.getY()+", "+pos.getZ()+" · ");
            if(assignment!=null)text=text.copy().append(tr("batch_assignment",label(preview.request().row(),preview.request().rowScheme()),assignment.number()));
            else if(!issues.isEmpty())text=text.copy().append(Component.translatable(issues.getFirst().code()));
            else text=text.copy().append(tr("batch_cell."+candidate.kind().name()));
            Component tooltip=text.copy();for(RowBatch.Issue issue:issues)tooltip=tooltip.copy().append("\n").append(Component.translatable(issue.code()));
            Button line=button(Component.literal(font.plainSubstrByWidth(text.getString(),panel-28)),8,92+i*17,panel-16,()->{});line.setTooltip(Tooltip.create(tooltip));
        }
        button("previous",8,234,70,()->{page--;rebuildWidgets();}).active=page>0;
        button(Component.literal((page+1)+" / "+pages),84,234,70,()->{}).active=false;
        button("next",160,234,70,()->{page++;rebuildWidgets();}).active=page+1<pages;
        button(tr("batch_confirm",preview.count()),8,262,panel/2-12,()->send("row_batch_confirm",args())).active=ready()&&preview.canCommit()&&data.revision()==preview.revision()&&!data.token().isEmpty();
        button("batch_restart",panel/2+4,262,panel/2-12,()->send("row_batch_restart",args())).active=ready();
        button("batch_exit",8,286,panel/2-12,()->send("row_batch_exit",args()));button("close",panel/2+4,286,panel/2-12,this::onClose);
    }
    static int rowIdentity(String text,GuidePreset.RowScheme scheme){
        return RowBatch.parseRowLabel(text,scheme);
    }
    static String label(int row,GuidePreset.RowScheme scheme){
        return SeatRegistry.rowLabel(row,scheme);
    }
    private RowBatchController.Args args(){RowBatchController.Args args=new RowBatchController.Args();args.card=data.card();args.revision=revision;args.token=data.token();return args;}
    private void send(String action,RowBatchController.Args args){
        if(!request.isEmpty())return;request=UUID.randomUUID().toString();this.action=action;sentAt=ticks;
        PacketDistributor.sendToServer(new SeatNetwork.Request(view.source(),view.identity(),request,action,SeatRegistry.JSON.toJson(args)));rebuildWidgets();
    }
    private void attempt(Runnable run){try{run.run();}catch(RuntimeException e){notice=e.getMessage()!=null&&e.getMessage().startsWith("mycinema.seats.")?e.getMessage():"mycinema.seats.batch_invalid";}}
    @Override public void tick(){super.tick();ticks++;if(!request.isEmpty()&&ticks-sentAt>100){request="";conflict=true;notice="mycinema.seats.timeout";rebuildWidgets();}}
    private boolean scrollBy(int amount){int next=Math.clamp(scroll+amount,0,Math.max(0,contentHeight-panelHeight));if(next==scroll)return false;scroll=next;rebuildWidgets();return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(x>=left&&x<=left+panel&&y>=top&&y<=top+panelHeight&&scrollBy(vertical>0?-24:vertical<0?24:0))return true;return super.mouseScrolled(x,y,horizontal,vertical);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==GLFW.GLFW_KEY_PAGE_DOWN&&scrollBy(Math.max(24,panelHeight-44)))return true;if(key==GLFW.GLFW_KEY_PAGE_UP&&scrollBy(-Math.max(24,panelHeight-44)))return true;return super.keyPressed(key,scan,modifiers);}
    @Override public void renderBackground(GuiGraphics graphics,int mx,int my,float partial){super.renderBackground(graphics,mx,my,partial);graphics.fill(left,top,left+panel,top+panelHeight,0xee152029);}
    private void line(GuiGraphics graphics,Component text,int y,int colour){int visibleY=y-scroll;if(visibleY>=22&&visibleY+9<=panelHeight-3)graphics.drawString(font,font.plainSubstrByWidth(text.getString(),panel-16),left+8,top+visibleY,colour,false);}
    @Override public void render(GuiGraphics graphics,int mx,int my,float partial){
        super.render(graphics,mx,my,partial);graphics.drawCenteredString(font,title,width/2,top+6,0xffffff);
        for(Label label:labels)if(label.y()>=top+22&&label.y()+9<=top+panelHeight-3)graphics.drawString(font,tr(label.key()),label.x(),label.y(),0xcbd5df,false);
        if(contentHeight>panelHeight){int track=panelHeight-26,thumb=Math.max(12,track*panelHeight/contentHeight),offset=(track-thumb)*scroll/(contentHeight-panelHeight);graphics.fill(left+panel-3,top+22,left+panel-1,top+panelHeight-3,0xff405565);graphics.fill(left+panel-3,top+22+offset,left+panel-1,top+22+offset+thumb,0xff9ecbd7);}
        if(data.preview()==null){
            line(graphics,tr("batch_hint"),196,0xcbd5df);line(graphics,tr("batch_hint2"),208,0xcbd5df);
            Component state=data.selection()==null?tr("batch_off"):!data.selection().active()?(data.applied()>0?tr("batch_complete",data.applied()):tr("batch_finished")):data.selection().firstPosition()==null?tr("batch_choose_first"):tr("batch_choose_last");
            line(graphics,state,226,0xa9d4be);
        }else{
            RowBatch.Preview p=data.preview();line(graphics,tr("batch_summary",p.count(),p.gaps(),p.skipped(),p.errors().size()),52,p.errors().isEmpty()?0xa9d4be:0xffbb66);
            line(graphics,tr("batch_preview_scope"),66,0xcbd5df);
            if(!p.errors().isEmpty())line(graphics,Component.translatable(p.errors().getFirst().code()),80,0xffbb66);
            line(graphics,tr("batch_preview_hint"),314,0xaaaabb);
        }
        if(!notice.isEmpty()){
            Component message=Component.translatable(notice);int y=Math.min(height-10,top+panelHeight+2);
            graphics.drawString(font,font.plainSubstrByWidth(message.getString(),width-8),4,y,conflict?0xffbb66:0xffeeaa,false);
            if(my>=y-2)graphics.renderTooltip(font,font.split(message,Math.max(100,width-24)),mx,my);
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){request="";super.onClose();}
}
