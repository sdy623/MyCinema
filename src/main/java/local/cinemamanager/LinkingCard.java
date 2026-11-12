package local.cinemamanager;
import local.cinemamanager.model.ManagerState.Hall;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
public final class LinkingCard extends Item {
    public LinkingCard(Properties p) { super(p); }
    @Override public InteractionResult useOn(UseOnContext c) {
        if (c.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(c.getPlayer() instanceof ServerPlayer actor)) return InteractionResult.FAIL;
        var clicked = c.getLevel().getBlockEntity(c.getClickedPos());
        CompoundTag data = c.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        try {
            if (clicked instanceof ManagerTile m) {
                if (!m.permits(actor,8)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m075"));
                String hallId = m.state.halls.isEmpty() ? "" : m.state.halls.getFirst().id;
                if (actor.isShiftKeyDown() && !m.state.halls.isEmpty()) {
                    int old = -1; for (int i=0;i<m.state.halls.size();i++) if (m.state.halls.get(i).id.equals(data.getString("hall"))) old=i;
                    hallId=m.state.halls.get((old+1)%m.state.halls.size()).id;
                }
                data.putLong("manager",m.getBlockPos().asLong()); data.putUUID("identity",m.identity); data.putString("dimension",c.getLevel().dimension().location().toString()); data.putString("hall",hallId);
                actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m076") + (hallId.isBlank()?local.cinemamanager.MessageText.key("message.m077"):m.state.hall(hallId).name) + local.cinemamanager.MessageText.key("message.m078")),false);
            } else {
                if (!data.hasUUID("identity") || !data.getString("dimension").equals(c.getLevel().dimension().location().toString())) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m079"));
                BlockPos pos=BlockPos.of(data.getLong("manager"));
                if (!c.getLevel().hasChunkAt(pos) || !(c.getLevel().getBlockEntity(pos) instanceof ManagerTile m) || !m.identity.equals(data.getUUID("identity"))) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m080"));
                if (!m.permits(actor,Double.POSITIVE_INFINITY)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m081"));
                if (clicked instanceof CinemaTerminalTile t) { Hall h=m.enroll(actor,t,true); data.putString("hall",h.id); actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m082") + h.name),false); }
                else if (clicked instanceof EndpointTile out) { m.bindOutput(actor,out,data.getString("hall")); actor.displayClientMessage(local.cinemamanager.MessageText.component(m.state.notice),false); }
                else if (clicked instanceof InfoTile info) {
                    if (!info.permits(actor,8)) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m083"));
                    info.bind(actor,m,data.getString("hall")); actor.displayClientMessage(local.cinemamanager.MessageText.component(local.cinemamanager.MessageText.key("message.m084")),false);
                } else throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m085"));
            }
            c.getItemInHand().set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        } catch (IllegalArgumentException e) { actor.displayClientMessage(local.cinemamanager.MessageText.component(e.getMessage()),false); }
        return InteractionResult.SUCCESS;
    }
}
