package local.cinemamanager.switches;

import java.util.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import local.cinemamanager.CinemaManager;
import static local.cinemamanager.switches.RegisteredSwitchEngine.*;

@EventBusSubscriber(modid=CinemaManager.ID)
public final class RegisteredSwitchCommands {
    private static final Map<MinecraftServer,RegisteredSwitchEngine> ENGINES=Collections.synchronizedMap(new WeakHashMap<>());
    private static RegisteredSwitchEngine engine(MinecraftServer server){return ENGINES.computeIfAbsent(server,s->new RegisteredSwitchEngine(RegisteredSwitchRegistry.runtime(s.overworld())));}
    static boolean hasPending(MinecraftServer server){var e=ENGINES.get(server);return e!=null&&e.hasPending();}
    static void bindingsChanged(MinecraftServer server){ENGINES.remove(server);}
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=Commands.literal("mycinema_switch").requires(s->s.hasPermission(2));
        root.then(Commands.literal("status").executes(c->{
            MinecraftSwitchGateway gw=new MinecraftSwitchGateway(c.getSource().getServer());settleStatus(c.getSource(),gw);String fault=gw.worldFault();String positionFault=gw.positionFault();
            PositionEnrollment receipt=PositionEnrollment.read(c.getSource().getServer().overworld());
            String summary=receipt==null?"No positional enrollment receipt":receipt.summary();
            IeInstallationBinding ie=IeInstallationBinding.read(c.getSource().getServer().overworld());
            String ies=ie==null?"IE not bound":ie.summary();
            var floor=FloorPositionEnrollment.read(c.getSource().getServer().overworld());String floors=floor==null?"Floor not bound":floor.summary();
            c.getSource().sendSuccess(()->Component.literal("MyCinema finite registry. "+summary+" ("+(positionFault==null?"position receipt readable":positionFault)+"). "+(fault==null?"World profile valid":fault)+". "+ies+"; "+floors+"; staff held."),false);return 0;
        }));
        root.then(Commands.literal("enroll_positions").executes(c->{
            String fault=new MinecraftSwitchGateway(c.getSource().getServer()).enrollAll();
            if(fault!=null){c.getSource().sendFailure(Component.literal(fault));return 0;}
            c.getSource().sendSuccess(()->Component.literal("45 fixed positions explicitly enrolled. This is positional registration, not persistent block identity; no controller was toggled."),false);return 1;
        }));
        var re=Commands.literal("reenroll");
        for(Device d:RegisteredSwitchRegistry.DEVICES)if(d.kind()==Kind.LEVER)re.then(Commands.literal(d.id()).executes(c->{
            String fault=new MinecraftSwitchGateway(c.getSource().getServer()).reenroll(d.id());
            if(fault!=null){c.getSource().sendFailure(Component.literal(fault));return 0;}
            c.getSource().sendSuccess(()->Component.literal("Position explicitly re-enrolled: "+d.id()+"; controller unchanged"),false);return 1;
        }));
        root.then(re);
        var bindFloor=Commands.literal("bind_floor");
        for(var plan:AuxiliaryPlan.PLANS)if(plan.floor())bindFloor.then(Commands.literal(plan.id()).executes(c->{
            String fault=FloorPositionEnrollment.bind(c.getSource().getServer().overworld(),plan.id());
            if(fault!=null){c.getSource().sendFailure(Component.literal(fault));return 0;}
            c.getSource().sendSuccess(()->Component.literal("Explicit floor positional binding completed: "+plan.id()+"; controller unchanged; offline identical replacements remain indistinguishable."),false);return 1;
        }));root.then(bindFloor);
        var bind=Commands.literal("bind_ie");
        for(var plan:IeCircuitPlan.CIRCUITS)bind.then(Commands.literal(plan.id()).executes(c->{
            String fault=IeInstallationBinding.bind(c.getSource().getServer().overworld(),plan.id());
            if(fault!=null){c.getSource().sendFailure(Component.literal(fault));return 0;}
            c.getSource().sendSuccess(()->Component.literal("Explicit IE binding completed: "+plan.id()+"; no device toggled. Offline identical replacements remain a positional-identity limitation."),false);return 1;
        }));root.then(bind);
        root.then(Commands.literal("verify").executes(c->report(c.getSource(),engine(c.getSource().getServer()).verify(new MinecraftSwitchGateway(c.getSource().getServer())))));
        var groups=Commands.literal("group");
        for(String group:RegisteredSwitchRegistry.DEVICES.stream().map(Device::group).distinct().toList())
            groups.then(actions(group,RegisteredSwitchRegistry.group(group)));
        root.then(groups);
        var devices=Commands.literal("device");
        for(Device d:RegisteredSwitchRegistry.DEVICES)devices.then(actions(d.id(),List.of(d.id())));
        root.then(devices);
        var scenes=Commands.literal("scene");
        for(String scene:List.of("entry","movie","pause","cleaning","egress"))
            scenes.then(Commands.literal(scene).executes(c->apply(c.getSource(),RegisteredSwitchRegistry.scene(),!scene.equals("movie"))));
        root.then(scenes);event.getDispatcher().register(root);
    }
    private static LiteralArgumentBuilder<CommandSourceStack> actions(String id,List<String> ids){
        return Commands.literal(id).then(Commands.literal("status").executes(c->inspect(c.getSource(),ids))).then(Commands.literal("on").executes(c->apply(c.getSource(),ids,true)))
                .then(Commands.literal("off").executes(c->apply(c.getSource(),ids,false)));
    }
    private static void settleStatus(CommandSourceStack source,MinecraftSwitchGateway gateway){
        var active=ENGINES.get(source.getServer());if(active==null)return;Result result=active.readStatus(gateway);if(result!=null)report(source,result);
    }
    private static int inspect(CommandSourceStack source,List<String> ids){
        MinecraftSwitchGateway gateway=new MinecraftSwitchGateway(source.getServer());settleStatus(source,gateway);String fault=gateway.worldFault();
        if(fault!=null){source.sendFailure(Component.literal(fault));return 0;}
        for(String id:ids){Device d=RegisteredSwitchRegistry.runtime(source.getServer().overworld()).stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();
            if(!d.eligible()){source.sendFailure(Component.literal(id+": HELD "+d.heldReason()));continue;}
            Observation o=gateway.observe(d);
            source.sendSuccess(()->Component.literal(observationText(d,o)),false);
        }
        return 0; // Readback is not scene success or electrical certification.
    }
    /** Presentation only: sentinel booleans from an unauthenticated/failed read are never shown as physical OFF. */
    static String observationText(Device d,Observation o){
        boolean authenticated=o!=null&&o.authenticated();
        boolean known=authenticated&&(o.fault()==null||o.fault().isBlank());
        String fault=o==null?"No readback":o.fault();
        if(!known&&(fault==null||fault.isBlank()))fault="Not authenticated / not verified";
        String route=d.kind()==Kind.LEVER?"":", route_certified="+d.routeCertified()+", downstream_ready="+(known?Boolean.toString(o.downstreamReady()):"not verified");
        return d.id()+": authenticated="+authenticated+(known?"":", physical=unknown/not verified")
                +", signal="+(known?Boolean.toString(o.powered()):"unknown")
                +", controller_inverted="+(known?Boolean.toString(o.controllerMode()):"unknown")
                +", breaker_inverted="+(known?Boolean.toString(o.breakerMode()):"unknown")
                +", breaker_active="+(known&&o.breakerActive()!=null?o.breakerActive().toString():"unknown")
                +", internal_edge="+(known&&o.breakerInternal()!=null?o.breakerInternal().toString():"unknown")+route+", fault="+fault;
    }
    private static int apply(CommandSourceStack source,List<String> ids,boolean on){
        return report(source,engine(source.getServer()).apply(ids,on,new MinecraftSwitchGateway(source.getServer())));
    }
    private static int report(CommandSourceStack source,Result result){
        Component text=Component.literal("[MyCinema "+result.status()+"] "+result.message()+"; changed/attempted="+result.changed()+"; pending="+result.pending());
        if(result.confirmed())source.sendSuccess(()->text,false);else source.sendFailure(text);
        // Pending, partial and refusals must not be interpreted as successful function execution.
        return result.confirmed()?1:0;
    }
    private RegisteredSwitchCommands(){}
}
