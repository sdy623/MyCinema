package local.cinemamanager.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoBlock;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class InfoRenderer implements BlockEntityRenderer<InfoTile> {
    private static final ResourceLocation WHITE = CinemaManager.id("textures/gui/panel_white.png");
    private final Font font;
    private final Map<InfoTile, CachedPages> cache = new WeakHashMap<>();
    private record PageKey(PanelSettings settings, PanelScope scope, String timezone, String day, List<PublicShowRow> rows,
                           int width, int linesPerPage, Object language, local.cinemamanager.operations.PublicOperationsSnapshot operations, boolean invalidRole) {}
    private record CachedPages(PageKey key, List<List<GuestPanelText.Line>> pages) {}

    public InfoRenderer(BlockEntityRendererProvider.Context context) { font = context.getFont(); }

    @Override
    public AABB getRenderBoundingBox(InfoTile tile) {
        if(tile.isAdvertising())return AdvertisingRenderer.bounds(tile);
        PanelGroup group = PanelGroup.of(tile);
        return group.root()==tile?new AABB(tile.getBlockPos()).expandTowards(PanelGroup.right(tile).getStepX()*(group.width()-1),group.height()-1,PanelGroup.right(tile).getStepZ()*(group.width()-1)):new AABB(tile.getBlockPos());
    }

    @Override
    public void render(InfoTile tile, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if(tile.isAdvertising()){AdvertisingRenderer.render(tile,pose,buffers,light,this);return;}
        PanelGroup group = PanelGroup.of(tile);
        if (group.root() != tile) return;
        pose.pushPose();
        pose.translate(.5, group.height() - .04, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-tile.getBlockState().getValue(InfoBlock.FACING).toYRot()));
        pose.translate(-.46, 0, tile.getBlockState().getBlock() instanceof WallInfoBlock ? -.373 : .506);
        pose.scale(1,-1,1);
        renderContent(tile, pose, buffers, group.width()-.08f, group.height()-.08f, 0xF000F0);
        pose.popPose();
    }

    /** Draw in a caller-owned positive-right/positive-down plane; no media player is created. */
    void renderContent(InfoTile tile, PoseStack pose, MultiBufferSource buffers, float widthBlocks, float heightBlocks, int packedLight) {
        PanelSettings settings=tile.panel;
        float scale=.0064f*settings.textPercent()/100f;
        float width=widthBlocks/scale,height=heightBlocks/scale;
        pose.pushPose();pose.scale(scale,scale,scale);
        quad(pose, buffers, WHITE, tile.isAdvertising()?new PanelLayout.Rect(0,0,width,height):new PanelLayout.Rect(-2, -2, width + 4, height + 4), 0, settings.background(), packedLight);
        pose.translate(0, 0, .4f);
        PanelLayout.Areas areas = PanelLayout.areas(width, height, settings.layout(),settings.style().posterPercent());
        if (settings.layout() != PanelSettings.Layout.TEXT && !tile.publishedPoster.isBlank()) {
            PosterImages.Result image = PosterImages.lookup(tile);
            if (image.status() == PosterImages.Status.READY)
                quad(pose, buffers, image.texture(), PanelLayout.contain(areas.poster(), image.width(), image.height(),settings.style()), .2f, 0xffffff, packedLight);
            // Loading and failure diagnostics belong in the owner editor, never on the public wall.
        }
        PanelLayout.Rect area = areas.text();
        int lineHeight = font.lineHeight + 2;
        int capacity = Math.max(1, (int) Math.floor(area.height() / lineHeight));
        PublicPanelSnapshot snapshot = tile.publicSnapshot;
        PageKey key = new PageKey(settings, tile.scope, snapshot.timezone(), PublicTimeRange.dayKey(snapshot.generatedAt(),snapshot.timezone()), snapshot.rows(),
                Math.max(1, (int) area.width()), capacity, net.minecraft.locale.Language.getInstance(),tile.isOperations()?tile.operationsSnapshot:null,tile.invalidTerminalRole());
        CachedPages cached = cache.get(tile);
        if (cached == null || !cached.key().equals(key)) {
            List<GuestPanelText.Line> lines = tile.isOperations()||tile.invalidTerminalRole()
                    ?OperationsWallText.lines(font,key.width(),settings,tile.operationsSnapshot,tile.invalidTerminalRole())
                    :GuestPanelText.lines(font, key.width(), settings, tile.scope, snapshot, true);
            int contentCapacity = PanelLayout.textPage(area,lines.size(),lineHeight,font.lineHeight).linesPerPage();
            cached = new CachedPages(key, PublicPanelPagination.paginate(lines, contentCapacity));
            cache.put(tile, cached);
        }
        List<List<GuestPanelText.Line>> pages = cached.pages();
        if (!pages.isEmpty()) {
            long gameTime = tile.getLevel() == null ? 0 : tile.getLevel().getGameTime();
            // Absolute game time makes every refresh independent of the eight-second rotation.
            int page = PublicPanelPagination.pageForRotation(gameTime, pages.size());
            List<GuestPanelText.Line> lines = pages.get(page);
            var pageLayout=PanelLayout.textPage(area,pages.stream().mapToInt(List::size).sum(),lineHeight,font.lineHeight);
            PanelLayout.Rect content=pageLayout.content();
            var positions=GuestPanelText.positions(font,content,lines,settings);
            for (int index = 0; index < lines.size(); index++) {
                GuestPanelText.Line line = lines.get(index);
                var position=positions.get(index);
                pose.pushPose();pose.translate(position.x(),position.y(),0);pose.scale(position.scale(),position.scale(),1);
                font.drawInBatch(line.text(), 0, 0, line.color(), false,
                        pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, packedLight);
                pose.popPose();
            }
            if (pages.size() > 1 && pageLayout.hasIndicator()) {
                Component indicator = Component.literal((page + 1) + "/" + pages.size());
                PanelLayout.Rect marker=pageLayout.indicator();
                float indicatorScale = Math.min(1f,Math.min(marker.width()/Math.max(1,font.width(indicator)),marker.height()/font.lineHeight));
                pose.pushPose();
                pose.translate(marker.x() + (marker.width() - font.width(indicator) * indicatorScale) / 2,
                        marker.y(), 0);
                pose.scale(indicatorScale, indicatorScale, 1);
                font.drawInBatch(indicator, 0, 0, settings.foreground(), false,
                        pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, packedLight);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    private static void quad(PoseStack pose, MultiBufferSource buffers, ResourceLocation texture, PanelLayout.Rect rect, float z, int rgb, int light) {
        var out = buffers.getBuffer(RenderType.text(texture));
        var matrix = pose.last().pose();
        int color = 0xff000000 | rgb;
        out.addVertex(matrix, rect.x(), rect.y() + rect.height(), z).setColor(color).setUv(0, 1).setLight(light);
        out.addVertex(matrix, rect.x() + rect.width(), rect.y() + rect.height(), z).setColor(color).setUv(1, 1).setLight(light);
        out.addVertex(matrix, rect.x() + rect.width(), rect.y(), z).setColor(color).setUv(1, 0).setLight(light);
        out.addVertex(matrix, rect.x(), rect.y(), z).setColor(color).setUv(0, 0).setLight(light);
    }
}
