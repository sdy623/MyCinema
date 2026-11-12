package local.cinemamanager.client.seats;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Actual renderer transform: readable from the placement side, upward front and no mirror. */
public final class SeatGuidePoseChecks {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static boolean near(Vector3f actual,float x,float y,float z){return actual.distance(x,y,z)<.00001f;}
    public static void main(String[] args){
        for(Direction facing:Direction.Plane.HORIZONTAL){
            PoseStack floor=new PoseStack();SeatGuideRenderer.orient(floor,facing,true);
            var matrix=floor.last().pose();
            Vector3f center=matrix.transformPosition(new Vector3f()),right=matrix.transformDirection(new Vector3f(1,0,0)),top=matrix.transformDirection(new Vector3f(0,1,0)),front=matrix.transformDirection(new Vector3f(0,0,1));
            check(near(center,.5f,.033f,.5f),"floor text clears the half-pixel body without floating above it");
            check(near(front,0,1,0),"glyph front faces upward for "+facing);
            check(near(top,-facing.getStepX(),0,-facing.getStepZ()),"text and up-arrow point away from the reader for "+facing);
            check(near(right,facing.getStepZ(),0,-facing.getStepX()),"right-arrow points to the reader's right for "+facing);
            check(new Vector3f(right).cross(top).dot(front)>.999f,"floor pose cannot mirror glyphs or arrows");
            PoseStack wall=new PoseStack();SeatGuideRenderer.orient(wall,facing,false);
            check(near(wall.last().pose().transformDirection(new Vector3f(0,0,1)),facing.getStepX(),0,facing.getStepZ()),"wall text keeps its outward normal for "+facing);
            check(near(wall.last().pose().transformDirection(new Vector3f(0,1,0)),0,1,0),"legacy wall text remains upright");
        }
        System.out.println("PASS: "+checks+" seat-guide renderer pose checks across four wall/floor facings.");
    }
}
