package local.cinemamanager.doors;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;

/** Only called by explicit native GameTests; records actual engine samples for post-save auditing. */
final class DoorLightReport {
    private DoorLightReport() {}
    private static final Map<String,List<Map<String,Object>>> PHASES=new LinkedHashMap<>();
    static void write(GameTestHelper helper,String test,String phase,List<BlockPos> leaves,List<BlockPos> samples) {
        var level=helper.getLevel();
        List<Map<String,Object>> values=new ArrayList<>();
        for(BlockPos relative:samples) {
            BlockPos pos=helper.absolutePos(relative);Map<String,Object> sample=position(pos);
            sample.put("relative",List.of(relative.getX(),relative.getY(),relative.getZ()));
            sample.put("sky_light",level.getBrightness(LightLayer.SKY,pos));
            sample.put("block_light",level.getBrightness(LightLayer.BLOCK,pos));
            sample.put("can_see_sky",level.canSeeSky(pos));
            sample.put("state",level.getBlockState(pos).toString());values.add(sample);
        }
        List<Map<String,Object>> sections=new ArrayList<>();
        for(BlockPos relative:leaves) for(int y=0;y<3;y++) {
            BlockPos pos=helper.absolutePos(relative.above(y));Map<String,Object> section=position(pos);
            section.put("state",level.getBlockState(pos).toString());sections.add(section);
        }
        Map<String,Object> measurement=new LinkedHashMap<>();
        measurement.put("phase",phase);measurement.put("game_time",level.getGameTime());
        measurement.put("door_sections",sections);measurement.put("samples",values);
        List<Map<String,Object>> phases=PHASES.computeIfAbsent(test,k->new ArrayList<>());phases.add(measurement);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("schema",1);report.put("test",test);report.put("dimension",level.dimension().location().toString());
        report.put("validation_note","Actual engine measurements. Final saved region arrays must be checked separately after clean server shutdown.");
        report.put("phases",phases);
        Path directory=Path.of(System.getProperty("mycinema.doorQaDirectory","door-tools/generated"));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("cinema_door_"+test+"_native_light.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        } catch(IOException failure) { throw new AssertionError("Unable to save native door light evidence: "+directory,failure); }
    }
    private static Map<String,Object> position(BlockPos pos) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("position",List.of(pos.getX(),pos.getY(),pos.getZ()));
        value.put("chunk",List.of(pos.getX()>>4,pos.getZ()>>4));value.put("section_y",pos.getY()>>4);
        value.put("local",List.of(pos.getX()&15,pos.getY()&15,pos.getZ()&15));
        value.put("section_block_index",((pos.getY()&15)<<8)|((pos.getZ()&15)<<4)|(pos.getX()&15));
        return value;
    }
}
