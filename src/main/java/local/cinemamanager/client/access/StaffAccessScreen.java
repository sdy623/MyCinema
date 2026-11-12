package local.cinemamanager.client.access;

import java.util.*;
import local.cinemamanager.access.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.neoforged.neoforge.network.PacketDistributor;

/** Explicit player/role selection. Authority and card creation remain on the server. */
public final class StaffAccessScreen extends Screen {
    private final BlockPos pos;
    private final String identity;
    private AccessNetwork.Data data;
    private EditBox target;
    private StaffRole issueRole=StaffRole.ENTRY;
    private final List<Button> mutations=new ArrayList<>();
    private Button personButton,cardRole;
    private int left,span,ticks,pendingTicks;
    private String pending="",notice="";
    private StaffAccessScreen(AccessNetwork.View packet,AccessNetwork.Data data){super(tr("title"));pos=packet.pos();identity=packet.identity();this.data=data;}
    private static Component tr(String key,Object...args){return Component.translatable("mycinema.access."+key,args);}
    private static Component role(String value){return tr("role."+value.toLowerCase(Locale.ROOT));}
    public static void open(AccessNetwork.View packet){
        AccessNetwork.Data data;try{data=AccessNetwork.JSON.fromJson(packet.json(),AccessNetwork.Data.class);}catch(RuntimeException invalid){return;}
        if(data==null||data.people()==null||data.people().size()>256||data.revision()==null||data.required()==null)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.screen instanceof StaffAccessScreen screen&&screen.pos.equals(packet.pos())&&screen.identity.equals(packet.identity()))screen.update(data);
        else if(packet.open())mc.setScreen(new StaffAccessScreen(packet,data));
    }
    private void update(AccessNetwork.Data next){data=next;notice=next.notice();pending="";pendingTicks=0;if(font!=null)rebuildWidgets();}
    @Override protected void init(){
        String previous=target==null?"":target.getValue();span=Math.min(500,width-24);left=(width-span)/2;mutations.clear();
        target=new EditBox(font,left,68,span,20,tr("target_uuid"));target.setMaxLength(36);target.setValue(previous);target.setHint(tr("target_uuid"));addRenderableWidget(target);
        if(target.getValue().isEmpty()&&!data.people().isEmpty())target.setValue(data.people().getFirst().id());
        personButton=addRenderableWidget(Button.builder(tr("choose_player"),button->nextPerson()).bounds(left,94,span,20).build());
        cardRole=addRenderableWidget(Button.builder(tr("card_role",role(issueRole.name())),button->{issueRole=issueRole==StaffRole.ENTRY?StaffRole.SERVICE:StaffRole.ENTRY;button.setMessage(tr("card_role",role(issueRole.name())));}).bounds(left,120,(span-6)/2,20).build());
        Button required=addRenderableWidget(Button.builder(tr("reader_role",role(data.required())),button->send("role",data.required().equals("ENTRY")?"SERVICE":"ENTRY")).bounds(left+(span+6)/2,120,(span-6)/2,20).build());
        required.setTooltip(Tooltip.create(tr("role_warning")));mutations.add(required);
        Button issue=addRenderableWidget(Button.builder(tr("issue"),button->send("issue",issueRole.name())).bounds(left,146,(span-6)/2,20).build());issue.setTooltip(Tooltip.create(tr("issue_help")));mutations.add(issue);
        Button revoke=addRenderableWidget(Button.builder(tr("revoke"),button->send("revoke","")).bounds(left+(span+6)/2,146,(span-6)/2,20).build());mutations.add(revoke);
        addRenderableWidget(Button.builder(tr("refresh"),button->send("refresh","")).bounds(left,height-28,(span-6)/2,20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE,button->onClose()).bounds(left+(span+6)/2,height-28,(span-6)/2,20).build());
        target.setResponder(value->updatePerson());updatePerson();for(Button button:mutations)button.active=pending.isEmpty();issue.active&=data.paired();
    }
    private void nextPerson(){if(data.people().isEmpty())return;int current=-1;for(int i=0;i<data.people().size();i++)if(data.people().get(i).id().equals(target.getValue()))current=i;target.setValue(data.people().get((current+1)%data.people().size()).id());}
    private void updatePerson(){if(personButton==null)return;var person=data.people().stream().filter(p->p.id().equals(target.getValue())).findFirst().orElse(null);personButton.setMessage(person==null?tr("choose_player"):Component.literal(person.name()).append(" · ").append(person.role().isBlank()?tr("no_card"):role(person.role())).append(person.online()?Component.empty():tr("offline")));}
    private void send(String action,String role){
        if(!pending.isEmpty())return;if(action.equals("issue")||action.equals("revoke"))try{UUID.fromString(target.getValue());}catch(RuntimeException invalid){notice="mycinema.access.invalid";return;}
        pending=action;pendingTicks=0;for(Button button:mutations)button.active=false;
        PacketDistributor.sendToServer(new AccessNetwork.Edit(pos,identity,data.revision(),action,target.getValue(),role));
    }
    @Override public void tick(){
        super.tick();if(minecraft==null||minecraft.player==null||minecraft.level==null||minecraft.player.distanceToSqr(pos.getCenter())>64||!(minecraft.level.getBlockEntity(pos) instanceof StaffReaderTile reader)||!reader.identity.toString().equals(identity)){onClose();return;}
        if(!pending.isEmpty()&&++pendingTicks>80){pending="";notice="mycinema.access.stale";rebuildWidgets();}
        ticks++;
    }
    @Override public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){renderTransparentBackground(graphics);graphics.fill(left-8,8,left+span+8,height-6,0xee14202a);}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        super.render(graphics,mouseX,mouseY,partialTick);graphics.drawString(font,title,left,18,0xfff7e2a5,false);
        Component state=data.paired()?tr("linked",data.door()):tr("not_ready");graphics.drawString(font,font.plainSubstrByWidth(state.getString(),span),left,36,data.paired()?0xff92dcbe:0xffffc17a,false);
        graphics.drawString(font,tr("target_uuid"),left,54,0xffc8d5e0,false);
        Component text=Component.translatable(notice.isBlank()?"mycinema.access.issue_help":notice);int y=174;
        for(var line:font.split(text,span)){if(y>height-42)break;graphics.drawString(font,line,left,y,0xffe6eef5,false);y+=font.lineHeight+1;}
    }
    @Override public boolean isPauseScreen(){return false;}
}
