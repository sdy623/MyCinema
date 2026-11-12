package local.cinemamanager.client.seats;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.seats.*;
import net.minecraft.core.Direction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import java.util.*;

@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT)
public final class SeatGuideRenderer implements BlockEntityRenderer<SeatGuideTile> {
    private final Font font;
    public SeatGuideRenderer(BlockEntityRendererProvider.Context context){font=context.getFont();}
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(SeatsModule.GUIDE_TILE.get(),SeatGuideRenderer::new);}
    @Override public void render(SeatGuideTile tile,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        GuidePreset preset=tile.preset;List<Component> lines=new ArrayList<>();String caption=(preset.hall()+" "+preset.zone()).strip();if(!caption.isEmpty())lines.add(Component.literal(caption));
        Component numbers=preset.rowPrefix()==GuidePreset.RowPrefix.LEGACY&&!preset.hasSeatRange()
            ?(preset.seat()>0?Component.translatable("mycinema.seats.seat_label",preset.firstLabel(),preset.seat()):Component.translatable("mycinema.seats.rows_label",preset.firstLabel(),preset.lastLabel()))
            :Component.literal(preset.displayLabel());
        lines.add(Component.literal(preset.arrowText().isEmpty()?"":preset.arrowText()+" ").append(numbers));
        pose.pushPose();
        orient(pose,tile.getBlockState().getValue(SeatGuideBlock.FACING),tile.getBlockState().getValue(SeatGuideBlock.FLOOR));
        int widest=lines.stream().mapToInt(font::width).max().orElse(1);float scale=Math.min(.010f,.46f/Math.max(1,widest));pose.scale(scale,-scale,scale);
        for(int i=0;i<lines.size();i++){Component line=lines.get(i);font.drawInBatch(line,-font.width(line)/2f,(i-(lines.size()-1)/2f)*10-4,0xfff4ca,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0,light);}
        pose.popPose();
    }
    static void orient(PoseStack pose,Direction facing,boolean floor){
        // FACING points toward the reader in both modes; floor text reads away from their feet.
        pose.translate(.5,floor?.033:.5,.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        if(floor)pose.mulPose(Axis.XP.rotationDegrees(-90));else pose.translate(0,0,-.467);
    }
}
