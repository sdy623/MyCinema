package local.cinemamanager.access;

import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.*;

/** Written/blank icons remain independent of the server's access decisions. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class AccessCardAppearanceGameTests {
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_card_icons_distinguish_written_roles_without_granting_access(GameTestHelper h){
        ItemStack blank=new ItemStack(AccessModule.CARD.get());
        h.assertTrue(StaffCardItem.visualState(blank)==0,"a real blank has the empty icon");
        CompoundTag decoration=new CompoundTag();decoration.putString("staffVisualRole","SERVICE");blank.set(DataComponents.CUSTOM_DATA,CustomData.of(decoration));
        h.assertTrue(StaffCardItem.visualState(blank)==0,"a role decoration without a token is still blank");
        UUID actor=UUID.randomUUID(),issuer=UUID.randomUUID();
        var scope=new StaffGrantBook.Scope(UUID.randomUUID(),issuer,1,UUID.randomUUID(),issuer,2,UUID.randomUUID(),3,UUID.randomUUID());
        StaffGrantBook book=new StaffGrantBook();
        var entry=book.issue(actor,issuer,scope,StaffRole.ENTRY);ItemStack card=new ItemStack(AccessModule.CARD.get());StaffCardItem.stamp(card,entry,"Icon QA");
        h.assertTrue(StaffCardItem.visualState(card)==1&&StaffCardItem.visualState(card.copy())==1,"stamped front-of-house icon survives item copy");
        decoration=card.get(DataComponents.CUSTOM_DATA).copyTag();decoration.putString("staffVisualRole","SERVICE");card.set(DataComponents.CUSTOM_DATA,CustomData.of(decoration));
        h.assertTrue(StaffCardItem.visualState(card)==2,"cosmetic field selects the service artwork");
        h.assertFalse(book.authorize(StaffCardItem.token(card),actor,scope,StaffRole.SERVICE),"forged service artwork cannot upgrade the actual ENTRY grant");
        var service=book.issue(actor,issuer,scope,StaffRole.SERVICE);StaffCardItem.stamp(card,service,"Icon QA");
        h.assertTrue(StaffCardItem.visualState(card)==2&&book.authorize(StaffCardItem.token(card),actor,scope,StaffRole.SERVICE),"real service grant has its distinct artwork");
        decoration=card.get(DataComponents.CUSTOM_DATA).copyTag();decoration.remove("staffVisualRole");card.set(DataComponents.CUSTOM_DATA,CustomData.of(decoration));
        h.assertTrue(StaffCardItem.visualState(card)==3,"legacy token-only cards remain visibly written");
        book.revoke(scope.reader(),actor);
        h.assertTrue(StaffCardItem.visualState(card)==3,"written does not claim current authorization");
        h.assertFalse(book.authorize(StaffCardItem.token(card),actor,scope,StaffRole.ENTRY),"revoked written card remains denied");h.succeed();
    }
}
