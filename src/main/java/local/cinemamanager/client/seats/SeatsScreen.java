package local.cinemamanager.client.seats;

import local.cinemamanager.seats.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Acknowledged editor: stale responses preserve the draft until an explicit reload. */
public final class SeatsScreen extends Screen {
    private SeatNetwork.View view;
    private SeatNetwork.Data data;
    private final Map<String,String> draft=new HashMap<>();
    private final List<Label> labels=new ArrayList<>();
    private String request="",action="",notice="",hall="",status="AVAILABLE",arrow="NONE";
    private GuidePreset.RowScheme rowScheme=GuidePreset.RowScheme.NUMERIC;
    private GuidePreset.RowPrefix rowPrefix=GuidePreset.RowPrefix.LEGACY;
    private long draftRevision;
    private boolean editor,conflict,numbering;
    private int continuousDirection=1;
    private int ticks,sentAt,left,top,panel,panelHeight,browseScroll;
    private record Label(String key,int x,int y){}
    public SeatsScreen(SeatNetwork.View view,SeatNetwork.Data data){super(tr("title"));this.view=view;this.data=data;numbering=data.continuousConfigured()&&!data.kind().equals("guide");load();}
    private static Component tr(String suffix,Object... args){return Component.translatable("mycinema.seats."+suffix,args);}
    public static void receive(SeatNetwork.View packet){
        Minecraft mc=Minecraft.getInstance();
        if(RowBatchScreen.receive(packet))return;
        if(packet.open()){
            if(mc.screen!=null)return;
            try{SeatNetwork.Data data=SeatRegistry.JSON.fromJson(packet.json(),SeatNetwork.Data.class);if(data!=null)mc.setScreen(new SeatsScreen(packet,data));}catch(RuntimeException ignored){}return;
        }
        if(!(mc.screen instanceof SeatsScreen screen)||!screen.view.source().equals(packet.source())||!screen.view.identity().equals(packet.identity())||screen.request.isEmpty()||!screen.request.equals(packet.request()))return;
        try{
            SeatNetwork.Data next=SeatRegistry.JSON.fromJson(packet.json(),SeatNetwork.Data.class);if(next==null)return;
            String completed=screen.action;screen.request="";screen.view=packet;screen.data=next;screen.notice=next.notice();
            if(completed.equals("view")||screen.notice.equals("mycinema.seats.saved")||screen.notice.equals("mycinema.seats.deleted")||screen.notice.equals("mycinema.seats.continuous_started")||screen.notice.equals("mycinema.seats.continuous_stopped"))screen.load();
            else if(screen.draftRevision!=next.revision())screen.conflict=true;
            if(screen.notice.equals("mycinema.seats.continuous_started")){screen.onClose();return;}
            screen.rebuildWidgets();
        }catch(RuntimeException ignored){}
    }
    private void load(){
        draft.clear();conflict=false;draftRevision=data.revision();hall=data.hall();notice=data.notice();editor=data.kind().equals("guide")||data.target()||data.selected()!=null;
        draft.put("filter_zone",data.filterZone());draft.put("filter_row",Integer.toString(data.filterRow()));
        if(data.kind().equals("guide")){GuidePreset preset=data.guide();draft.put("hall",preset.hall());draft.put("zone",preset.zone());draft.put("row",Integer.toString(preset.firstRow()));draft.put("last_row",Integer.toString(preset.lastRow()));draft.put("number",Integer.toString(preset.seat()));draft.put("first_seat",Integer.toString(preset.firstSeat()));draft.put("last_seat",Integer.toString(preset.lastSeat()));arrow=preset.arrow().name();rowScheme=preset.rowScheme();rowPrefix=preset.rowPrefix();}
        else{SeatRegistry.Seat seat=data.selected();draft.put("zone",seat==null?"":seat.zone());rowScheme=seat==null?GuidePreset.RowScheme.NUMERIC:seat.rowScheme();draft.put("row",seat==null?"1":seat.rowLabel());draft.put("number",Integer.toString(seat==null?1:seat.number()));status=seat==null?"AVAILABLE":seat.status().name();if(seat!=null)hall=seat.hall();loadContinuous();}
    }
    @Override protected void init(){
        panel=Math.min(430,width-12);panelHeight=data.kind().equals("guide")?332:numbering?282:editor?234:256;left=(width-panel)/2;top=Math.max(3,(height-panelHeight)/2);labels.clear();
        if(!editor&&!numbering&&!data.kind().equals("guide")){browseScroll=Math.max(0,Math.min(browseScroll,Math.max(0,panelHeight+6-height)));top-=browseScroll;}
        if(data.kind().equals("guide"))guide();else if(numbering)continuous();else if(editor)edit();else browse();
        if(!request.isEmpty())children().forEach(child->{if(child instanceof AbstractWidget widget)widget.active=false;});
    }
    private Button button(Component text,int x,int y,int w,Runnable callback){return addRenderableWidget(Button.builder(text,b->callback.run()).bounds(left+x,top+y,w,18).build());}
    private Button button(String key,int x,int y,int w,Runnable callback){return button(tr(key),x,y,w,callback);}
    private void input(String key,int y,String value,int max){labels.add(new Label(key,left+8,top+y+5));EditBox field=new EditBox(font,left+panel/2,top+y,panel/2-8,18,tr(key));field.setMaxLength(max);field.setValue(value);field.setResponder(s->draft.put(key,s));addRenderableWidget(field);}
    private String value(String key){return draft.getOrDefault(key,"");}
    private int number(String key){try{return Integer.parseInt(value(key));}catch(RuntimeException invalid){throw SeatRegistry.error("invalid");}}
    private String hallName(){return data.halls().stream().filter(h->h.id().equals(hall)).map(SeatNetwork.Hall::name).findFirst().orElseGet(()->tr("hall_missing").getString());}
    private void cycleHall(){if(data.halls().isEmpty())return;int index=0;for(int i=0;i<data.halls().size();i++)if(data.halls().get(i).id().equals(hall))index=i;hall=data.halls().get((index+1)%data.halls().size()).id();if(editor||numbering)rebuildWidgets();else{SeatNetwork.Args args=args();args.page=0;args.id="";send("view",args);}}
    private void browse(){
        button(Component.literal(hallName()),8,20,panel-132,this::cycleHall);
        button("batch_entry",panel-116,20,108,()->send("row_batch_view",args())).active=data.card()!=null&&!data.card().isEmpty();
        EditBox zone=new EditBox(font,left+8,top+46,panel/2-12,18,tr("filter_zone"));zone.setMaxLength(32);zone.setValue(value("filter_zone"));zone.setHint(tr("filter_zone"));zone.setResponder(s->draft.put("filter_zone",s));addRenderableWidget(zone);
        EditBox row=new EditBox(font,left+panel/2,top+46,45,18,tr("filter_row"));row.setMaxLength(3);row.setValue(value("filter_row"));row.setResponder(s->draft.put("filter_row",s));addRenderableWidget(row);
        button("filter",panel/2+50,46,panel/2-58,()->attempt(()->{SeatNetwork.Args args=args();args.page=0;send("view",args);}));
        for(int i=0;i<data.seats().size();i++){SeatRegistry.Seat seat=data.seats().get(i);String diagnostic=data.diagnostics().getOrDefault(seat.id(),"unverified");Component label=Component.literal(seat.zone()+" · ").append(seat.rowScheme()==GuidePreset.RowScheme.LETTERS?Component.literal(seat.rowLabel()+" · "+seat.number()):tr("seat_label",seat.rowLabel(),seat.number())).append(" · ").append(tr("status."+seat.status().name()));if(!diagnostic.equals("present"))label=label.copy().append(" · ").append(tr("binding."+diagnostic));button(label,8,102+i*17,panel-16,()->{SeatNetwork.Args args=args();args.id=seat.id();send("view",args);}).setTooltip(Tooltip.create(label));}
        button("previous",8,208,60,()->{SeatNetwork.Args args=args();args.page=data.page()-1;send("view",args);}).active=data.page()>0;
        button(Component.literal((data.page()+1)+" / "+data.pages()),72,208,64,()->{}).active=false;
        button("next",140,208,60,()->{SeatNetwork.Args args=args();args.page=data.page()+1;send("view",args);}).active=data.page()+1<data.pages();
        button("close",panel-78,208,70,this::onClose);
        button("continuous_title",8,233,panel/2-12,this::showContinuous).active=data.card()!=null&&!data.card().isEmpty();
        Button map=button(Component.translatable("mycinema.seats.map.entry"),panel/2+4,233,panel/2-12,()->SeatMapScreen.open(this,view.source(),view.identity(),hall));
        map.active=!hall.isEmpty();map.setTooltip(Tooltip.create(Component.translatable("mycinema.seats.map.title")));
    }
    private void edit(){
        button(Component.literal(hallName()),8,27,panel-132,this::cycleHall);
        button("batch_entry",panel-116,27,108,()->send("row_batch_view",args())).active=data.card()!=null&&!data.card().isEmpty();
        input("zone",53,value("zone"),32);input("row",79,value("row"),3);input("number",105,value("number"),3);
        button(tr("status."+status),8,135,panel-16,()->{SeatRegistry.Status[] values=SeatRegistry.Status.values();status=values[(SeatRegistry.Status.valueOf(status).ordinal()+1)%values.length].name();rebuildWidgets();});
        button("save",8,162,panel/2-12,()->attempt(()->{SeatNetwork.Args args=args();args.zone=value("zone");args.row=RowBatch.parseRowLabel(value("row"),rowScheme);args.number=number("number");args.status=status;send("save",args);})).active=data.editable()&&!conflict&&!data.halls().isEmpty();
        button("reload",panel/2+4,162,panel/2-12,()->send("view",args())).setTooltip(Tooltip.create(tr("reload_hint")));
        button("delete",8,188,panel/2-12,()->{if(!notice.equals("mycinema.seats.confirm_delete")){notice="mycinema.seats.confirm_delete";return;}SeatNetwork.Args args=args();args.confirmed=true;send("delete",args);}).active=data.selected()!=null&&data.editable()&&!conflict;
        button("back",panel/2+4,188,panel/2-12,()->{SeatNetwork.Args args=args();args.id="";args.target=false;send("view",args);});
        button("continuous_title",8,213,panel/2-12,this::showContinuous).active=data.card()!=null&&!data.card().isEmpty();
        button("close",panel/2+4,213,panel/2-12,this::onClose);
    }
    private void loadContinuous(){
        ContinuousSeatState saved=data.continuous();
        draft.put("continuous_zone",saved==null?value("zone"):saved.zone());
        draft.put("continuous_row",Integer.toString(saved==null?(data.selected()==null?1:data.selected().row()):saved.row()));
        draft.put("continuous_start",saved==null?value("number"):Integer.toString(saved.next()));
        draft.put("continuous_end",saved==null?"0":Integer.toString(saved.end()));
        continuousDirection=saved==null?1:saved.direction();if(saved!=null&&numbering)hall=saved.hall();
    }
    private void showContinuous(){numbering=true;loadContinuous();rebuildWidgets();}
    private void continuous(){
        button(Component.literal(hallName()),8,27,panel-16,this::cycleHall);
        input("continuous_zone",51,value("continuous_zone"),32);input("continuous_row",75,value("continuous_row"),3);
        input("continuous_start",99,value("continuous_start"),3);input("continuous_end",123,value("continuous_end"),3);
        if(children().getLast() instanceof EditBox field)field.setTooltip(Tooltip.create(tr("continuous_end_hint")));
        button(tr(continuousDirection==1?"continuous_forward":"continuous_backward"),8,147,panel-16,()->{continuousDirection=-continuousDirection;rebuildWidgets();});
        boolean ready=data.editable()&&!conflict&&data.card()!=null&&!data.card().isEmpty()&&!data.halls().isEmpty();
        button("continuous_begin",8,212,panel/2-12,()->attempt(()->{
            SeatNetwork.Args args=args();args.zone=value("continuous_zone");args.row=number("continuous_row");args.number=number("continuous_start");args.end=number("continuous_end");args.direction=continuousDirection;send("continuous_start",args);
        })).active=ready;
        button("reload",panel/2+4,212,panel/2-12,()->send("view",args())).setTooltip(Tooltip.create(tr("reload_hint")));
        Button next=button("continuous_next_row",8,236,panel/2-12,()->send("continuous_next",args()));next.active=ready&&data.continuous()!=null&&data.continuous().row()<SeatRegistry.MAX_NUMBER;next.setTooltip(Tooltip.create(tr("continuous_next_hint")));
        button("continuous_exit",panel/2+4,236,panel/2-12,()->send("continuous_stop",args())).active=data.continuousConfigured()&&data.card()!=null&&!data.card().isEmpty();
        button("back",8,260,panel/2-12,()->{numbering=false;rebuildWidgets();});button("close",panel/2+4,260,panel/2-12,this::onClose);
    }
    private GuidePreset guideDraft(){return new GuidePreset(value("hall"),value("zone"),number("row"),number("last_row"),number("number"),GuidePreset.Arrow.valueOf(arrow),rowScheme,rowPrefix,number("first_seat"),number("last_seat"));}
    private void guide(){
        input("hall_label",27,value("hall"),32); // Store the display caption separately from a manager hall UUID.
        if(children().getLast() instanceof EditBox field)field.setResponder(s->draft.put("hall",s));
        input("zone",52,value("zone"),32);input("guide_first_row",77,value("row"),3);
        if(children().getLast() instanceof EditBox field)field.setResponder(s->draft.put("row",s));
        input("guide_last_row",102,value("last_row"),3);
        if(children().getLast() instanceof EditBox field)field.setResponder(s->draft.put("last_row",s));
        input("guide_number",127,value("number"),3);
        if(children().getLast() instanceof EditBox field)field.setResponder(s->draft.put("number",s));
        input("first_seat",152,value("first_seat"),3);
        if(children().getLast() instanceof EditBox field)field.setTooltip(Tooltip.create(tr("seat_range_hint")));
        input("last_seat",177,value("last_seat"),3);
        if(children().getLast() instanceof EditBox field)field.setTooltip(Tooltip.create(tr("seat_range_hint")));
        button(tr("row_scheme."+rowScheme.name()),8,202,panel/2-12,()->{rowScheme=rowScheme==GuidePreset.RowScheme.NUMERIC?GuidePreset.RowScheme.LETTERS:GuidePreset.RowScheme.NUMERIC;if(rowScheme==GuidePreset.RowScheme.LETTERS)rowPrefix=GuidePreset.RowPrefix.PLAIN;rebuildWidgets();}).setTooltip(Tooltip.create(tr("row_scheme_hint")));
        if(rowScheme==GuidePreset.RowScheme.LETTERS)rowPrefix=GuidePreset.RowPrefix.PLAIN;
        Button prefix=button(tr(rowScheme==GuidePreset.RowScheme.LETTERS?"row_prefix.LETTERS":"row_prefix."+rowPrefix.name()),panel/2+4,202,panel/2-12,()->{GuidePreset.RowPrefix[] values=GuidePreset.RowPrefix.values();rowPrefix=values[(rowPrefix.ordinal()+1)%values.length];rebuildWidgets();});
        prefix.active=rowScheme==GuidePreset.RowScheme.NUMERIC;prefix.setTooltip(Tooltip.create(tr("row_prefix_hint")));
        button(tr("arrow."+arrow),8,254,panel-16,()->{GuidePreset.Arrow[] values=GuidePreset.Arrow.values();arrow=values[(GuidePreset.Arrow.valueOf(arrow).ordinal()+1)%values.length].name();rebuildWidgets();});
        button("save",8,281,panel/3-10,()->attempt(()->{SeatNetwork.Args args=args();args.guide=guideDraft();send("guide_save",args);})).active=!conflict;
        button("copy",panel/3+2,281,panel/3-8,()->send("guide_copy",args())).active=!conflict;
        button("reload",panel*2/3+2,281,panel/3-10,()->send("view",args())).setTooltip(Tooltip.create(tr("reload_hint")));
        Button bits=button("bits_generate",8,308,panel/2-12,()->send("guide_bits",args()));bits.active=!conflict;bits.setTooltip(Tooltip.create(tr("bits_tooltip")));
        button("close",panel/2+4,308,panel/2-12,this::onClose);
    }
    private SeatNetwork.Args args(){SeatNetwork.Args args=new SeatNetwork.Args();args.revision=draftRevision;args.card=data.card();args.hall=hall;args.id=data.selected()==null?"":data.selected().id();args.target=data.target();args.position=data.position();args.page=data.page();args.filterZone=value("filter_zone");try{args.filterRow=Integer.parseInt(value("filter_row"));}catch(RuntimeException invalid){args.filterRow=0;}return args;}
    private void attempt(Runnable work){try{work.run();}catch(RuntimeException invalid){notice=invalid.getMessage()!=null&&invalid.getMessage().startsWith("mycinema.seats.")?invalid.getMessage():"mycinema.seats.invalid";}}
    private void send(String action,SeatNetwork.Args args){if(!request.isEmpty())return;this.action=action;request=UUID.randomUUID().toString();sentAt=ticks;PacketDistributor.sendToServer(new SeatNetwork.Request(view.source(),view.identity(),request,action,SeatRegistry.JSON.toJson(args)));rebuildWidgets();}
    static boolean acceptsRowBatch(SeatNetwork.View packet){
        return Minecraft.getInstance().screen instanceof SeatsScreen screen&&screen.view.source().equals(packet.source())
                &&screen.view.identity().equals(packet.identity())&&!screen.request.isEmpty()&&screen.request.equals(packet.request())
                &&screen.action.equals("row_batch_view");
    }
    @Override public void tick(){super.tick();ticks++;if(!request.isEmpty()&&ticks-sentAt>100){request="";conflict=true;notice="mycinema.seats.timeout";rebuildWidgets();}}
    @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        super.renderBackground(graphics,mouseX,mouseY,partial);graphics.fill(left,top,left+panel,top+panelHeight,0xee152029);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        // Screen.render draws the background (including blur) before the widgets in 1.21.1.
        super.render(graphics,mouseX,mouseY,partial);graphics.drawCenteredString(font,data.kind().equals("guide")?tr("guide_title"):numbering?tr("continuous_title"):tr("title"),width/2,top+6,0xffffff);
        for(Label label:labels)graphics.drawString(font,tr(label.key()),label.x(),label.y(),0xcbd5df,false);
        if(data.kind().equals("guide")){
            Component preview;try{GuidePreset preset=guideDraft();preview=tr("row_preview",preset.displayLabel()+" "+preset.arrowText());}catch(RuntimeException invalid){preview=tr("row_preview_invalid");}
            graphics.drawString(font,preview,left+8,top+226,0xfff4ca,false);
            graphics.drawString(font,tr("row_scheme_hint"),left+8,top+238,0xaaaabb,false);
        }
        if(numbering){
            ContinuousSeatState saved=data.continuous();Component state=saved==null?tr("continuous_off"):saved.active()?tr("continuous_ready",saved.zone(),saved.row(),saved.next()):tr("continuous_finished");
            graphics.drawString(font,font.plainSubstrByWidth(state.getString(),panel-16),left+8,top+171,0xa9d4be,false);
            graphics.drawString(font,font.plainSubstrByWidth(tr("continuous_hint").getString(),panel-16),left+8,top+185,0xaaaabb,false);
            graphics.drawString(font,font.plainSubstrByWidth(tr("continuous_air_hint").getString(),panel-16),left+8,top+197,0xaaaabb,false);
        }
        else if(!editor){int y=top+70;for(SeatRegistry.Count count:data.counts()){Component line=tr("count",count.zone().isEmpty()?hallName():count.zone()+(count.row()==0?"":" / "+count.rowLabel()),count.total(),count.available(),count.maintenance(),count.disabled());graphics.drawString(font,font.plainSubstrByWidth(line.getString(),panel-16),left+8,y,0xa9d4be,false);y+=10;}}
        else if(!data.kind().equals("guide"))graphics.drawString(font,font.plainSubstrByWidth(tr("inventory_only").getString(),panel-16),left+8,top+16,0xaaaabb,false);
        if(!notice.isEmpty()){String text=Component.translatable(notice).getString();int y=Math.min(height-10,top+panelHeight+1);graphics.drawString(font,font.plainSubstrByWidth(text,width-8),4,y,conflict?0xffbb66:0xffeeaa,false);if(mouseY>=y-2)graphics.renderTooltip(font,font.split(Component.translatable(notice),Math.max(100,width-24)),mouseX,mouseY);}
    }
    private boolean scrollBrowse(int amount){
        if(editor||numbering||data.kind().equals("guide"))return false;
        int next=Math.max(0,Math.min(browseScroll+amount,Math.max(0,panelHeight+6-height)));
        if(next==browseScroll)return false;browseScroll=next;rebuildWidgets();return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(scrollBrowse(vertical>0?-24:vertical<0?24:0))return true;
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN&&scrollBrowse(24)||key==org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP&&scrollBrowse(-24))return true;
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){request="";super.onClose();}
}
