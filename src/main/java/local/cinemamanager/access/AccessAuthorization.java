package local.cinemamanager.access;

import java.util.function.Predicate;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.doors.DoorPart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** One server-side physical-door boundary; credential policies do not own the actuator. */
final class AccessAuthorization {
    record Context(StaffAccessRegistry registry,StaffReaderTile reader,ManagerTile manager,StaffGrantBook.Scope scope) {}
    private AccessAuthorization(){}
    static boolean open(ServerPlayer actor,StaffAccessRegistry.Door binding,Predicate<Context> credential){
        if(!StaffReaderTile.real(actor)||!actor.isAlive()||binding==null)return false;
        var level=actor.serverLevel();var registry=StaffAccessRegistry.get(level);BlockPos readerPos=BlockPos.of(binding.readerPos);
        if(registry.door(binding.base())!=binding||!level.hasChunkAt(readerPos)
                ||!(level.getBlockEntity(readerPos) instanceof StaffReaderTile reader)||!reader.identity.equals(binding.reader)
                ||!level.mayInteract(actor,readerPos))return false;
        var scope=reader.currentScope();var manager=reader.controller();
        if(scope==null||manager==null||!scope.door().equals(binding.token))return false;
        for(var leaf:binding.leaves){BlockPos base=BlockPos.of(leaf.pos());int parts=FixedIronDoorAccess.candidate(level,base)?2:3;
            for(int offset=0;offset<parts;offset++)if(!level.mayInteract(actor,base.above(offset)))return false;
        }
        return credential.test(new Context(registry,reader,manager,scope))
                &&AccessDoors.pulse(level,binding,BlockPos.of(binding.base()));
    }
}
