package local.cinemamanager.automation;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;

public final class CinemaAutomationChecks {
    public static void main(String[] args) {
        UUID terminal = UUID.randomUUID(), controller = UUID.randomUUID(), owner = UUID.randomUUID();
        BlockPos position = new BlockPos(4, 5, 6);
        var grant = new CinemaAutomation.Grant(terminal, controller, owner, position, GameType.SURVIVAL);
        var restored = CinemaAutomation.Grant.load(grant.save());
        check(grant.equals(restored) && restored.matches(terminal, controller, owner, position), "grant did not round-trip");
        check(!restored.matches(UUID.randomUUID(), controller, owner, position), "wrong terminal inherited authority");
        check(CinemaAutomation.Grant.load(new CompoundTag()) == null, "empty data created authority");
        CompoundTag spectator = grant.save(); spectator.putString("mode", GameType.SPECTATOR.getName());
        check(CinemaAutomation.Grant.load(spectator) == null, "spectator grant became automation authority");
        CompoundTag malformedPosition = grant.save(); malformedPosition.putString("pos", "broken");
        check(CinemaAutomation.Grant.load(malformedPosition) == null, "wrong NBT type became authority");
        System.out.println("CinemaAutomationChecks PASS");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
