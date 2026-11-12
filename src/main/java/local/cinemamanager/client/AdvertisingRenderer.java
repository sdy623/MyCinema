package local.cinemamanager.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import local.cinemamanager.*;
import local.cinemamanager.advertising.*;
import local.cinemamanager.panels.PanelLayout;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/** Shared physical surface. Printed material uses world light; media draws only a borrowed texture. */
public final class AdvertisingRenderer {
    private static final ResourceLocation WHITE=CinemaManager.id("textures/gui/panel_white.png");
    private AdvertisingRenderer(){}
    private static final java.util.Map<InfoTile,ScreenMode> MODES=new java.util.WeakHashMap<>();
    public static AABB bounds(InfoTile t){return t.advertising.bounds(t.getBlockPos(),t.getBlockState().getValue(AdvertisingBlock.FACING),t.getBlockState().getValue(AdvertisingBlock.FLOOR)).inflate(.003);}
    public static void render(InfoTile tile,PoseStack pose,MultiBufferSource buffers,int ambient,InfoRenderer information){
        ScreenMode previous=MODES.put(tile,tile.screenMode);
        if(previous!=null&&previous!=tile.screenMode)PosterImages.retry(tile);
        var state=tile.getBlockState();var block=(AdvertisingBlock)state.getBlock();var g=tile.advertising;boolean floor=state.getValue(AdvertisingBlock.FLOOR);
        int frame=block.emissive()?0x26323c:0x796951;int surface=block.emissive()?LightTexture.FULL_BRIGHT:ambient;
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-state.getValue(AdvertisingBlock.FACING).toYRot()));
        double front=floor?.0625:-.375,back=floor?-.0625:-.5;
        var picture=tile.screenMode==ScreenMode.PICTURE&&!tile.publishedPoster.isBlank()?PosterImages.lookup(tile):null;
        boolean pictureReady=picture!=null&&picture.status()==PosterImages.Status.READY;
        boolean shaped=tile.screenMode==ScreenMode.PICTURE&&!tile.advertisingShape.rectangular();
        if(shaped){for(var r:tile.advertisingShape.bodyRectangles(g)){if(pictureReady)sides(pose,buffers,(float)r.left()-.5f,(float)r.bottom(),(float)r.right()-.5f,(float)r.top(),(float)back,(float)front,frame,ambient);else box(pose,buffers,(float)r.left()-.5f,(float)r.bottom(),(float)r.right()-.5f,(float)r.top(),(float)back,(float)front,frame,ambient);}}
        else box(pose,buffers,g.left()/8f-.5f,g.bottom()/8f,g.right()/8f-.5f,g.top()/8f,(float)back,(float)front,frame,ambient);
        if(floor)box(pose,buffers,g.left()/8f-.5f,g.bottom()/8f,g.right()/8f-.5f,g.bottom()/8f+.125f,-.5f,.0625f,frame,ambient);
        pose.translate(g.left()/8.0-.5,g.top()/8.0,front+.0015);
        pose.scale(1,-1,1);
        float width=(float)g.widthBlocks(),height=(float)g.heightBlocks();
        if(tile.screenMode==ScreenMode.INFORMATION){information.renderContent(tile,pose,buffers,width,height,surface);pose.popPose();return;}
        if(!shaped)quad(pose,buffers,WHITE,new PanelLayout.Rect(0,0,width,height),0,block.emissive()?0x080d12:0xe2ded4,surface);
        if(tile.screenMode==ScreenMode.MEDIA&&tile.publishedMirror!=null){
            var movie=CinemaMirrorFrames.current(tile.getLevel(),tile.publishedMirror);
            if(movie!=null){if(movie.kind()==CinemaMirrorFrames.Kind.BLACK)quad(pose,buffers,WHITE,new PanelLayout.Rect(0,0,width,height),.001f,0,surface);else quad(pose,buffers,movie.texture(),PanelLayout.contain(new PanelLayout.Rect(0,0,width,height),movie.width(),movie.height()),.001f,0xffffff,surface);}
        }
        if(tile.screenMode==ScreenMode.PICTURE&&!tile.publishedPoster.isBlank()){
            var image=picture;if(pictureReady){float border=shaped?0:Math.min(.02f,Math.min(width,height)/10);
                var fit=PanelLayout.contain(new PanelLayout.Rect(border,border,width-2*border,height-2*border),image.width(),image.height());quad(pose,buffers,image.texture(),fit,.001f,0xffffff,surface);if(shaped)quad(pose,buffers,image.texture(),fit,(float)(back-front-.003),0xffffff,surface);}
        }
        pose.popPose();
    }
    private static void box(PoseStack pose,MultiBufferSource buffers,float x0,float y0,float x1,float y1,float z0,float z1,int rgb,int light){
        face(pose,buffers,new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}},rgb,light);
        face(pose,buffers,new float[][]{{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}},rgb,light);
        face(pose,buffers,new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}},rgb,light);
        face(pose,buffers,new float[][]{{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}},rgb,light);
        face(pose,buffers,new float[][]{{x0,y1,z1},{x1,y1,z1},{x1,y1,z0},{x0,y1,z0}},rgb,light);
        face(pose,buffers,new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}},rgb,light);
    }
    /** The PNG supplies the front/back cutout; only bounded thickness edges use solid material. */
    private static void sides(PoseStack pose,MultiBufferSource buffers,float x0,float y0,float x1,float y1,float z0,float z1,int rgb,int light){
        face(pose,buffers,new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}},rgb,light);
        face(pose,buffers,new float[][]{{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}},rgb,light);
        face(pose,buffers,new float[][]{{x0,y1,z1},{x1,y1,z1},{x1,y1,z0},{x0,y1,z0}},rgb,light);
        face(pose,buffers,new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}},rgb,light);
    }
    private static void face(PoseStack pose,MultiBufferSource buffers,float[][]v,int rgb,int light){var out=buffers.getBuffer(RenderType.text(WHITE));var m=pose.last().pose();for(int i=0;i<4;i++)out.addVertex(m,v[i][0],v[i][1],v[i][2]).setColor(0xff000000|rgb).setUv(i==1||i==2?1:0,i<2?1:0).setLight(light);}
    private static void quad(PoseStack pose,MultiBufferSource buffers,ResourceLocation texture,PanelLayout.Rect r,float z,int rgb,int light){var out=buffers.getBuffer(RenderType.text(texture));var m=pose.last().pose();int c=0xff000000|rgb;
        out.addVertex(m,r.x(),r.y()+r.height(),z).setColor(c).setUv(0,1).setLight(light);out.addVertex(m,r.x()+r.width(),r.y()+r.height(),z).setColor(c).setUv(1,1).setLight(light);out.addVertex(m,r.x()+r.width(),r.y(),z).setColor(c).setUv(1,0).setLight(light);out.addVertex(m,r.x(),r.y(),z).setColor(c).setUv(0,0).setLight(light);}
}
