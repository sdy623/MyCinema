package local.cinemamanager.client;

import local.cinemamanager.operations.*;
import local.cinemamanager.panels.PublicTimeRange;
import local.cinemamanager.theater.CinemaProgramme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Source-free, paged operator console. A public wall is never a bearer credential. */
public final class OperationsScreen extends Screen {
    private final BlockPos pos;
    private final String identity;
    private final OperationsSession session=new OperationsSession();
    private OperationsNetwork.Data data;
    private final List<Button> commands=new ArrayList<>(),selection=new ArrayList<>();
    private String hall="",show="",localNotice="";
    private int tab,page,clip,ticks,left,contentWidth,contentTop=103,rowsPerPage;
    private Button previousPage,nextPage,load;
    private final List<FormattedCharSequence> lines=new ArrayList<>();
    private OperationsScreen(OperationsNetwork.View packet,OperationsNetwork.Data data){super(tr("title"));pos=packet.pos();identity=packet.identity();this.data=data;selectDefaults();}
    public static void open(OperationsNetwork.View packet){
        OperationsNetwork.Data data;try{data=OperationsNetwork.JSON.fromJson(packet.json(),OperationsNetwork.Data.class);}catch(RuntimeException invalid){return;}
        if(data==null||data.halls()==null||data.plans()==null)return;
        Minecraft mc=Minecraft.getInstance();
        if(!packet.open()){
            if(mc.screen instanceof OperationsScreen screen&&screen.pos.equals(packet.pos())&&screen.identity.equals(packet.identity())&&screen.session.accept(packet.request(),screen.ticks)){
                screen.data=data;screen.localNotice="";screen.selectDefaults();screen.rebuild();
            }
        }else mc.setScreen(new OperationsScreen(packet,data));
    }
    private static Component tr(String key,Object...args){return Component.translatable("mycinema.operations."+key,args);}
    private OperationsNetwork.Hall selectedHall(){return data.halls().stream().filter(h->h.id().equals(hall)).findFirst().orElse(null);}
    private List<OperationsNetwork.Plan> plans(){return data.plans().stream().filter(s->s.hall().equals(hall)).toList();}
    private OperationsNetwork.Plan selectedShow(){return plans().stream().filter(s->s.id().equals(show)).findFirst().orElse(null);}
    private void selectDefaults(){
        if(selectedHall()==null)hall=data.halls().isEmpty()?"":data.halls().getFirst().id();
        if(selectedShow()==null)show=plans().isEmpty()?"":plans().getFirst().id();
        var h=selectedHall();clip=h==null||h.programme()==null?0:Math.clamp(clip,0,h.programme().clips().size()-1);
    }
    @Override protected void init(){
        contentWidth=Math.max(150,Math.min(680,width-24));left=(width-contentWidth)/2;
        rowsPerPage=Math.max(1,(height-118-contentTop)/(font.lineHeight+2));commands.clear();selection.clear();load=null;
        button(tr("tab_status"),left,48,(contentWidth-4)/2,()->{tab=0;page=0;rebuild();});
        button(tr("tab_schedule"),left+(contentWidth+4)/2,48,(contentWidth-4)/2,()->{tab=1;page=0;rebuild();});
        selection.add(button(Component.literal("<"),left,73,24,()->cycleHall(-1)));
        selection.add(button(Component.literal(">"),left+contentWidth-24,73,24,()->cycleHall(1)));
        int commandY=height-91,unit=(contentWidth-20)/6;
        if(tab==0){
            String[] names={"play","pause","stop","previous","next","choose"};
            CinemaProgramme.Action[] actions={CinemaProgramme.Action.PLAY,CinemaProgramme.Action.PAUSE,CinemaProgramme.Action.STOP,CinemaProgramme.Action.PREVIOUS,CinemaProgramme.Action.NEXT,CinemaProgramme.Action.CHOOSE};
            for(int i=0;i<names.length;i++){var action=actions[i];commands.add(button(tr(names[i]),left+i*(unit+4),commandY,unit,()->control(action)));}
            selection.add(button(tr("clip_previous"),left,commandY-24,(contentWidth-4)/2,()->{var h=selectedHall();if(h!=null&&h.programme()!=null){clip=Math.floorMod(clip-1,h.programme().clips().size());rebuildLines();}}));
            selection.add(button(tr("clip_next"),left+(contentWidth+4)/2,commandY-24,(contentWidth-4)/2,()->{var h=selectedHall();if(h!=null&&h.programme()!=null){clip=(clip+1)%h.programme().clips().size();rebuildLines();}}));
        }else{
            selection.add(button(tr("show_previous"),left,commandY-24,(contentWidth-4)/2,()->cycleShow(-1)));
            selection.add(button(tr("show_next"),left+(contentWidth+4)/2,commandY-24,(contentWidth-4)/2,()->cycleShow(1)));
            load=button(tr("load_show"),left,commandY,contentWidth,this::confirmShow);
        }
        int bottom=height-25,bw=(contentWidth-12)/4;
        previousPage=button(tr("page_previous"),left,bottom,bw,()->{page=Math.max(0,page-1);updateButtons();});
        nextPage=button(tr("page_next"),left+bw+4,bottom,bw,()->{page=Math.min(pageCount()-1,page+1);updateButtons();});
        button(tr("refresh"),left+(bw+4)*2,bottom,bw,()->send("view",arguments(),false));
        button(Component.translatable("gui.done"),left+(bw+4)*3,bottom,bw,this::onClose);
        rebuildLines();updateButtons();
    }
    private Button button(Component text,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(text,b->action.run()).bounds(x,y,Math.max(12,w),20).build());}
    private void rebuild(){clearWidgets();init();}
    private void cycleHall(int direction){if(session.pending()||data.halls().isEmpty())return;int index=0;for(int i=0;i<data.halls().size();i++)if(data.halls().get(i).id().equals(hall))index=i;hall=data.halls().get(Math.floorMod(index+direction,data.halls().size())).id();show="";clip=0;page=0;selectDefaults();rebuildLines();updateButtons();}
    private void cycleShow(int direction){var plans=plans();if(session.pending()||plans.isEmpty())return;int index=0;for(int i=0;i<plans.size();i++)if(plans.get(i).id().equals(show))index=i;show=plans.get(Math.floorMod(index+direction,plans.size())).id();rebuildLines();updateButtons();}
    private void line(Component text){lines.addAll(font.split(text,contentWidth-8));}
    private void rebuildLines(){
        lines.clear();var h=selectedHall();
        if(!data.authorized()){line(tr("owner_required"));line(tr("staff_help"));}
        else if(h==null)line(tr("empty"));
        else if(tab==0){
            line(tr("hall_status",h.name(),tr("status."+h.status().toLowerCase(Locale.ROOT)).getString()));
            line(Component.translatable(h.authority()));
            var programme=h.programme();
            if(programme==null)line(tr("no_programme"));else{
                line(tr("playlist",programme.title(),programme.currentIndex()+1,programme.clips().size()));
                line(tr("clip_selected",clip+1));
                for(var item:programme.clips())line(Component.literal((item.index()==programme.currentIndex()?"▶ ":"  ")+(item.index()+1)+". "+item.label()+"  "+duration(item.durationMs())+(item.index()==clip?"  ✓":"")));
                if(!programme.failureCode().isBlank()){String key="mycinema.catalog."+programme.failureCode();line(tr("programme_issue",net.minecraft.client.resources.language.I18n.exists(key)?net.minecraft.client.resources.language.I18n.get(key):tr("unavailable").getString()));}
            }
        }else{
            line(tr("today",local.cinemamanager.panels.PublicTimeRange.dayKey(data.serverTime(),data.timezone())));
            if(data.timezone().isBlank())line(tr("timezone_unset"));else if(plans().isEmpty())line(tr("no_shows"));
            for(var plan:plans()){
                var time=PublicTimeRange.of(plan.start(),plan.end(),data.serverTime(),data.timezone());
                line(Component.literal((plan.id().equals(show)?"▶ ":"  ")+OperationsWallText.time(time)+"  "+plan.title()).append(" · ").append(tr("status."+plan.status().toLowerCase(Locale.ROOT))));
            }
        }
        page=Math.clamp(page,0,pageCount()-1);
    }
    private int pageCount(){return Math.max(1,(lines.size()+rowsPerPage-1)/rowsPerPage);}
    private static String duration(long ms){long seconds=ms/1000;return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private boolean canControl(){return data.authorized()&&data.controllable()&&session.fresh(ticks)&&!session.pending()&&selectedHall()!=null;}
    private void updateButtons(){
        var hall=selectedHall();var programme=hall==null?null:hall.programme();
        for(Button button:commands)button.active=canControl()&&programme!=null;
        if(commands.size()>2)commands.get(2).active=programme!=null&&ProgrammeStopPolicy.enabled(canControl(),true,session.fresh(ticks),session.pending(),programme.status());
        for(Button button:selection)button.active=!session.pending()&&data.authorized();
        if(load!=null)load.active=canControl()&&selectedShow()!=null&&selectedShow().loadable();
        if(previousPage!=null)previousPage.active=page>0;if(nextPage!=null)nextPage.active=page<pageCount()-1;
    }
    private OperationsNetwork.Arguments arguments(){var args=new OperationsNetwork.Arguments();args.controller=data.controller();args.panelRevision=data.panelRevision();args.signature=data.signature();args.managerRevision=data.managerRevision();args.hall=hall;args.show=show;return args;}
    private void control(CinemaProgramme.Action action){
        if(!canControl()||selectedHall().programme()==null)return;
        var current=selectedHall().programme();var args=arguments();
        args.programme=new CinemaProgramme.Request(action,current.run(),current.operationRevision(),current.currentIndex(),action==CinemaProgramme.Action.CHOOSE?clip:0);
        send("control",args,true);
    }
    private void confirmShow(){
        if(!canControl()||selectedShow()==null||!selectedShow().loadable())return;
        var args=arguments();String title=selectedShow().title();
        minecraft.setScreen(new ConfirmScreen(yes->{minecraft.setScreen(this);if(yes&&canControl()){args.confirmed=true;send("load_show",args,true);}},tr("load_show"),tr("confirm_show",title)));
    }
    private void send(String action,OperationsNetwork.Arguments args,boolean command){
        String request=UUID.randomUUID().toString();if(!session.begin(request,ticks,command))return;
        localNotice=command?"mycinema.operations.pending":"";
        PacketDistributor.sendToServer(new OperationsNetwork.Request(pos,identity,action,request,OperationsNetwork.JSON.toJson(args)));updateButtons();
    }
    @Override public void tick(){
        super.tick();ticks++;
        if(minecraft==null||minecraft.level==null||minecraft.player==null||minecraft.player.distanceToSqr(pos.getCenter())>64
                ||!(minecraft.level.getBlockEntity(pos) instanceof local.cinemamanager.InfoTile tile)
                ||!tile.identity.toString().equals(identity)||!tile.isOperations()&&!tile.invalidTerminalRole()){onClose();return;}
        if(session.tick(ticks))localNotice="mycinema.operations.no_reply";
        if(ticks%40==0&&!session.pending())send("view",arguments(),false);
        updateButtons();
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        renderBackground(graphics,mouseX,mouseY,partial);
        graphics.drawCenteredString(font,title,width/2,12,0xffffff);
        String heading=data.authorized()?data.cinema():tr("locked").getString();graphics.drawCenteredString(font,font.plainSubstrByWidth(heading,contentWidth),width/2,29,0x80e8e8);
        var hall=selectedHall();graphics.drawCenteredString(font,font.plainSubstrByWidth(hall==null?"":hall.name(),Math.max(1,contentWidth-60)),width/2,79,0xffffff);
        graphics.enableScissor(left,contentTop,left+contentWidth,height-118);
        int first=page*rowsPerPage;for(int i=first;i<Math.min(lines.size(),first+rowsPerPage);i++)graphics.drawString(font,lines.get(i),left+4,contentTop+(i-first)*(font.lineHeight+2),0xe0e8ee,false);
        graphics.disableScissor();
        Component notice=Component.translatable(localNotice.isBlank()?data.notice():localNotice);
        int y=height-66;for(var line:font.split(notice,contentWidth)){if(y>height-42)break;graphics.drawString(font,line,left,y,0xe7c778,false);y+=font.lineHeight+1;}
        graphics.drawCenteredString(font,(page+1)+"/"+pageCount(),width/2,height-38,0xaaaaaa);
        super.render(graphics,mouseX,mouseY,partial);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){session.close();super.onClose();}
}
