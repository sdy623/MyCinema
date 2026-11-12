package local.cinemamanager.seats.map;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import com.mojang.brigadier.arguments.*;
import local.cinemamanager.*;
import local.cinemamanager.seats.SeatRegistry;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit owner import, fixed per-manager/per-hall file, no user supplied filesystem paths. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class SeatMapImportCommand {
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("mycinema_seat_map")
            .then(Commands.literal("import").then(Commands.argument("manager",BlockPosArgument.blockPos())
            .then(Commands.argument("hall",StringArgumentType.word())
            .then(Commands.argument("registryRevision",LongArgumentType.longArg(0))
            .then(Commands.argument("layoutRevision",LongArgumentType.longArg(0)).executes(context->{
                var actor=context.getSource().getPlayerOrException();
                try{
                    BlockPos pos=BlockPosArgument.getBlockPos(context,"manager");
                    if(!actor.level().hasChunkAt(pos)||!(actor.level().getBlockEntity(pos) instanceof ManagerTile manager)||!SeatMapNetwork.managerAllowed(actor,manager))throw SeatMapGeometry.invalid("permission");
                    String hall=SeatRegistry.uuid(StringArgumentType.getString(context,"hall"));
                    if(manager.state.halls.stream().noneMatch(h->h.id.equals(hall)))throw SeatMapGeometry.invalid("hall_missing");
                    Path root=actor.getServer().getWorldPath(LevelResource.ROOT).resolve("mycinema-seat-maps");
                    Path directory=root.resolve(manager.identity.toString()),file=directory.resolve(hall+".json");
                    if(Files.isSymbolicLink(root)||Files.isSymbolicLink(directory)||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw SeatMapGeometry.invalid("import_file");
                    byte[] bytes;try(InputStream in=Files.newInputStream(file)){bytes=in.readNBytes(SeatMapLayoutDocument.MAX_BYTES+1);}
                    if(bytes.length>SeatMapLayoutDocument.MAX_BYTES)throw SeatMapGeometry.invalid("layout_size");
                    String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    SeatMapStorage.get((ServerLevel)actor.level(),manager.identity,hall).importAuthored(actor,manager,hall,LongArgumentType.getLong(context,"registryRevision"),LongArgumentType.getLong(context,"layoutRevision"),raw);
                    context.getSource().sendSuccess(()->Component.translatable("mycinema.seats.map.imported"),false);return 1;
                }catch(Exception bad){
                    String key=SeatMapWire.notice(bad,"import_failed");
                    context.getSource().sendFailure(Component.translatable(key));return 0;
                }
            })))))));
    }
    private SeatMapImportCommand(){}
}
