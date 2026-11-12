package local.cinemamanager.seats.fractional;

/** Pure one-time facing policy checks. No Minecraft bootstrap is needed. */
public final class FractionalSeatFacingChecks {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        float[] expected = {180,270,0,90};
        for (int turn=0; turn<4; turn++) {
            check(FractionalSeatFacing.frontYaw(turn)==expected[turn], "four chair fronts");
            for (float incoming : new float[]{expected[turn]+180, -540, 359.9f, 47}) {
                var before=new FractionalSeatFacing.Rotation(incoming,incoming-10,incoming+20,incoming-20,incoming+30,incoming-30,25);
                var after=FractionalSeatFacing.afterSuccessfulMount(true,turn,before);
                check(after.yaw()==expected[turn] && after.headYaw()==expected[turn] && after.bodyYaw()==expected[turn], "all current headings align");
                check(after.previousYaw()==expected[turn] && after.previousHeadYaw()==expected[turn] && after.previousBodyYaw()==expected[turn], "no stale-angle spin");
                check(after.pitch()==before.pitch(), "vertical look preserved");
                check(FractionalSeatFacing.afterSuccessfulMount(false,turn,before)==before, "failed entry changes nothing");
            }
        }
        for(int invalid:new int[]{-1,4}) {
            try { FractionalSeatFacing.frontYaw(invalid); throw new AssertionError("invalid turn accepted"); }
            catch(IllegalArgumentException expectedFailure) { checks++; }
        }
        System.out.println("FractionalSeatFacingChecks: "+checks+" passed");
    }
}
