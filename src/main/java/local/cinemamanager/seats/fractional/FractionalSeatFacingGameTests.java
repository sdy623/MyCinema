package local.cinemamanager.seats.fractional;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

/** Native field application check; full mount/packet/failure acceptance still needs the QA client. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FractionalSeatFacingGameTests {
    @GameTest(template="empty",timeoutTicks=40)
    public static void fractional_mount_facing_is_once_and_preserves_position(GameTestHelper h) {
        ServerPlayer player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),
            new GameProfile(UUID.randomUUID(),"FacingQA"),ClientInformation.createDefault());
        Vec3 position=Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4,3,4)));player.setPos(position);
        float[] expected={180,270,0,90};
        for(int turn=0;turn<4;turn++) {
            player.setYRot(expected[turn]+180);player.yRotO=17;
            player.setYHeadRot(27);player.yHeadRotO=37;player.setYBodyRot(47);player.yBodyRotO=57;player.setXRot(25);
            FractionalSeatIntegration.applyMountFacing(player,turn);
            h.assertTrue(player.getYRot()==expected[turn] && player.yRotO==expected[turn],"current and previous yaw agree");
            h.assertTrue(player.getYHeadRot()==expected[turn] && player.yHeadRotO==expected[turn],"head has no interpolation spin");
            h.assertTrue(player.yBodyRot==expected[turn] && player.yBodyRotO==expected[turn],"body faces chair front");
            h.assertTrue(player.position().equals(position) && player.getXRot()==25,"XYZ and pitch unchanged");
            player.setYRot(33);player.setYHeadRot(44);
            h.assertTrue(player.getYRot()==33 && player.getYHeadRot()==44,"normal later looking is not locked");
        }
        h.succeed();
    }
    private FractionalSeatFacingGameTests() {}
}
